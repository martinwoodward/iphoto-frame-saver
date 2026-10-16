package io.github.martinwoodward.iphotoframesaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OfflineService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler();
    private boolean running;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (!running) return;
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(1, notification(FrameApp.repo(OfflineService.this).downloadStatus));
            handler.postDelayed(this, 2000);
        }
    };

    private Notification notification(String status) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "offline") : new Notification.Builder(this);
        Intent cancel = new Intent(this, OfflineService.class).setAction("cancel");
        PendingIntent stop = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Saving offline photos").setContentText(status)
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Repository repo = FrameApp.repo(this);
        if (intent != null && "cancel".equals(intent.getAction())) {
            repo.cancelDownload = true;
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(
                    new NotificationChannel("offline", "Offline photo downloads", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, notification("Starting download..."));
        if (running) return START_NOT_STICKY;
        running = true;
        handler.postDelayed(progress, 1000);
        worker.execute(() -> {
            repo.downloadAlbum();
            handler.post(() -> {
                running = false;
                handler.removeCallbacksAndMessages(null);
                stopForeground(true);
                stopSelf();
            });
        });
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        FrameApp.repo(this).cancelDownload = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
