package io.github.martinwoodward.iphotoframesaver;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import io.github.martinwoodward.iphotoframesaver.core.PlaybackPolicy;

public final class RefreshJobService extends JobService {
    private static final int JOB = 7301;
    private volatile boolean stopped;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        long interval = PlaybackPolicy.refreshMillis(new Settings(context).refresh());
        scheduler.cancel(JOB);
        if (interval == 0 || new Settings(context).album().isEmpty()) return;
        int result = scheduler.schedule(new JobInfo.Builder(JOB,
                new ComponentName(context, RefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(interval).build());
        if (result != JobScheduler.RESULT_SUCCESS) {
            FrameApp.repo(context).status = "Background refresh could not be scheduled; playback will still check the album.";
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        Repository repo = FrameApp.repo(this);
        repo.background.execute(() -> {
            boolean retry = false;
            try { repo.prepareStartupCache(repo.refresh(false)); }
            catch (FrameException e) { repo.status = e.getMessage(); retry = true; }
            if (!stopped) jobFinished(params, retry);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { stopped = true; return true; }
}
