package org.hifuture.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** The only data allowed across the one-way weather bridge. Temperatures are Celsius. */
public final class WeatherSnapshot {
    public static final long MAX_AGE_MS = 3 * 60 * 60 * 1000L;
    public static final int MAX_BYTES = 32768;

    public static double coordinate(String value, double limit) {
        double n = Double.parseDouble(value.trim().replace(',', '.'));
        if (Double.isNaN(n) || Double.isInfinite(n) || Math.abs(n) > limit)
            throw new IllegalArgumentException("Invalid coordinate");
        return Math.round(n * 10.0) / 10.0;
    }

    public static String forecastUrl(double lat, double lon) throws Exception {
        // Revalidate and round at the network boundary, even for saved preferences.
        lat = coordinate(Double.toString(lat), 90);
        lon = coordinate(Double.toString(lon), 180);
        return String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f", lat, lon)
            + "&current=temperature_2m,relative_humidity_2m,weather_code"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max"
            + "&hourly=temperature_2m,weather_code&forecast_days=7&forecast_hours=24"
            + "&temperature_unit=celsius&timezone=auto&timeformat=unixtime";
    }

    public static JSONObject fromForecast(JSONObject api, String label, long fetched) throws Exception {
        JSONObject units = api.getJSONObject("current_units");
        if (!"°C".equals(units.getString("temperature_2m")) ||
            !"unixtime".equals(units.getString("time")))
            throw new IllegalArgumentException("Unexpected forecast units");
        JSONObject c = api.getJSONObject("current");
        JSONObject out = new JSONObject().put("version", 1).put("fetched", fetched)
            .put("label", label.trim()).put("time", c.getLong("time"))
            .put("temperature", c.getDouble("temperature_2m"))
            .put("humidity", c.getInt("relative_humidity_2m"))
            .put("code", c.getInt("weather_code"));
        JSONObject d = api.getJSONObject("daily");
        JSONArray days = new JSONArray();
        for (int i = 0; i < Math.min(7, d.getJSONArray("time").length()); i++) {
            days.put(new JSONObject().put("time", d.getJSONArray("time").getLong(i))
                .put("code", d.getJSONArray("weather_code").getInt(i))
                .put("high", d.getJSONArray("temperature_2m_max").getDouble(i))
                .put("low", d.getJSONArray("temperature_2m_min").getDouble(i))
                .put("sunrise", d.getJSONArray("sunrise").isNull(i) ? 0 : d.getJSONArray("sunrise").getLong(i))
                .put("sunset", d.getJSONArray("sunset").isNull(i) ? 0 : d.getJSONArray("sunset").getLong(i))
                .put("uv", d.getJSONArray("uv_index_max").isNull(i) ? 0 : d.getJSONArray("uv_index_max").getDouble(i)));
        }
        JSONArray hours = new JSONArray();
        JSONObject h = api.getJSONObject("hourly");
        for (int i = 0; i < h.getJSONArray("time").length() && hours.length() < 24; i++) {
            long time = h.getJSONArray("time").getLong(i);
            if (time + 3600 < c.getLong("time")) continue;
            hours.put(new JSONObject().put("time", time)
                .put("code", h.getJSONArray("weather_code").getInt(i))
                .put("temperature", h.getJSONArray("temperature_2m").getDouble(i)));
        }
        out.put("days", days).put("hours", hours);
        validate(out, fetched);
        return out;
    }

    public static JSONObject parse(String raw, long now) throws Exception {
        if (raw == null || raw.length() > MAX_BYTES) throw new IllegalArgumentException("Oversized weather");
        JSONObject out = new JSONObject(raw);
        validate(out, now);
        return out;
    }

    private static void range(double n, double min, double max) {
        if (Double.isNaN(n) || Double.isInfinite(n) || n < min || n > max)
            throw new IllegalArgumentException("Invalid weather value");
    }

    private static void timestamp(long t) { range(t, 1, Integer.MAX_VALUE); }

    public static void validate(JSONObject s, long now) throws Exception {
        if (s.getInt("version") != 1) throw new IllegalArgumentException("Unknown weather format");
        long age = now - s.getLong("fetched");
        if (age < -60000 || age > MAX_AGE_MS) throw new IllegalArgumentException("Expired weather");
        timestamp(s.getLong("time"));
        if (Math.abs(now / 1000 - s.getLong("time")) > MAX_AGE_MS / 1000)
            throw new IllegalArgumentException("Expired current conditions");
        if (s.getString("label").isEmpty() || s.getString("label").length() > 32)
            throw new IllegalArgumentException("Use a location label of 1-32 characters");
        range(s.getDouble("temperature"), -100, 70);
        range(s.getInt("humidity"), 0, 100);
        qweatherCode(s.getInt("code"));
        JSONArray days = s.getJSONArray("days"), hours = s.getJSONArray("hours");
        range(days.length(), 1, 7); range(hours.length(), 0, 24);
        long previous = 0;
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            long time = d.getLong("time"); timestamp(time);
            if (time <= previous) throw new IllegalArgumentException("Unordered forecast");
            if (i == 0 && (s.getLong("time") < time || s.getLong("time") >= time + 90000))
                throw new IllegalArgumentException("Forecast is for a different day");
            previous = time;
            range(d.getDouble("low"), -100, 70); range(d.getDouble("high"), d.getDouble("low"), 70);
            range(d.getDouble("uv"), 0, 30);
            range(d.getLong("sunrise"), 0, Integer.MAX_VALUE);
            range(d.getLong("sunset"), 0, Integer.MAX_VALUE);
            qweatherCode(d.getInt("code"));
        }
        previous = 0;
        for (int i = 0; i < hours.length(); i++) {
            JSONObject h = hours.getJSONObject(i);
            long time = h.getLong("time"); timestamp(time);
            if (time <= previous) throw new IllegalArgumentException("Unordered hourly forecast");
            previous = time;
            range(h.getDouble("temperature"), -100, 70); qweatherCode(h.getInt("code"));
        }
    }

    /** WMO -> existing QWeather converter -> correct RK/Nadal watch codes. */
    public static int qweatherCode(int wmo) {
        switch (wmo) {
            case 0: return 100;
            case 1: case 2: return 101;
            case 3: return 104;
            case 45: case 48: return 500;
            case 51: case 53: case 55: case 61: return 305;
            case 63: return 306;
            case 65: return 307;
            case 56: case 57: case 66: case 67: return 313;
            case 71: case 77: return 400;
            case 73: return 401;
            case 75: return 402;
            case 80: case 81: case 82: return 300;
            case 85: case 86: return 407;
            case 95: return 302;
            case 96: case 99: return 304;
            default: throw new IllegalArgumentException("Unknown WMO weather code");
        }
    }

    public static String description(int code) {
        switch (qweatherCode(code)) {
            case 100: return "Clear"; case 101: return "Partly cloudy"; case 104: return "Overcast";
            case 500: return "Fog"; case 305: return "Light rain"; case 306: return "Rain";
            case 307: return "Heavy rain"; case 313: return "Freezing rain";
            case 400: return "Light snow"; case 401: return "Snow"; case 402: return "Heavy snow";
            case 300: return "Rain showers"; case 407: return "Snow showers";
            case 302: return "Thunderstorm"; default: return "Thunderstorm with hail";
        }
    }
}
