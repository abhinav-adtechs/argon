package com.argon.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Weather now, the next hours, and today's sunrise and sunset for one place, from Open-Meteo (no key), cached so the
// screen opens with numbers. The sunset turns the home screen dark. The place comes from the web remote as
// "name|latitude|longitude"; empty means Bengaluru. Only fetched while the home screen or the screensaver shows.
final class Weather {
    private static final String TAG = "Weather";
    static final String DEFAULT_PLACE = "Bengaluru|12.97|77.59";
    private static final long EVERY = 30 * 60_000L, RETRY = 5 * 60_000L;

    interface Listener { void onWeather(); }

    static final class Data {
        String place = "";
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // The next hours (from the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static Weather instance;

    static synchronized Weather get(Context c) {
        if (instance == null) instance = new Weather(c.getApplicationContext());
        return instance;
    }

    volatile Data data = new Data();
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile String setting = DEFAULT_PLACE;
    private volatile long at, failedAt;
    private volatile boolean fetching;

    private Weather(Context c) {
        cache = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        try {
            String body = cache.getString("body", null);
            setting = cache.getString("place", DEFAULT_PLACE);
            if (body != null) data = parse(body, name(setting));
            at = cache.getLong("at", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** "name|latitude|longitude" from the remote; anything malformed (or empty) means Bengaluru. */
    void setPlace(String value) {
        String next = valid(value) ? value : DEFAULT_PLACE;
        if (next.equals(setting)) return;
        setting = next;
        at = 0;
        failedAt = 0;
        refreshIfStale();
    }

    private static boolean valid(String s) {
        String[] parts = s == null ? new String[0] : s.split("\\|");
        if (parts.length != 3) return false;
        try {
            Double.parseDouble(parts[1]);
            Double.parseDouble(parts[2]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String name(String s) {
        return s.split("\\|")[0].trim();
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (fetching || (now - at < EVERY && now >= at) || (failedAt != 0 && now - failedAt < RETRY && now >= failedAt)) return;
        fetching = true;
        String place = setting;
        io.execute(() -> {
            try {
                String[] p = place.split("\\|");
                String body = get(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2", p[1].trim(), p[2].trim()));
                data = parse(body, name(place));
                at = System.currentTimeMillis();
                failedAt = 0;
                cache.edit().putString("body", body).putString("place", place).putLong("at", at).apply();
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onWeather(); });
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                Log.w(TAG, "can't load: " + e);
            } finally {
                fetching = false;
            }
        });
    }

    /** From sunset to sunrise at the place (it moves ~1 min a day, so yesterday's times do); without data, 6:00 and 18:00. */
    boolean isNight(long now) {
        long t = timeOfDay(now, 0);
        long rise = timeOfDay(data.sunrise, 6 * 3600_000L);
        long set = timeOfDay(data.sunset, 18 * 3600_000L);
        return t < rise || t >= set;
    }

    private static long timeOfDay(long at, long fallback) {
        if (at == 0) return fallback;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60 + c.get(Calendar.SECOND)) * 1000L;
    }

    private static Data parse(String body, String place) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Data w = new Data();
        w.place = place;
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long t = localTime(times.getString(i), zone);
                if (t <= now) continue;
                w.hours.add(t);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static String degrees(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v) + "°";
    }

    static String hour(long at) {
        return new SimpleDateFormat(Ui.h24 ? "H:00" : "h a", Locale.US).format(new Date(at));
    }

    // WMO weather interpretation codes.
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return "Clear";
        if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
        if (code == 2) return "Partly Cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorms";
        return "Rain";
    }
}
