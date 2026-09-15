package org.hifuture.weather;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class WeatherActivity extends Activity {
    private EditText label, lat, lon;
    private Switch enabled;
    private TextView status, forecast;
    private Button refresh;

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, 12, 0, 12); parent.addView(view); return view;
    }
    private EditText field(LinearLayout parent, String hint, String value, boolean numeric) {
        EditText view = new EditText(this); view.setHint(hint); view.setText(value); view.setSingleLine(true);
        if (numeric) view.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        parent.addView(view); return view;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = WeatherStore.prefs(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding); scroll.addView(layout); setContentView(scroll);
        text(layout, "Private Watch Weather", 26);
        text(layout, "Choose a fixed location. Coordinates are rounded to 0.1° before saving or sending (roughly city scale). No GPS, health data, account, or API key is used. Your location label stays on your phone and watch.", 16);
        text(layout, "When enabled, Open-Meteo receives your IP and rounded coordinates. Its policy allows request logs for 90 days. Automatic updates run about hourly; Android may delay them to save battery.", 16);
        label = field(layout, "Location label (1-32 characters)", p.getString("label", ""), false);
        lat = field(layout, "Latitude (-90 to 90)", p.getString("lat", ""), true);
        lon = field(layout, "Longitude (-180 to 180)", p.getString("lon", ""), true);
        enabled = new Switch(this); enabled.setText("Enable hourly weather"); enabled.setChecked(p.getBoolean("enabled", false)); layout.addView(enabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                WeatherStore.prefs(this).edit().putBoolean("enabled", false).commit();
                WeatherStore.schedule(this);
                // Disable runs off the UI thread if a fetch is currently completing.
                refresh.setEnabled(false);
                new Thread(() -> { WeatherStore.disable(this); runOnUiThread(() -> {
                    refresh.setEnabled(true); status.setText("Weather is off. Phone caches cleared. The watch may retain its last display until it expires."); showForecast();
                }); }).start();
            }
        });
        refresh = new Button(this); refresh.setText("Save and refresh"); layout.addView(refresh);
        refresh.setOnClickListener(v -> saveAndRefresh());
        status = text(layout, p.getBoolean("enabled", false) ? "Weather enabled." : "Weather is off. No requests are made.", 16);
        forecast = text(layout, "", 18);
        TextView source = text(layout, "Weather data: Open-Meteo · CC BY 4.0\nhttps://open-meteo.com/\nPrivacy: https://open-meteo.com/en/terms\nFree API for non-commercial use.\nAir quality and moon data are unavailable; watch firmware may display protocol defaults.", 14);
        android.text.util.Linkify.addLinks(source, android.text.util.Linkify.WEB_URLS);
        WeatherStore.schedule(this); showForecast();
    }
    private void saveAndRefresh() {
        try {
            String name = label.getText().toString().trim();
            if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("Use a location label of 1-32 characters.");
            double latitude = WeatherSnapshot.coordinate(lat.getText().toString(), 90);
            double longitude = WeatherSnapshot.coordinate(lon.getText().toString(), 180);
            SharedPreferences p = WeatherStore.prefs(this);
            boolean changed = !Double.toString(latitude).equals(p.getString("lat", "")) || !Double.toString(longitude).equals(p.getString("lon", "")) || !name.equals(p.getString("label", ""));
            if (changed) p.edit().remove("snapshot").commit();
            p.edit().putString("label", name).putString("lat", Double.toString(latitude)).putString("lon", Double.toString(longitude))
                .putBoolean("enabled", enabled.isChecked()).commit();
            lat.setText(Double.toString(latitude)); lon.setText(Double.toString(longitude));
            refresh.setEnabled(false); status.setText(enabled.isChecked() ? "Fetching weather…" : "Saving location…");
            new Thread(() -> {
                String message;
                try {
                    if (changed) { try { WeatherStore.send(this, null, true); } catch (Exception ignored) { } }
                    WeatherStore.schedule(this); message = WeatherStore.refresh(this, true);
                } catch (Exception e) { message = "Could not update weather. Check your connection and that both apps use the same signing key."; }
                final String result = message;
                runOnUiThread(() -> { refresh.setEnabled(true); status.setText(result); showForecast(); });
            }, "weather-manual-refresh").start();
        } catch (Exception e) { status.setText(e.getMessage()); }
    }
    private void showForecast() {
        try {
            JSONObject s = WeatherSnapshot.parse(WeatherStore.prefs(this).getString("snapshot", ""), System.currentTimeMillis());
            StringBuilder lines = new StringBuilder(s.getString("label"));
            lines.append(String.format(Locale.US, "\n%.0f°C · %s · %d%% humidity\nUpdated %s\n", s.getDouble("temperature"), WeatherSnapshot.description(s.getInt("code")), s.getInt("humidity"), DateFormat.getDateTimeInstance().format(new Date(s.getLong("fetched")))));
            JSONArray days = s.getJSONArray("days");
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.getJSONObject(i);
                lines.append(String.format(Locale.US, "\n%s: %.0f–%.0f°C · %s", DateFormat.getDateInstance().format(new Date(d.getLong("time") * 1000)), d.getDouble("low"), d.getDouble("high"), WeatherSnapshot.description(d.getInt("code"))));
            }
            forecast.setText(lines);
        } catch (Exception ignored) { forecast.setText("No fresh weather cached. Updates older than three hours are not sent to the watch."); }
    }
}
