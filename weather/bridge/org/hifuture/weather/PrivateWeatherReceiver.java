package org.hifuture.weather;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** No network code. Manifest requires the locally held signature permission. */
public final class PrivateWeatherReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.hifuture.weather.UPDATE";
    private static final String PREFS = "private_weather";

    @Override public void onReceive(Context context, Intent intent) {
        setResultCode(Activity.RESULT_CANCELED);
        if (!ACTION.equals(intent.getAction())) return;
        try {
            if (intent.getBooleanExtra("clear", false)) {
                context.getSharedPreferences(PREFS, 0).edit().clear().commit();
                Object dao = singleton("com.yc.gloryfitpro.dao.SPDao");
                call(dao, "setFutureWeatherInfo", create("com.yc.gloryfitpro.bean.FutureWeatherInfo"));
                call(dao, "setWeatherUpdateTime", 0L);
                notifyHome();
                setResultCode(Activity.RESULT_OK);
                return;
            }
            String raw = intent.getStringExtra("snapshot");
            WeatherSnapshot.parse(raw, System.currentTimeMillis());
            // Validate and convert completely before committing either cache.
            Object weather = convert(WeatherSnapshot.parse(raw, System.currentTimeMillis()));
            Object dao = singleton("com.yc.gloryfitpro.dao.SPDao");
            call(dao, "setFutureWeatherInfo", weather);
            call(dao, "setWeatherUpdateTime", new JSONObject(raw).getLong("fetched"));
            context.getSharedPreferences(PREFS, 0).edit().putString("snapshot", raw).commit();
            notifyHome();
            setResultCode(Activity.RESULT_OK);
            PendingResult pending = goAsync();
            new Thread(() -> { try { syncCached(context); } finally { pending.finish(); } }, "weather-to-watch").start();
        } catch (Exception ignored) {
            // Never log the snapshot, coordinates, health data, or vendor exception details.
        }
    }

    private static Object create(String name) throws Exception { return Class.forName(name).getConstructor().newInstance(); }
    private static Object singleton(String name) throws Exception { return Class.forName(name).getMethod("getInstance").invoke(null); }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) return m.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }
    private static int integer(JSONObject s, String name) throws Exception { return (int)Math.round(s.getDouble(name)); }

    private static Object convert(JSONObject s) throws Exception {
        Object converter = singleton("com.yc.gloryfitpro.utils.WeatherUtil");
        Method code = converter.getClass().getDeclaredMethod("changeWeatherCode", int.class);
        code.setAccessible(true);
        Object info = create("com.yc.gloryfitpro.bean.FutureWeatherInfo");
        Object current = create("com.yc.gloryfitpro.bean.NowWeather");
        call(current, "setCityName", s.getString("label"));
        call(current, "setPublishSource", "Open-Meteo (CC BY 4.0)");
        call(current, "setPublishTime", s.getInt("time"));
        call(current, "setCurrentTemperature", integer(s, "temperature"));
        call(current, "setHum", s.getInt("humidity"));
        call(current, "setUnit", call(singleton("com.yc.gloryfitpro.dao.SPDao"), "getTemperatureUnitStatus"));
        call(current, "setPhenomenon", code.invoke(converter, WeatherSnapshot.qweatherCode(s.getInt("code"))));
        call(current, "setPhenomenonDes", WeatherSnapshot.description(s.getInt("code")));
        // These measurements are not supplied by this forecast. Zero is the existing protocol's default.
        call(current, "setAqi", 0); call(current, "setPm25", 0);
        List<Object> days = new ArrayList<>(), hours = new ArrayList<>();
        JSONArray ds = s.getJSONArray("days"), hs = s.getJSONArray("hours");
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            Object day = create("com.yc.gloryfitpro.bean.DayWeather");
            call(day, "setTimestamp", d.getInt("time"));
            call(day, "setHighestTemperature", integer(d, "high"));
            call(day, "setLowestTemperature", integer(d, "low"));
            call(day, "setPhenomenon", code.invoke(converter, WeatherSnapshot.qweatherCode(d.getInt("code"))));
            call(day, "setPhenomenonDes", WeatherSnapshot.description(d.getInt("code")));
            call(day, "setSunriseTime", d.getInt("sunrise")); call(day, "setSunsetTime", d.getInt("sunset"));
            call(day, "setUv", integer(d, "uv"));
            days.add(day);
            if (i == 0) {
                call(current, "setHighest", integer(d, "high")); call(current, "setLowest", integer(d, "low"));
                call(current, "setUv", integer(d, "uv"));
            }
        }
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.getJSONObject(i);
            Object hour = create("com.yc.nadalsdk.bean.FutureWeatherConfig$HourWeather");
            call(hour, "setTimestamp", h.getInt("time")); call(hour, "setTemperature", integer(h, "temperature"));
            call(hour, "setPhenomenon", code.invoke(converter, WeatherSnapshot.qweatherCode(h.getInt("code"))));
            hours.add(hour);
        }
        call(info, "setNowWeather", current); call(info, "setDayWeatherList", days); call(info, "setHourWeatherList", hours);
        return info;
    }

    public static boolean isFresh() {
        try {
            Context context = (Context)Class.forName("com.yc.gloryfitpro.MyApplication").getMethod("getMyApp").invoke(null);
            WeatherSnapshot.parse(context.getSharedPreferences(PREFS, 0).getString("snapshot", ""), System.currentTimeMillis());
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static void syncCached(Context context) {
        try {
            JSONObject s = WeatherSnapshot.parse(context.getSharedPreferences(PREFS, 0).getString("snapshot", ""), System.currentTimeMillis());
            Object model = create("com.yc.gloryfitpro.model.main.MainHomeModelImpl");
            call(model, "mySetFutureWeatherToBle", convert(s));
        } catch (Exception ignored) { }
    }

    public static void refreshHome(Object fragment) {
        try {
            Object dao = singleton("com.yc.gloryfitpro.dao.SPDao");
            Object info = isFresh() ? call(dao, "getFutureWeatherInfo") : create("com.yc.gloryfitpro.bean.FutureWeatherInfo");
            Method m = fragment.getClass().getDeclaredMethod("updateWeatherUi", Class.forName("com.yc.gloryfitpro.bean.FutureWeatherInfo"));
            m.setAccessible(true); m.invoke(fragment, info);
        } catch (Exception ignored) { }
    }

    private static void notifyHome() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object bus = Class.forName("org.greenrobot.eventbus.EventBus").getMethod("getDefault").invoke(null);
                Object event = Class.forName("com.yc.gloryfitpro.bean.EventBus.EventBusWeather").getConstructor(int.class).newInstance(1);
                call(bus, "post", event);
            } catch (Exception ignored) { }
        });
    }

    public static void openCompanion(Activity activity) {
        try {
            activity.startActivity(new Intent().setClassName("org.hifuture.weather", "org.hifuture.weather.WeatherActivity"));
        } catch (Exception ignored) {
            Toast.makeText(activity, "Install the Private Watch Weather companion to enable weather.", Toast.LENGTH_LONG).show();
        }
        activity.finish();
    }
}
