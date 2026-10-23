package com.argon.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// One team's live, just-finished or next match, from ESPN's public site API (no key). The team
// schedules cover every competition, but their live clock lags a couple of minutes behind, so a
// match that has kicked off is read from its own summary instead.
final class Football {
    static final int NEXT = 0, LIVE = 1, FULL_TIME = 2;
    private static final String TAG = "Football";
    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final String LOGO = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500-dark/%s.png&w=%d&h=%d";
    // A result stays up this long after kick-off before the card moves on to the next match.
    private static final long RESULT_MS = 4 * 60 * 60_000L;
    private static final long LIVE_MS = 30_000, RESULT_REFRESH_MS = 10 * 60_000L, IDLE_MS = 30 * 60_000L,
            RETRY_MS = 2 * 60_000L;

    static final class Side {
        String id, name, abbr, score;
        Bitmap logo;
    }

    static final class Match {
        int state;
        long kickoff;
        String competition, detail;
        final Side home = new Side(), away = new Side();
    }

    private final String team;
    private final int logoPx;
    private final Consumer<Match> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Runnable refresh = this::refresh;
    private boolean running, loading;
    private long nextAt;

    Football(String team, int logoPx, Consumer<Match> listener) {
        this.team = team;
        this.logoPx = logoPx;
        this.listener = listener;
    }

    void start() {
        running = true;
        main.removeCallbacks(refresh);
        if (!loading) main.postDelayed(refresh, Math.max(0, nextAt - SystemClock.elapsedRealtime()));
    }

    void stop() {
        running = false;
        main.removeCallbacks(refresh);
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    private void refresh() {
        loading = true;
        worker.execute(() -> {
            Match m = null;
            boolean ok = false;
            try {
                m = load();
                ok = true;
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "can't load: " + e);
            }
            Match match = m;
            boolean loaded = ok;
            main.post(() -> {
                loading = false;
                if (loaded) listener.accept(match);
                long delay = !loaded ? RETRY_MS
                        : match == null ? IDLE_MS
                        : match.state == LIVE ? LIVE_MS
                        : match.state == FULL_TIME ? RESULT_REFRESH_MS
                        : Math.max(LIVE_MS, Math.min(IDLE_MS, match.kickoff - System.currentTimeMillis()));
                nextAt = SystemClock.elapsedRealtime() + delay;
                if (running) main.postDelayed(refresh, delay);
            });
        });
    }

    private Match load() throws IOException, JSONException {
        long now = System.currentTimeMillis();
        Match m = null;
        JSONObject next = null;
        JSONArray fixtures = json(API + "all/teams/" + team + "/schedule?fixture=true").getJSONArray("events");
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject e = fixtures.getJSONObject(i);
            if (kickoff(e) + RESULT_MS > now && (next == null || kickoff(e) < kickoff(next))) next = e;
        }
        if (next != null && kickoff(next) <= now) {
            m = parse(next);
            JSONObject summary = json(API + next.getJSONObject("league").getString("slug")
                    + "/summary?event=" + next.getString("id"));
            status(m, summary.getJSONObject("header").getJSONArray("competitions").getJSONObject(0));
        } else {
            JSONObject last = null;
            JSONArray results = json(API + "all/teams/" + team + "/schedule").getJSONArray("events");
            for (int i = 0; i < results.length(); i++) {
                JSONObject e = results.getJSONObject(i);
                if (last == null || kickoff(e) > kickoff(last)) last = e;
            }
            if (last != null && kickoff(last) + RESULT_MS > now) m = parse(last);
            else if (next != null) m = parse(next);
        }
        if (m == null) return null;
        logos.keySet().retainAll(Set.of(m.home.id, m.away.id));
        m.home.logo = logo(m.home.id);
        m.away.logo = logo(m.away.id);
        return m;
    }

    private static long kickoff(JSONObject event) throws JSONException {
        return OffsetDateTime.parse(event.getString("date")).toInstant().toEpochMilli();
    }

    private static Match parse(JSONObject event) throws JSONException {
        Match m = new Match();
        m.kickoff = kickoff(event);
        m.competition = event.getJSONObject("league").getString("shortName");
        status(m, event.getJSONArray("competitions").getJSONObject(0));
        return m;
    }

    private static void status(Match m, JSONObject competition) throws JSONException {
        JSONObject type = competition.getJSONObject("status").getJSONObject("type");
        String state = type.getString("state");
        m.state = "in".equals(state) ? LIVE : "post".equals(state) ? FULL_TIME : NEXT;
        m.detail = type.optString("shortDetail");
        JSONArray sides = competition.getJSONArray("competitors");
        for (int i = 0; i < sides.length(); i++) {
            JSONObject c = sides.getJSONObject(i);
            Side s = "home".equals(c.getString("homeAway")) ? m.home : m.away;
            JSONObject t = c.getJSONObject("team");
            s.id = t.getString("id");
            s.name = t.optString("shortDisplayName", t.optString("displayName"));
            s.abbr = t.optString("abbreviation", s.name);
            // Schedules give the score as an object, summaries as a plain string.
            Object score = c.opt("score");
            s.score = score instanceof JSONObject ? ((JSONObject) score).optString("displayValue", null)
                    : score instanceof String ? (String) score : null;
        }
    }

    private Bitmap logo(String id) {
        Bitmap b = logos.get(id);
        if (b != null) return b;
        try {
            byte[] data = bytes(String.format(Locale.US, LOGO, id, logoPx, logoPx));
            b = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (IOException e) {
            Log.w(TAG, "can't load logo " + id + ": " + e);
        }
        if (b != null) logos.put(id, b);
        return b;
    }

    private static JSONObject json(String url) throws IOException, JSONException {
        return new JSONObject(new String(bytes(url), StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode() + " from " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            conn.disconnect();
        }
    }
}
