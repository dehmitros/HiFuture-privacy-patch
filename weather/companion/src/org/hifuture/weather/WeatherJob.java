package org.hifuture.weather;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class WeatherJob extends JobService {
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            try { WeatherStore.refresh(this, false); } catch (Exception ignored) { }
            finally { if (!stopped) jobFinished(params, false); }
        }, "weather-refresh").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { stopped = true; return false; }
}
