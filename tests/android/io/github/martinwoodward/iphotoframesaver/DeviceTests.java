package io.github.martinwoodward.iphotoframesaver;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import io.github.martinwoodward.iphotoframesaver.core.Photo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Runs against isolated synthetic preferences/files; never reads the configured user album. */
public final class DeviceTests extends Instrumentation {
    private int assertions;
    private void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        try {
            runSyntheticTests();
            result.putString("stream", "PASS: " + assertions + " Android storage/cache/lifecycle assertions\n");
        } catch (Exception | AssertionError e) {
            // No user data or exception messages are included in instrumentation output.
            result.putString("stream", "FAIL: synthetic Android tests (" + e.getClass().getSimpleName() + ")\n");
            StringBuilder locations = new StringBuilder();
            for (StackTraceElement frame : e.getStackTrace()) {
                if (frame.getClassName().startsWith("io.github.martinwoodward.iphotoframesaver")) {
                    locations.append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(' ');
                }
            }
            result.putString("location", locations.toString());
            code = 1;
        }
        finish(code, result);
    }
    private void runSyntheticTests() throws Exception {
        Context real = getTargetContext();
        android.content.ContentResolver resolver = real.getContentResolver();
        DreamStateProvider.setDreaming(real, false);
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        android.database.ContentObserver observer = new android.database.ContentObserver(null) {
            @Override public void onChange(boolean selfChange) { changes.incrementAndGet(); }
        };
        resolver.registerContentObserver(DreamStateProvider.URI, false, observer);
        try (android.database.Cursor state = resolver.query(DreamStateProvider.URI, null, null, null, null)) {
            check(state != null && state.moveToFirst() && state.getColumnCount() == 1
                    && state.getInt(state.getColumnIndexOrThrow("active")) == 0, "Provider exposes only inactive state");
        }
        DreamStateProvider.setDreaming(real, true);
        try (android.database.Cursor state = resolver.query(DreamStateProvider.URI,
                new String[] {"active"}, null, null, null)) {
            check(state != null && state.moveToFirst() && state.getInt(0) == 1, "Provider exposes active dream");
        } finally { DreamStateProvider.setDreaming(real, false); }
        long notificationDeadline = System.currentTimeMillis() + 2000;
        while (changes.get() < 2 && System.currentTimeMillis() < notificationDeadline) Thread.sleep(20);
        resolver.unregisterContentObserver(observer);
        check(changes.get() >= 2, "Companion observes both dream start and stop transitions");
        boolean readOnly = false;
        try { resolver.insert(DreamStateProvider.URI, new android.content.ContentValues()); }
        catch (UnsupportedOperationException e) { readOnly = true; }
        check(readOnly, "External callers cannot change dream state");
        boolean privateFieldsRejected = false;
        try (android.database.Cursor ignored = resolver.query(DreamStateProvider.URI,
                new String[] {"album"}, null, null, null)) { }
        catch (IllegalArgumentException e) { privateFieldsRejected = true; }
        check(privateFieldsRejected, "Provider cannot expose album information");
        File directory = new File(real.getFilesDir(), "synthetic-device-tests");
        if (directory.exists()) resultCleanup(directory);
        check(directory.isDirectory() || directory.mkdirs(), "Synthetic directory");
        Context isolated = new ContextWrapper(real) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("synthetic-device-tests-" + name, mode);
            }
        };
        isolated.getSharedPreferences("frame", 0).edit().clear().commit();
        Repository repo = new Repository(isolated);
        repo.settings.save("https://photos.icloud.com/shared/album/SyntheticDeviceAlbum123/", 30, "manual");
        check(repo.settings.interval() == 30 && "manual".equals(repo.settings.refresh()), "Settings persist");
        check(repo.load().photos.isEmpty(), "New album starts empty");
        String key = repo.key();
        JSONArray photos = new JSONArray();
        List<String> ids = new ArrayList<>();
        File stream = new File(new File(directory, "stream"), key);
        check(stream.isDirectory() || stream.mkdirs(), "Cache directory");
        for (int i = 0; i < 8; i++) {
            Photo p = new Photo("synthetic-photo-" + i, "https://example.icloud-content.com/synthetic", "", 1, 64, 64);
            photos.put(p.json());
            ids.add(p.id);
            File file = new File(stream, Repository.filename(p));
            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            try (FileOutputStream out = new FileOutputStream(file)) {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out), "Synthetic JPEG");
            }
            bitmap.recycle();
        }
        File metadata = new File(directory, "album-" + key + ".json");
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write(new JSONObject().put("refreshed", System.currentTimeMillis())
                    .put("photos", photos).toString().getBytes(StandardCharsets.UTF_8));
        }
        // A new repository instance simulates process recreation and uses only persisted state.
        Repository restored = new Repository(isolated);
        Repository.Snapshot snapshot = restored.load();
        check(snapshot.photos.size() == 8, "Metadata survives restart");
        check(restored.refresh(false).photos.size() == 8, "Manual mode uses saved metadata without network");
        check(restored.playableIds(snapshot).size() == 8, "Cached photos available with network disabled");
        Repository.Lease pinned = restored.acquire(snapshot, snapshot.photos.get(7), false);
        restored.prefetch(snapshot, ids.get(0), ids.subList(1, 6));
        long deadline = System.currentTimeMillis() + 5000;
        while (stream.listFiles().length > 7 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        check(pinned != null && pinned.file.isFile(), "Pruning cannot remove an in-use file");
        pinned.close();
        restored.prefetch(snapshot, ids.get(0), ids.subList(1, 6));
        while (stream.listFiles().length > 6 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        check(stream.listFiles().length == 6, "Current plus exactly five prefetched photos");
        check(restored.file(snapshot, snapshot.photos.get(0), false) != null, "Current photo retained");
        check(restored.playableIds(snapshot).size() == 6, "Six saved photos retained offline");
        restored.settings.prefs.edit().putString("storage", "internal").commit();
        File offline = new File(new File(directory, "offline"), key);
        check(offline.isDirectory() || offline.mkdirs(), "Offline directory");
        Photo p = snapshot.photos.get(7);
        File destination = new File(offline, Repository.filename(p));
        try (FileOutputStream out = new FileOutputStream(destination)) { out.write(new byte[] {1, 2, 3}); }
        check(restored.file(snapshot, p, false).equals(destination), "Offline copy takes precedence");
        restored.health("test", 2, 640, 480, false);
        java.io.ByteArrayOutputStream healthBytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(new File(directory, "health.json"))) {
            Net.transfer(in, healthBytes, 10000);
        }
        String health = new String(healthBytes.toByteArray(), StandardCharsets.UTF_8);
        check(!health.contains("icloud") && !health.contains("synthetic-photo"), "Health contains no album identifiers");
        check((real.getPackageManager().getApplicationInfo(real.getPackageName(), 0).flags
                & android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) == 0, "Android backup disabled");
        android.net.Uri drive = android.provider.DocumentsContract.buildTreeDocumentUri(
                "io.github.martinwoodward.iphotoframesaver.tests.documents", "root");
        String syntheticAlbum = "synthetic-album-" + System.nanoTime();
        AttachedStorage attached = new AttachedStorage(real.getContentResolver(), drive.toString(), syntheticAlbum);
        check(!attached.contains("synthetic.photo"), "New SAF drive starts empty");
        File source = new File(stream, Repository.filename(snapshot.photos.get(0)));
        attached.save("synthetic.photo", source);
        check(attached.contains("synthetic.photo"), "SAF file saved");
        File copied = new File(directory, "saf-copy.photo");
        check(attached.copyTo("synthetic.photo", copied), "SAF photo readable");
        check(copied.length() == source.length(), "SAF transfer complete");
        AttachedStorage reconnected = new AttachedStorage(real.getContentResolver(), drive.toString(), syntheticAlbum);
        check(reconnected.contains("synthetic.photo"), "SAF index survives recreation");
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write(new JSONObject().put("refreshed", System.currentTimeMillis())
                    .put("photos", new JSONArray().put(snapshot.photos.get(0).json())
                            .put(snapshot.photos.get(1).json()).put(snapshot.photos.get(2).json()))
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        Repository downloader = new Repository(isolated);
        downloader.downloadAlbum(false);
        check(downloader.downloadStatus.startsWith("Offline album ready."), "Full local offline download completes");
        for (int i = 0; i < 3; i++) {
            check(new File(offline, Repository.filename(snapshot.photos.get(i))).isFile(), "Full offline copy retained");
        }
        downloader.settings.prefs.edit().putString("storage", "attached").putString("tree", drive.toString()).commit();
        downloader.downloadAlbum(false);
        check(downloader.downloadStatus.startsWith("Offline album ready."), "Full SAF offline download completes");
        AttachedStorage fullDrive = new AttachedStorage(real.getContentResolver(), drive.toString(), key);
        for (int i = 0; i < 3; i++) {
            check(fullDrive.contains(Repository.filename(snapshot.photos.get(i))), "Full SAF copy retained");
        }
        for (int i = 0; i < 3; i++) {
            String name = Repository.filename(snapshot.photos.get(i));
            check(new File(offline, name).delete(), "Remove synthetic local copy");
            check(new File(stream, name).delete(), "Remove synthetic stream copy");
        }
        int beforeEnumeration = stream.listFiles().length;
        check(downloader.playableIds(downloader.current).size() == 3, "SAF photos discovered without copying");
        check(stream.listFiles().length == beforeEnumeration, "SAF enumeration does not bulk-fill internal cache");
        check(downloader.file(downloader.current, snapshot.photos.get(0), false) != null, "Selected SAF photo copied on demand");
        Photo corrupt = new Photo("synthetic-corrupt", "https://example.icloud-content.com/synthetic", "", 1, 64, 64);
        File corruptBytes = new File(directory, "corrupt.photo");
        try (FileOutputStream out = new FileOutputStream(corruptBytes)) { out.write(new byte[] {1, 2, 3}); }
        fullDrive.save(Repository.filename(corrupt), corruptBytes);
        Repository corruptReader = new Repository(isolated);
        check(corruptReader.file(downloader.current, corrupt, false) == null, "Corrupt attached photo is not made playable");
        AttachedStorage afterCorruption = new AttachedStorage(real.getContentResolver(), drive.toString(), key);
        check(!afterCorruption.contains(Repository.filename(corrupt)), "Corrupt attached photo can be downloaded again");
        downloader.settings.prefs.edit().putString("storage", "internal").remove("tree").commit();
        // One cached photo isolates lifecycle checks from all network access.
        try (FileOutputStream out = new FileOutputStream(metadata)) {
            out.write(new JSONObject().put("refreshed", System.currentTimeMillis())
                    .put("photos", new JSONArray().put(snapshot.photos.get(0).json()))
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        Repository playbackRepo = new Repository(isolated);
        check(!playbackRepo.settings.clock(), "Clock is optional and disabled by default");
        ClockOverlay[] clock = new ClockOverlay[1];
        runOnMainSync(() -> {
            clock[0] = new ClockOverlay(real, playbackRepo.settings);
            clock[0].start();
        });
        check(clock[0].getVisibility() == android.view.View.GONE, "Disabled clock stays hidden");
        runOnMainSync(() -> {
            playbackRepo.settings.prefs.edit().putBoolean("clock", true).commit();
            clock[0].update();
        });
        check(clock[0].getVisibility() == android.view.View.VISIBLE, "Clock toggle takes effect live");
        java.util.Date instant = new java.util.Date();
        String expectedTime = android.text.format.DateFormat.getTimeFormat(real).format(instant);
        String previousMinute = android.text.format.DateFormat.getTimeFormat(real)
                .format(new java.util.Date(instant.getTime() - 1000));
        check(clock[0].timeText().equals(expectedTime) || clock[0].timeText().equals(previousMinute),
                "Clock uses the system time/12-hour/24-hour formatter");
        String systemDateSetting = android.provider.Settings.System.getString(
                resolver, android.provider.Settings.System.DATE_FORMAT);
        check(clock[0].dateText().equals(ClockOverlay.dateFormatter(real, systemDateSetting).format(instant)),
                "Clock uses the system date formatter");
        check(ClockOverlay.dateFormatter(real, "yyyy-MM-dd").format(instant)
                        .equals(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(instant)),
                "Legacy system date pattern is honored");
        check(ClockOverlay.dateFormatter(real, null).format(instant)
                        .equals(android.text.format.DateFormat.getDateFormat(real).format(instant)),
                "Absent date setting uses locale format");
        check(ClockOverlay.dateFormatter(real, "'unterminated").format(instant)
                        .equals(android.text.format.DateFormat.getDateFormat(real).format(instant)),
                "Invalid system date pattern falls back safely");
        check(new Settings(isolated).clock(), "Clock preference persists");
        runOnMainSync(() -> {
            clock[0].stop();
            clock[0].update();
        });
        check(clock[0].getVisibility() == android.view.View.GONE, "Stopped clock cannot reappear from stale update");
        runOnMainSync(() -> {
            clock[0].start();
            playbackRepo.settings.prefs.edit().putBoolean("clock", false).commit();
            clock[0].update();
        });
        check(clock[0].getVisibility() == android.view.View.GONE, "Clock can be disabled while running");
        runOnMainSync(() -> {
            clock[0].stop();
            playbackRepo.settings.prefs.edit().putBoolean("clock", true).commit();
        });
        SlideshowController[] controller = new SlideshowController[1];
        runOnMainSync(() -> {
            controller[0] = new SlideshowController(real, "synthetic-lifecycle", playbackRepo);
            controller[0].start();
        });
        check(waitForRender(directory, 1), "Activity/dream controller renders cached photo");
        runOnMainSync(() -> controller[0].stop());
        JSONObject stopped = readHealth(directory);
        check(!stopped.getBoolean("active"), "Stop cancels playback");
        Thread.sleep(250);
        check(readHealth(directory).getInt("renders") == stopped.getInt("renders"), "No render after stop");
        runOnMainSync(() -> controller[0].start());
        check(waitForRender(directory, 2), "Restart resumes playback");
        for (int render = 3; render <= 7; render++) {
            runOnMainSync(() -> controller[0].next());
            check(waitForRender(directory, render), "Rapid next renders");
        }
        Thread.sleep(1800);
        boolean[] visible = new boolean[1];
        runOnMainSync(() -> {
            for (int i = 0; i < controller[0].view().getChildCount(); i++) {
                android.view.View child = controller[0].view().getChildAt(i);
                if (!(child instanceof android.widget.ImageView)) continue;
                android.widget.ImageView image = (android.widget.ImageView) child;
                if (image.getDrawable() != null && image.getAlpha() > 0) visible[0] = true;
            }
        });
        check(visible[0], "Interrupted cross-fades do not clear the newest photo");
        check(controller[0].view().getChildAt(controller[0].view().getChildCount() - 1) instanceof ClockOverlay,
                "Clock remains above photos after rapid cross-fades");
        runOnMainSync(() -> { controller[0].stop(); controller[0].start(); controller[0].close(); });
        Thread.sleep(250);
        check(!readHealth(directory).getBoolean("active"), "Rapid lifecycle change rejects stale decode");
        repo.background.shutdownNow();
        restored.background.shutdownNow();
        playbackRepo.background.shutdownNow();
        downloader.background.shutdownNow();
        corruptReader.background.shutdownNow();
        resultCleanup(directory);
        isolated.getSharedPreferences("frame", 0).edit().clear().commit();
    }
    private void resultCleanup(File path) throws java.io.IOException {
        File[] children = path.listFiles();
        if (children != null) for (File child : children) resultCleanup(child);
        if (!path.delete()) throw new java.io.IOException("Synthetic cleanup failed");
    }
    private JSONObject readHealth(File directory) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(new File(directory, "health.json"))) {
            Net.transfer(in, bytes, 10000);
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
    private boolean waitForRender(File directory, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            File file = new File(directory, "health.json");
            if (file.exists() && readHealth(directory).optInt("renders") >= count) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
