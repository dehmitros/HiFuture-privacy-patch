package org.hifuture.weather;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

public final class WeatherStore {
    private static final int JOB = 1701;
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("weather", 0); }
    public static void schedule(Context c) {
        JobScheduler scheduler = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!prefs(c).getBoolean("enabled", false)) { scheduler.cancel(JOB); return; }
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(c, WeatherJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(3600000L)
            .setPersisted(true).build());
    }
    public static void send(Context c, String snapshot, boolean clear) {
        // Explicit destination; sender also checks the recipient's signing certificate.
        if (c.getPackageManager().checkSignatures(c.getPackageName(), "com.cs.ute.hiFuture") != 0)
            throw new IllegalStateException("Install HiFuture Fit signed with the same local key first.");
        Intent intent = new Intent("org.hifuture.weather.UPDATE")
            .setClassName("com.cs.ute.hiFuture", "org.hifuture.weather.PrivateWeatherReceiver")
            .putExtra("clear", clear);
        if (!clear) intent.putExtra("snapshot", snapshot);
        c.sendBroadcast(intent);
    }
    public static synchronized void disable(Context c) {
        prefs(c).edit().putBoolean("enabled", false).remove("snapshot").remove("attempt").commit();
        schedule(c);
        try { send(c, null, true); } catch (Exception ignored) { }
    }
    public static synchronized String refresh(Context c, boolean manual) throws Exception {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("enabled", false)) return "Weather is off. No requests are made.";
        long now = System.currentTimeMillis(), attempt = p.getLong("attempt", 0);
        long wait = manual ? 60000L : 3600000L;
        if (now >= attempt && now - attempt < wait) {
            String cached = p.getString("snapshot", "");
            if (!cached.isEmpty()) { WeatherSnapshot.parse(cached, now); send(c, cached, false); }
            return "Refresh is limited to once per minute; automatic refresh is hourly.";
        }
        // No arbitrary URL, user identifier, city name, timezone name, or app data goes to the provider.
        String lat = p.getString("lat", ""), lon = p.getString("lon", ""), label = p.getString("label", "Weather");
        String url = WeatherSnapshot.forecastUrl(Double.parseDouble(lat), Double.parseDouble(lon));
        p.edit().putLong("attempt", now).commit();
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "PrivateWatchWeather/1.0");
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Weather service unavailable. Try later.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 131072) throw new IllegalStateException("Weather response too large.");
                    bytes.write(buffer, 0, count);
                }
            }
            String snapshot = WeatherSnapshot.fromForecast(new JSONObject(bytes.toString("UTF-8")), label, now).toString();
            if (!p.getBoolean("enabled", false) || !lat.equals(p.getString("lat", "")) ||
                !lon.equals(p.getString("lon", "")) || !label.equals(p.getString("label", "")))
                return "Weather settings changed. Discarded the previous request.";
            p.edit().putString("snapshot", snapshot).commit();
            send(c, snapshot, false);
            return "Weather sent to HiFuture Fit. Connect your watch to sync.";
        } finally { connection.disconnect(); }
    }
}
