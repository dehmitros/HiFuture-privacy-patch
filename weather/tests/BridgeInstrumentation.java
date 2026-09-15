package org.hifuture.weather.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Optional device test APK, signed with the same private key. Leaves weather disabled. */
public final class BridgeInstrumentation extends Instrumentation {
    private boolean configureNewYork;
    @Override public void onCreate(Bundle args) {
        configureNewYork = args != null && "true".equals(args.getString("configureNewYork"));
        super.onCreate(args); start();
    }
    private int deliver(Intent intent) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); int[] code = {99};
        getContext().sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { code[0] = getResultCode(); latch.countDown(); }
        }, null, Activity.RESULT_CANCELED, null, null);
        if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("Bridge timeout");
        return code[0];
    }
    private Intent intent() { return new Intent("org.hifuture.weather.UPDATE").setClassName("com.cs.ute.hiFuture", "org.hifuture.weather.PrivateWeatherReceiver"); }
    private void require(boolean ok) { if (!ok) throw new AssertionError("Bridge rejected valid weather or accepted invalid weather"); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long now = System.currentTimeMillis(), seconds = now / 1000, day = seconds - seconds % 86400;
            JSONObject sample = new JSONObject().put("version", 1).put("fetched", now).put("time", seconds)
                .put("label", "Weather bridge test").put("temperature", 23.4).put("humidity", 61).put("code", 2)
                .put("days", new JSONArray().put(new JSONObject().put("time", day).put("code", 2)
                    .put("high", 27).put("low", 18).put("sunrise", day + 21600).put("sunset", day + 64800).put("uv", 5)))
                .put("hours", new JSONArray().put(new JSONObject().put("time", seconds).put("code", 2).put("temperature", 23.4)));
            require(deliver(intent().putExtra("snapshot", sample.toString())) == Activity.RESULT_OK);
            require(deliver(intent().putExtra("snapshot", sample.put("humidity", 101).toString())) == Activity.RESULT_CANCELED);
            require(deliver(intent().putExtra("snapshot", sample.put("humidity", 61).put("fetched", now - 4 * 3600000L).toString())) == Activity.RESULT_CANCELED);
            require(deliver(intent().putExtra("clear", true)) == Activity.RESULT_OK);
            if (configureNewYork) {
                Context target = getTargetContext();
                target.getSharedPreferences("weather", 0).edit().putString("label", "New York")
                    .putString("lat", "40.7").putString("lon", "-74.0").putBoolean("enabled", true).remove("attempt").commit();
                Class<?> store = Class.forName("org.hifuture.weather.WeatherStore", true, target.getClassLoader());
                store.getMethod("schedule", Context.class).invoke(null, target);
                store.getMethod("refresh", Context.class, boolean.class).invoke(null, target, true);
                String live = target.getSharedPreferences("weather", 0).getString("snapshot", "");
                require(!live.isEmpty());
                require(deliver(intent().putExtra("snapshot", live)) == Activity.RESULT_OK);
            }
            result.putString("stream", "Passed device bridge: conversion, signed delivery, malformed and expired rejection, cache clearing."
                + (configureNewYork ? " New York configured; live Android forecast delivery passed." : "") + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            try { deliver(intent().putExtra("clear", true)); } catch (Exception ignored) { }
            result.putString("stream", "Bridge test failed: " + e + "\n"); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
