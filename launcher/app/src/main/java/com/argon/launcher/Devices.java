package com.argon.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.argon.launcher.smarthome.SmartHomeProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The room's lights and plugs as the home screen shows them: a copy of SmartHome's state (in the web remote's process),
 * read through SmartHomeProvider again whenever it announces a change, while the home screen is showing.
 */
final class Devices {
    interface Listener {
        void onDevices();

        void onDeviceError(String error);
    }

    static final class Device {
        String id, kind, name, icon, color, colorName;
        boolean on, online, colors, whites;
        int bri, rgb, timer;
        double watts, volts, amps, today, week, month;
        double[] days = new double[0];
        long since;

        boolean light() {
            return "light".equals(kind);
        }
    }

    static final class Scene {
        String id, name, sub;
        int[] colors;
    }

    String room = "Living room";
    boolean configured;
    int on;
    double watts;
    List<Device> list = new ArrayList<>();
    List<Scene> scenes = new ArrayList<>();

    private final ContentResolver resolver;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            fetch();
        }
    };
    private boolean watching;

    Devices(Context c, Listener listener) {
        resolver = c.getContentResolver();
        this.listener = listener;
    }

    void start() {
        if (watching) return;
        watching = true;
        resolver.registerContentObserver(SmartHomeProvider.STATE, false, observer);
        fetch();
    }

    void stop() {
        if (!watching) return;
        watching = false;
        resolver.unregisterContentObserver(observer);
    }

    Device find(String id) {
        for (Device d : list) if (d.id.equals(id)) return d;
        return null;
    }

    private void fetch() {
        io.execute(() -> {
            try {
                Bundle b = resolver.call(SmartHomeProvider.STATE, "state", null, null);
                JSONObject s = new JSONObject(b == null ? "{}" : b.getString("json", "{}"));
                handler.post(() -> apply(s));
            } catch (RuntimeException | JSONException e) {
                Log.w("Home", "smart home state: " + e);
            }
        });
    }

    private void apply(JSONObject s) {
        room = s.optString("room", "Living room");
        configured = s.optBoolean("configured");
        on = s.optInt("on");
        watts = s.optDouble("watts", 0);
        List<Device> ds = new ArrayList<>();
        JSONArray a = s.optJSONArray("devices");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            Device d = new Device();
            d.id = o.optString("id");
            d.kind = o.optString("kind");
            d.name = o.optString("name");
            d.icon = o.optString("icon");
            d.on = o.optBoolean("on");
            d.online = o.optBoolean("online");
            d.bri = o.optInt("bri", 100);
            d.rgb = color(o.optString("rgb"), 0xFFF2B04A);
            d.color = o.optString("color");
            d.colorName = o.optString("colorName");
            d.colors = o.optBoolean("colors");
            d.whites = o.optBoolean("whites");
            d.watts = o.optDouble("watts", 0);
            d.volts = o.optDouble("volts", 0);
            d.amps = o.optDouble("amps", 0);
            d.timer = o.optInt("timer");
            d.since = o.optLong("since");
            d.today = o.optDouble("today", 0);
            d.week = o.optDouble("week", 0);
            d.month = o.optDouble("month", 0);
            JSONArray days = o.optJSONArray("days");
            d.days = new double[days == null ? 0 : days.length()];
            for (int j = 0; j < d.days.length; j++) d.days[j] = days.optDouble(j, 0);
            ds.add(d);
        }
        List<Scene> sc = new ArrayList<>();
        JSONArray b = s.optJSONArray("scenes");
        for (int i = 0; b != null && i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            Scene x = new Scene();
            x.id = o.optString("id");
            x.name = o.optString("name");
            x.sub = o.optString("sub");
            JSONArray cs = o.optJSONArray("colors");
            x.colors = new int[cs == null ? 0 : cs.length()];
            for (int j = 0; j < x.colors.length; j++) x.colors[j] = color(cs.optString(j), 0);
            sc.add(x);
        }
        list = ds;
        scenes = sc;
        listener.onDevices();
    }

    private static int color(String hex, int fallback) {
        try {
            return hex == null || hex.isEmpty() ? fallback : Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // MARK: - Changes: shown at once, then confirmed by the state that comes back

    void toggle(Device d) {
        d.on = !d.on;
        recount();
        send("id", d.id, "on", d.on);
    }

    void allOff(boolean on) {
        for (Device d : list) d.on = on;
        recount();
        send("all", on);
    }

    void brightness(Device d, int bri) {
        d.bri = Math.max(1, Math.min(100, bri));
        d.on = true;
        recount();
        send("id", d.id, "bri", d.bri);
    }

    void color(Device d, String preset, int rgb) {
        d.color = preset;
        d.rgb = rgb;
        d.on = true;
        recount();
        send("id", d.id, "color", preset);
    }

    void timer(Device d, int seconds) {
        d.timer = seconds;
        send("id", d.id, "timer", seconds);
    }

    void scene(String id) {
        send("scene", id);
    }

    void dim() {
        send("dim", true);
    }

    private void recount() {
        int n = 0;
        for (Device d : list) if (d.on) n++;
        on = n;
        listener.onDevices();
    }

    private void send(Object... kv) {
        JSONObject m = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        } catch (JSONException e) {
            return;
        }
        io.execute(() -> {
            try {
                Bundle b = resolver.call(SmartHomeProvider.STATE, "do", m.toString(), null);
                String error = b == null ? null : b.getString("error");
                if (error != null) handler.post(() -> listener.onDeviceError(error));
            } catch (RuntimeException e) {
                Log.w("Home", "smart home: " + e);
            }
        });
    }
}
