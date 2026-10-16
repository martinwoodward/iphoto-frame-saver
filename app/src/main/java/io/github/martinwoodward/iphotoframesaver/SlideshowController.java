package io.github.martinwoodward.iphotoframesaver;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import io.github.martinwoodward.iphotoframesaver.core.Photo;
import io.github.martinwoodward.iphotoframesaver.core.PlaybackPolicy;
import io.github.martinwoodward.iphotoframesaver.core.ShuffleDeck;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SlideshowController {
    private final Repository repo;
    private final FrameLayout root;
    private final ImageView[] views = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final TextView message;
    private final ClockOverlay clock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String surface;
    private volatile int generation;
    private volatile boolean active;
    private boolean busy;
    private int front;
    private int renders;
    private int decodedWidth;
    private int decodedHeight;
    private ShuffleDeck deck;
    private List<String> deckIds = new ArrayList<>();
    private String deckKey = "";
    private String lastDisplayed;
    private final Runnable tick = this::next;

    SlideshowController(Context context, String surface) {
        this(context, surface, FrameApp.repo(context));
    }

    SlideshowController(Context context, String surface, Repository repository) {
        this.surface = surface;
        repo = repository;
        lastDisplayed = repo.settings.prefs.getString("last_displayed", "");
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            views[i] = new ImageView(context);
            views[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            views[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(views[i], new FrameLayout.LayoutParams(-1, -1));
        }
        message = new TextView(context);
        message.setTextColor(0xffd2e8e4);
        message.setTextSize(22);
        message.setGravity(Gravity.CENTER);
        message.setPadding(48, 32, 48, 32);
        message.setText("Preparing your photos...");
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        clock = new ClockOverlay(context, repo.settings);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        int margin = Math.round(32 * root.getResources().getDisplayMetrics().density);
        clockParams.setMarginStart(margin);
        clockParams.bottomMargin = margin;
        root.addView(clock, clockParams);
    }

    FrameLayout view() { return root; }

    void start() {
        if (active) return;
        active = true;
        generation++;
        busy = false;
        clock.start();
        repo.health(surface, renders, decodedWidth, decodedHeight, true);
        next();
    }

    void stop() {
        active = false;
        generation++;
        busy = false;
        handler.removeCallbacksAndMessages(null);
        repo.stopPrefetch();
        clock.stop();
        for (int i = 0; i < 2; i++) {
            views[i].animate().setListener(null);
            views[i].animate().cancel();
            views[i].setImageDrawable(null);
            if (bitmaps[i] != null) bitmaps[i].recycle();
            bitmaps[i] = null;
        }
        repo.health(surface, renders, decodedWidth, decodedHeight, false);
    }

    void close() { stop(); worker.shutdownNow(); }

    void next() {
        if (!active || busy) return;
        handler.removeCallbacks(tick);
        busy = true;
        final int token = generation;
        final int width = Math.max(1, root.getWidth() > 0 ? root.getWidth()
                : root.getResources().getDisplayMetrics().widthPixels);
        final int height = Math.max(1, root.getHeight() > 0 ? root.getHeight()
                : root.getResources().getDisplayMetrics().heightPixels);
        worker.execute(() -> {
            Bitmap bitmap = null;
            String error = null;
            String selected = null;
            Repository.Snapshot snapshot = repo.load();
            try {
                try { snapshot = repo.refresh(false); }
                catch (FrameException e) { error = e.getMessage(); }
                if (!active || token != generation) return;
                if (repo.settings.album().isEmpty()) {
                    throw new FrameException("Open Photo Frame Saver to add a public shared album.");
                }
                List<String> ids = new ArrayList<>();
                for (Photo p : snapshot.photos) ids.add(p.id);
                if (!ids.equals(deckIds) || !deckKey.equals(snapshot.key)) {
                    deckIds = ids;
                    deckKey = snapshot.key;
                    deck = new ShuffleDeck(ids, new Random(), lastDisplayed);
                }
                if (ids.isEmpty()) throw new FrameException(repo.status);
                String id = deck.next();
                if (ids.size() > 1 && id.equals(lastDisplayed)) id = deck.next();
                Photo photo = snapshot.find(id);
                if (photo != null) {
                    try (Repository.Lease source = repo.acquire(snapshot, photo, true)) {
                        bitmap = decode(source == null ? null : source.file, photo, width, height);
                        selected = id;
                    }
                    catch (FrameException e) { error = e.getMessage(); }
                }
                if (bitmap == null) {
                    List<String> saved = repo.playableIds(snapshot);
                    ShuffleDeck available = new ShuffleDeck(saved, new Random(), lastDisplayed);
                    for (int i = 0; i < saved.size() && bitmap == null; i++) {
                        String candidate = available.next();
                        Photo p = snapshot.find(candidate);
                        if (p == null) continue;
                        try (Repository.Lease source = repo.acquire(snapshot, p, false)) {
                            bitmap = decode(source == null ? null : source.file, p, width, height);
                            selected = candidate;
                        }
                        catch (FrameException e) { error = e.getMessage(); }
                    }
                }
                if (bitmap == null) throw new FrameException(error == null ? FrameException.DECODE : error);
                if (active && token == generation) {
                    repo.prefetch(snapshot, selected, deck.peek(PlaybackPolicy.PREFETCH));
                }
            } catch (FrameException e) {
                error = e.getMessage();
            } catch (OutOfMemoryError e) {
                error = "Not enough memory for this photo. Trying another photo shortly.";
            } finally {
                final Bitmap result = bitmap;
                final String safeError = error;
                final String displayedId = selected;
                handler.post(() -> {
                    if (!active || token != generation) {
                        if (result != null) result.recycle();
                        return;
                    }
                    busy = false;
                    if (result != null) {
                        lastDisplayed = displayedId;
                        repo.settings.prefs.edit().putString("last_displayed", displayedId).apply();
                        show(result);
                    } else if (bitmaps[front] == null) {
                        message.setText(safeError == null ? "Waiting for saved photos..." : safeError);
                        message.setVisibility(View.VISIBLE);
                    }
                    handler.postDelayed(tick, result == null ? 10_000L : repo.settings.interval() * 1000L);
                });
            }
        });
    }

    private void show(Bitmap bitmap) {
        for (ImageView view : views) {
            view.animate().setListener(null);
            view.animate().cancel();
        }
        int old = front;
        front = 1 - front;
        ImageView incoming = views[front];
        ImageView outgoing = views[old];
        incoming.animate().setListener(null);
        incoming.animate().cancel();
        incoming.setImageDrawable(null);
        if (bitmaps[front] != null) bitmaps[front].recycle();
        bitmaps[front] = bitmap;
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        clock.bringToFront();
        message.setVisibility(View.GONE);
        final int previous = old;
        incoming.animate().alpha(1f).setDuration(renders == 0 ? 450 : 1600)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animator) {
                        outgoing.setImageDrawable(null);
                        if (bitmaps[previous] != null) bitmaps[previous].recycle();
                        bitmaps[previous] = null;
                    }
                }).start();
        renders++;
        decodedWidth = bitmap.getWidth();
        decodedHeight = bitmap.getHeight();
        repo.health(surface, renders, decodedWidth, decodedHeight, true);
    }

    private Bitmap decode(File file, Photo photo, int width, int height) throws FrameException {
        if (file == null) throw new FrameException(FrameException.DECODE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new FrameException(FrameException.DECODE);
        options.inSampleSize = PlaybackPolicy.sampleSize(options.outWidth, options.outHeight, width, height);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) throw new FrameException(FrameException.DECODE);
        int orientation = photo.orientation;
        try {
            int exif = new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);
            if (exif >= 1 && exif <= 8) orientation = exif;
        } catch (IOException e) {
            // PNG and server-rendered images may not have EXIF; album metadata is the fallback.
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(270); break;
            default: return decoded;
        }
        Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (rotated != decoded) decoded.recycle();
        return rotated;
    }
}
