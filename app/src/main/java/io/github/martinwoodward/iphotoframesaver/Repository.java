package io.github.martinwoodward.iphotoframesaver;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import io.github.martinwoodward.iphotoframesaver.core.AlbumAddress;
import io.github.martinwoodward.iphotoframesaver.core.Photo;
import io.github.martinwoodward.iphotoframesaver.core.PlaybackPolicy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class Repository {
    static final class Snapshot {
        final String key;
        final List<Photo> photos;
        final long refreshed;
        Snapshot(String key, List<Photo> photos, long refreshed) {
            this.key = key;
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
            this.refreshed = refreshed;
        }
        Photo find(String id) {
            for (Photo p : photos) if (p.id.equals(id)) return p;
            return null;
        }
    }

    final Context context;
    final Settings settings;
    final ExecutorService background = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor();
    private final AtomicInteger prefetchGeneration = new AtomicInteger();
    volatile Snapshot current = new Snapshot("", Collections.emptyList(), 0);
    volatile String status = "Add a public shared album to get started.";
    volatile String downloadStatus = "";
    volatile boolean downloading;
    volatile boolean cancelDownload;
    private volatile long lastAttempt;
    private String attachedIdentity = "";
    private AttachedStorage attached;
    private final java.util.Map<File, Integer> pins = new java.util.HashMap<>();
    // Saved-photo startup must not wait for the monitor held by network transfers.
    private final Object metadataLock = new Object();
    private final Object cacheLock = new Object();

    final class Lease implements AutoCloseable {
        final File file;
        private final boolean temporary;
        private boolean closed;
        Lease(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
            synchronized (cacheLock) {
                pins.put(file, pins.containsKey(file) ? pins.get(file) + 1 : 1);
            }
        }
        @Override public void close() {
            synchronized (cacheLock) {
                if (closed) return;
                closed = true;
                int remaining = pins.get(file) - 1;
                if (remaining == 0) pins.remove(file); else pins.put(file, remaining);
                if (temporary && !file.delete()) status = "A temporary photo could not be removed.";
            }
        }
    }

    Repository(Context context) {
        this.context = context.getApplicationContext();
        settings = new Settings(context);
    }

    static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    String key() { return settings.album().isEmpty() ? "" : hash(settings.album()); }

    private File directory(String type, String key) throws FrameException {
        File dir = new File(new File(context.getFilesDir(), type), key);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FrameException(FrameException.STORAGE);
        return dir;
    }

    private File metadata(String key) { return new File(context.getFilesDir(), "album-" + key + ".json"); }

    Snapshot load() {
        synchronized (metadataLock) { return loadSaved(); }
    }

    private Snapshot loadSaved() {
        String key = key();
        if (key.equals(current.key)) return current;
        lastAttempt = 0;
        Snapshot loaded = new Snapshot(key, Collections.emptyList(), 0);
        if (!key.isEmpty() && metadata(key).exists()) {
            try {
                byte[] bytes = new AtomicFile(metadata(key)).readFully();
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONArray array = json.getJSONArray("photos");
                List<Photo> photos = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) photos.add(Photo.fromJson(array.getJSONObject(i)));
                loaded = new Snapshot(key, photos, json.getLong("refreshed"));
                status = "Saved album ready.";
            } catch (IOException | JSONException e) {
                status = "Saved album data could not be read. Check the album again to recover.";
            }
        } else if (!key.isEmpty()) status = "Ready to check your album.";
        current = loaded;
        return current;
    }

    synchronized Snapshot refresh(boolean force) throws FrameException {
        Snapshot before = load();
        if (before.key.isEmpty()) return before;
        long now = System.currentTimeMillis();
        if (!force && ((!before.photos.isEmpty()
                && !PlaybackPolicy.refreshDue(now, before.refreshed, settings.refresh()))
                || (lastAttempt > 0 && now - lastAttempt < 60_000))) return before;
        lastAttempt = now;
        status = "Checking album for photos...";
        try {
            List<Photo> photos = new AlbumClient().fetch(AlbumAddress.parse(settings.album()));
            long refreshed = System.currentTimeMillis();
            JSONArray array = new JSONArray();
            for (Photo p : photos) array.put(p.json());
            JSONObject json = new JSONObject().put("refreshed", refreshed).put("photos", array);
            synchronized (metadataLock) {
                if (!before.key.equals(key())) return loadSaved();
                write(new AtomicFile(metadata(before.key)), json.toString().getBytes(StandardCharsets.UTF_8));
                current = new Snapshot(before.key, photos, refreshed);
                status = photos.isEmpty() ? "No photos found. Add photos to the shared album, then check again."
                        : "Album up to date. " + photos.size() + " photos ready.";
                return current;
            }
        } catch (FrameException e) {
            status = e.getMessage();
            throw e;
        } catch (IOException e) {
            status = FrameException.STORAGE;
            throw new FrameException(status);
        } catch (JSONException | IllegalArgumentException e) {
            status = FrameException.ALBUM;
            throw new FrameException(status);
        }
    }

    private static void write(AtomicFile atomic, byte[] bytes) throws IOException {
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (IOException e) {
            if (out != null) atomic.failWrite(out);
            throw e;
        }
    }

    private synchronized AttachedStorage attached(String key) {
        String identity = settings.tree() + ":" + key;
        if (settings.tree().isEmpty()) return null;
        if (!identity.equals(attachedIdentity)) {
            attached = new AttachedStorage(context.getContentResolver(), settings.tree(), key);
            attachedIdentity = identity;
        }
        return attached;
    }

    static String filename(Photo p) { return hash(p.id) + ".photo"; }

    private File cachedFile(Snapshot snapshot, Photo photo) {
        String name = filename(photo);
        File offline = new File(new File(new File(context.getFilesDir(), "offline"), snapshot.key), name);
        if (offline.isFile()) return offline;
        File stream = new File(new File(new File(context.getFilesDir(), "stream"), snapshot.key), name);
        return stream.isFile() ? stream : null;
    }

    Lease acquireCached(Snapshot snapshot, Photo photo) {
        synchronized (cacheLock) {
            File source = cachedFile(snapshot, photo);
            return source == null ? null : new Lease(source, false);
        }
    }

    List<String> cachedIds(Snapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (Photo photo : snapshot.photos) if (cachedFile(snapshot, photo) != null) result.add(photo.id);
        return result;
    }

    void prepareStartupCache(Snapshot snapshot) {
        if (snapshot.key.isEmpty() || !snapshot.key.equals(key())) return;
        int target = Math.min(PlaybackPolicy.PREFETCH + 1, snapshot.photos.size());
        List<String> window = cachedIds(snapshot);
        if (window.size() >= target) return;
        List<String> choices = new ArrayList<>();
        for (Photo photo : snapshot.photos) choices.add(photo.id);
        Collections.shuffle(choices);
        for (String id : choices) {
            if (window.size() == target) break;
            if (!window.contains(id)) window.add(id);
        }
        prefetch(snapshot, "", window);
    }

    synchronized Lease acquire(Snapshot snapshot, Photo photo, boolean network) throws FrameException {
        File source = file(snapshot, photo, network);
        return source == null ? null : new Lease(source, false);
    }

    synchronized File file(Snapshot snapshot, Photo photo, boolean network) throws FrameException {
        String name = filename(photo);
        File offline = new File(directory("offline", snapshot.key), name);
        if (offline.isFile()) return offline;
        File stream = new File(directory("stream", snapshot.key), name);
        if (stream.isFile()) return stream;
        File temporary = new File(stream.getParentFile(), name + ".part");
        if ("attached".equals(settings.storage())) {
            AttachedStorage storage = attached(snapshot.key);
            if (storage != null) {
                try {
                    if (storage.contains(name) && stream.getParentFile().getUsableSpace() < 160L * 1024 * 1024) {
                        throw new FrameException(FrameException.STORAGE);
                    }
                    if (storage.copyTo(name, temporary)) {
                        if (!decodable(temporary)) {
                            storage.invalidate(name);
                            if (!temporary.delete()) throw new IOException("Damaged cache could not be removed");
                            status = "A damaged offline photo will be downloaded again when connected.";
                        } else {
                            if (!temporary.renameTo(stream)) throw new IOException("Save failed");
                            return stream;
                        }
                    }
                } catch (IOException | SecurityException | IllegalArgumentException e) {
                    status = FrameException.STORAGE;
                    if (temporary.exists() && !temporary.delete()) throw new FrameException(FrameException.STORAGE);
                }
            }
        }
        if (!network) return null;
        if (stream.getParentFile().getUsableSpace() < 160L * 1024 * 1024) {
            throw new FrameException(FrameException.STORAGE);
        }
        try {
            fetchPhoto(snapshot, photo, temporary);
            if (!temporary.renameTo(stream)) throw new FrameException(FrameException.STORAGE);
            return stream;
        } finally {
            if (temporary.exists() && !temporary.delete()) status = FrameException.STORAGE;
        }
    }

    private void fetchPhoto(Snapshot snapshot, Photo photo, File temporary) throws FrameException {
        Photo resource = photo;
        try {
            Net.download(photo.url, temporary);
        } catch (FrameException first) {
            // Signed CDN URLs expire even in manual-refresh mode. Renew only on demand,
            // at most once a minute, while keeping the last successful metadata on failure.
            if (System.currentTimeMillis() - lastAttempt < 60_000) throw first;
            Snapshot renewed = refresh(true);
            Photo fresh = renewed.key.equals(snapshot.key) ? renewed.find(photo.id) : null;
            if (fresh == null) throw first;
            resource = fresh;
            Net.download(fresh.url, temporary);
        }
        if (!decodable(temporary) && !resource.fallback.isEmpty() && !resource.url.equals(resource.fallback)) {
            Net.download(resource.fallback, temporary);
        }
        if (!decodable(temporary)) throw new FrameException(FrameException.DECODE);
    }

    private boolean decodable(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    void prefetch(Snapshot snapshot, String currentId, List<String> next) {
        int generation = prefetchGeneration.incrementAndGet();
        prefetcher.execute(() -> {
            Set<String> keep = new HashSet<>();
            Photo now = snapshot.find(currentId);
            if (now != null) keep.add(filename(now));
            for (String id : next) {
                Photo p = snapshot.find(id);
                if (p != null) keep.add(filename(p));
            }
            boolean complete = true;
            for (String id : next) {
                if (generation != prefetchGeneration.get() || !snapshot.key.equals(key())) return;
                Photo p = snapshot.find(id);
                if (p == null) continue;
                try { file(snapshot, p, true); }
                catch (FrameException e) { complete = false; status = e.getMessage(); }
                prune(snapshot.key, keep, true);
            }
            if (generation == prefetchGeneration.get()) prune(snapshot.key, keep, !complete);
        });
    }

    void stopPrefetch() { prefetchGeneration.incrementAndGet(); }

    private synchronized void prune(String key, Set<String> wanted, boolean preserveFallback) {
        synchronized (cacheLock) { pruneLocked(key, wanted, preserveFallback); }
    }

    private void pruneLocked(String key, Set<String> wanted, boolean preserveFallback) {
        File dir = new File(new File(context.getFilesDir(), "stream"), key);
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> keep = new HashSet<>(wanted);
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) if (pins.containsKey(f)) keep.add(f.getName());
        int retained = 0;
        for (File f : files) if (f.getName().endsWith(".photo") && keep.contains(f.getName())) retained++;
        if (preserveFallback) for (File f : files) {
            if (retained >= PlaybackPolicy.PREFETCH + 1) break;
            if (f.getName().endsWith(".photo") && keep.add(f.getName())) retained++;
        }
        for (File f : files) if (f.getName().endsWith(".photo") && !keep.contains(f.getName())) {
            if (pins.containsKey(f)) continue;
            if (!f.delete()) status = "Some old cache files could not be removed.";
        }
    }

    List<String> playableIds(Snapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (Photo p : snapshot.photos) {
            String name = filename(p);
            if (cachedFile(snapshot, p) != null) result.add(p.id);
            else if ("attached".equals(settings.storage())) {
                AttachedStorage storage = attached(snapshot.key);
                try { if (storage != null && storage.contains(name)) result.add(p.id); }
                catch (IOException | SecurityException | IllegalArgumentException e) { status = FrameException.STORAGE; }
            }
        }
        return result;
    }

    private synchronized Lease offlineSource(Snapshot snapshot, Photo p) throws FrameException {
        File local = new File(directory("offline", snapshot.key), filename(p));
        if (local.isFile()) return new Lease(local, false);
        File stream = new File(directory("stream", snapshot.key), filename(p));
        if (stream.isFile()) return new Lease(stream, false);
        File staging = new File(directory("staging", snapshot.key), filename(p) + ".part");
        if (staging.getParentFile().getUsableSpace() < 160L * 1024 * 1024) throw new FrameException(FrameException.STORAGE);
        try {
            fetchPhoto(snapshot, p, staging);
            return new Lease(staging, true);
        } catch (FrameException e) {
            if (staging.exists() && !staging.delete()) status = FrameException.STORAGE;
            throw e;
        }
    }

    void downloadAlbum() {
        downloadAlbum(true);
    }

    void downloadAlbum(boolean checkMetadata) {
        if (downloading) return;
        downloading = true;
        cancelDownload = false;
        try {
            Snapshot snapshot;
            try { snapshot = checkMetadata ? refresh(true) : load(); }
            catch (FrameException e) { snapshot = load(); }
            if (snapshot.photos.isEmpty()) {
                downloadStatus = "No saved photos yet. Connect and check the album first.";
                return;
            }
            String mode = settings.storage();
            if ("stream".equals(mode)) {
                downloadStatus = "Choose an offline destination first.";
                return;
            }
            int done = 0;
            for (Photo p : snapshot.photos) {
                if (cancelDownload || !snapshot.key.equals(key()) || !mode.equals(settings.storage())) {
                    downloadStatus = "Download stopped. Completed photos are kept.";
                    return;
                }
                downloadStatus = "Saving photo " + (done + 1) + " of " + snapshot.photos.size() + "...";
                String name = filename(p);
                if ("attached".equals(mode)) {
                    AttachedStorage storage = attached(snapshot.key);
                    if (storage == null) throw new FrameException(FrameException.STORAGE);
                    if (!storage.contains(name)) {
                        try (Lease source = offlineSource(snapshot, p)) { storage.save(name, source.file); }
                    }
                } else {
                    File destination = new File(directory("offline", snapshot.key), name);
                    if (!destination.exists()) {
                        File temp = new File(destination.getPath() + ".part");
                        try (Lease source = offlineSource(snapshot, p);
                             java.io.InputStream in = new java.io.FileInputStream(source.file);
                             FileOutputStream out = new FileOutputStream(temp)) {
                            Net.transfer(in, out, 128L * 1024 * 1024);
                            out.getFD().sync();
                        }
                        if (!temp.renameTo(destination)) throw new IOException("Save failed");
                    }
                }
                done++;
            }
            downloadStatus = "Offline album ready. " + done + " photos saved.";
        } catch (FrameException e) {
            downloadStatus = e.getMessage() + " Completed offline photos are kept.";
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            downloadStatus = FrameException.STORAGE + " Completed offline photos are kept.";
        } finally {
            downloading = false;
        }
    }

    void health(String surface, int renders, int width, int height, boolean active) {
        try {
            JSONObject data = new JSONObject().put("surface", surface).put("renders", renders)
                    .put("photos", current.photos.size()).put("active", active)
                    .put("decodedWidth", width).put("decodedHeight", height)
                    .put("intervalSeconds", settings.interval()).put("refresh", settings.refresh())
                    .put("storage", settings.storage()).put("clockEnabled", settings.clock())
                    .put("updatedAt", System.currentTimeMillis());
            write(new AtomicFile(new File(context.getFilesDir(), "health.json")),
                    data.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            status = "Playback is running, but local diagnostics could not be saved.";
        }
    }
}
