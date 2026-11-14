package com.argon.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The club's season (Manchester City, ESPN team 382 by default) from ESPN's public site API, no key: the live, next or
 * just-finished match with its game sheet (lineups, goals, cards, stats, form, head-to-head, top scorers), the last
 * results, the fixtures, the league and European tables, the cups, the club's news and the transfer talk about it.
 *
 * The answers are kept in files/city.json so the home screen and the screensaver open with them. Fetched only while
 * one of them shows: every 30 s during a match, every 2 min in the three hours before kick-off (the lineups come about
 * an hour before), every 5 min after the final whistle, else every 30 min and again at kick-off. Tables, news,
 * transfer talk and the scorers of past matches are refreshed every 30 min.
 */
final class City {
    static final int NEXT = 0, LIVE = 1, FULL_TIME = 2;
    private static final String TAG = "City";
    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final String TABLES = "https://site.api.espn.com/apis/v2/sports/soccer/";
    private static final String STORY = "https://content.core.api.espn.com/v1/sports/news/";
    private static final String CREST = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/%s.png&w=%d&h=%d";
    private static final String RESIZE = "https://a.espncdn.com/combiner/i?img=%s&w=%d&h=%d";
    // A result stays the match for this long after kick-off before the next one takes over.
    private static final long RESULT_MS = 4 * 3600_000L;
    private static final long LIVE_MS = 30_000, LINEUPS_MS = 2 * 60_000L, AFTER_MS = 5 * 60_000L,
            IDLE_MS = 30 * 60_000L, RETRY_MS = 2 * 60_000L, SLOW_MS = 30 * 60_000L, SAVE_MS = 10 * 60_000L;
    static final int CREST_PX = 128, BIG_CREST_PX = 512, PHOTO_W = 480, PHOTO_H = 270, NEWS = 10, RUMOURS = 8;
    private static final String FILE = "city.json";

    interface Listener {
        void onCity();
    }

    static final class Team {
        String id = "", name = "", shortName = "", abbr = "";
    }

    /** One side of the match. `form`: the last five results, oldest first ("WDLWW"). */
    static final class Side {
        String id = "", name = "", abbr = "", score, shootout, form = "", record = "", points = "";
        int color = 0xFFFFFFFF, altColor = 0xFF000000;
        Lineup lineup;
        Leader goals, assists;
        final Map<String, String> stats = new HashMap<>();
    }

    static final class Leader {
        String name = "";
        int value;
    }

    static final class Player {
        String id = "", name = "", jersey = "", position = "";
        int place, goals;
        boolean starter, subIn, subOut, yellow, red;
    }

    static final class Lineup {
        String formation = "";
        final List<Player> starters = new ArrayList<>(), bench = new ArrayList<>();
    }

    /** A goal, a card or a substitution. `minute` as ESPN shows it ("45'+2'"). */
    static final class Event {
        String type, minute = "", player = "", other = "";
        boolean home, penalty, own;
    }

    static final class Meeting {
        long date;
        String home = "", away = "", score = "";
        char result;    // W, D or L for the club
    }

    static final class Match {
        String id = "", league = "", competition = "", round = "", venue = "", detail = "";
        int state;
        long kickoff;
        final Side home = new Side(), away = new Side();
        final List<Event> events = new ArrayList<>();
        final List<Meeting> meetings = new ArrayList<>();
        String series = "", club = "";
        boolean detailed;       // its summary (lineups, events, stats) has been read

        Side us() {
            return home.id.equals(club) ? home : away;
        }

        Side them() {
            return home.id.equals(club) ? away : home;
        }

        boolean hasLineups() {
            return home.lineup != null && away.lineup != null && home.lineup.starters.size() >= 11
                    && away.lineup.starters.size() >= 11;
        }
    }

    /** A finished match, from the club's side: `goals` is ours, `against` theirs. */
    static final class Result {
        String id = "", league = "", competition = "", round = "", scorers = "";
        long date;
        Team opponent = new Team();
        boolean home, pens;
        int goals, against;
        char outcome;
    }

    static final class Fixture {
        String id = "", league = "", competition = "", round = "", venue = "";
        long date;
        Team opponent = new Team();
        boolean home;
    }

    static final class Row {
        int rank, played, won, drawn, lost, gd, points;
        String id = "", name = "", abbr = "", note = "";
    }

    static final class Table {
        String league = "", name = "", stage = "";
        final List<Row> rows = new ArrayList<>();

        Row row(String id) {
            for (Row r : rows) if (r.id.equals(id)) return r;
            return null;
        }
    }

    /** A knockout cup: the ties so far and the next one. `waiting`: not in it yet (the FA Cup before January). */
    static final class Cup {
        String league = "", name = "", stage = "", waiting = "";
        final List<Result> path = new ArrayList<>();
        Fixture next;
        boolean out;
    }

    static final class Story {
        String id = "", headline = "", description = "", byline = "", image = "", type = "";
        long published;
    }

    /** One item of transfer talk about the club: who, what, which paper said it, from which ESPN story. */
    static final class Rumour {
        String player = "", text = "", source = "", headline = "", story = "";
        long date;
    }

    static final class Data {
        boolean loaded;
        long at;
        Team club = new Team();
        String league = "";
        Match match;
        final List<Result> results = new ArrayList<>();       // newest first
        final List<Fixture> fixtures = new ArrayList<>();     // soonest first
        Table table, europe;
        final List<Cup> cups = new ArrayList<>();
        final List<Story> news = new ArrayList<>();
        final List<Rumour> rumours = new ArrayList<>();
        Leader goals, assists;
        String leadersIn = "";

        /** The last five results, oldest first. */
        List<Result> form() {
            List<Result> out = new ArrayList<>(results.subList(0, Math.min(5, results.size())));
            Collections.reverse(out);
            return out;
        }

        String formString() {
            StringBuilder sb = new StringBuilder();
            for (Result r : form()) sb.append(r.outcome);
            return sb.toString();
        }
    }

    private static City instance;

    static synchronized City get(Context c) {
        if (instance == null) instance = new City(c.getApplicationContext());
        return instance;
    }

    volatile Data data = new Data();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Bitmap> crests = new ConcurrentHashMap<>(), photos = new ConcurrentHashMap<>();
    private final Runnable refresh = this::refresh;
    private volatile String team;
    private Bitmap bigCrest;
    private boolean loading;
    private long nextAt, slowAt, savedAt;
    private int generation;
    // Only the worker touches these.
    private JSONObject raw;
    private final Map<String, List<String>> bodies = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) {
            return size() > 12;
        }
    };

    private City(Context c) {
        context = c;
        team = Config.team(c, c.getSharedPreferences("launcher", Context.MODE_PRIVATE));
        int gen = generation;
        worker.execute(() -> {
            try {
                File f = new File(context.getFilesDir(), FILE);
                if (!f.exists()) return;
                JSONObject saved = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (!team.equals(saved.optString("club"))) return;
                raw = saved;
                Data d = parse(raw, System.currentTimeMillis());
                d.at = saved.optLong("at");
                loadImages(d, true);
                main.post(() -> {
                    if (gen != generation || data.loaded) return;
                    data = d;
                    notifyListeners();
                });
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "saved data unreadable: " + e);
            }
        });
    }

    boolean enabled() {
        return !team.isEmpty();
    }

    String team() {
        return team;
    }

    /** Main thread. The first listener starts the refreshes, the last one to go stops them. */
    void addListener(Listener l) {
        if (listeners.contains(l)) return;
        listeners.add(l);
        if (listeners.size() == 1) start();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) main.removeCallbacks(refresh);
    }

    /** Another club from the web remote: everything starts over. */
    void setTeam(String id) {
        id = id == null ? "" : id.trim();
        if (id.equals(team)) return;
        team = id;
        generation++;
        data = new Data();
        crests.clear();
        photos.clear();
        bigCrest = null;
        slowAt = nextAt = 0;
        worker.execute(() -> raw = null);
        notifyListeners();
        main.removeCallbacks(refresh);
        if (!listeners.isEmpty()) start();
    }

    private void start() {
        main.removeCallbacks(refresh);
        if (!loading && enabled()) main.postDelayed(refresh, Math.max(0, nextAt - SystemClock.elapsedRealtime()));
    }

    Bitmap crest(String id) {
        return id == null ? null : crests.get(id);
    }

    /** The club's crest at 512 px (for the wallpaper), or null until loaded. */
    Bitmap bigCrest() {
        return bigCrest;
    }

    Bitmap photo(String url) {
        return url == null || url.isEmpty() ? null : photos.get(url);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onCity();
    }

    private void refresh() {
        if (loading || !enabled()) return;
        loading = true;
        long now = System.currentTimeMillis();
        // A first load gets the match on screen before the tables, news and transfer talk (another 80-odd requests).
        boolean slow = data.loaded && (slowAt == 0 || now - slowAt >= SLOW_MS || now < slowAt);
        int gen = generation;
        String id = team;
        worker.execute(() -> {
            Data d = null;
            try {
                d = fetch(id, slow, now);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "can't load: " + e);
            }
            Data done = d;
            main.post(() -> {
                loading = false;
                if (gen != generation) {
                    if (!listeners.isEmpty()) start();
                    return;
                }
                if (done != null) {
                    if (slow) slowAt = now;
                    data = done;
                    notifyListeners();
                }
                long delay = done == null ? RETRY_MS : slowAt == 0 ? 500 : delay(done, System.currentTimeMillis());
                nextAt = SystemClock.elapsedRealtime() + delay;
                if (!listeners.isEmpty()) main.postDelayed(refresh, delay);
            });
        });
    }

    private static long delay(Data d, long now) {
        Match m = d.match;
        if (m == null) return IDLE_MS;
        if (m.state == LIVE) return LIVE_MS;
        if (m.state == FULL_TIME) return AFTER_MS;
        long until = m.kickoff - now;
        if (until < 3 * 3600_000L) return Math.max(LIVE_MS, Math.min(LINEUPS_MS, until));
        return Math.max(LIVE_MS, Math.min(IDLE_MS, until));
    }

    // MARK: - Fetching (worker thread)

    private Data fetch(String club, boolean slow, long now) throws IOException, JSONException {
        JSONObject r = raw != null && club.equals(raw.optString("club")) ? raw : new JSONObject().put("club", club);
        if (slow || !r.has("team")) r.put("team", json(API + "all/teams/" + club));
        r.put("fixtures", json(API + "all/teams/" + club + "/schedule?fixture=true"));
        r.put("results", json(API + "all/teams/" + club + "/schedule"));
        JSONObject focus = focus(r, now);
        if (focus != null) {
            r.put("summary", json(API + slug(focus) + "/summary?event=" + focus.getString("id")));
        } else {
            r.remove("summary");
        }
        if (slow) {
            String league = defaultLeague(r);
            JSONObject tables = new JSONObject();
            Set<String> wanted = new HashSet<>();
            wanted.add(league);
            String europe = europe(r);
            if (europe != null) wanted.add(europe);
            for (String slug : wanted) {
                try {
                    tables.put(slug, json(TABLES + slug + "/standings"));
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "no table for " + slug + ": " + e);
                }
            }
            r.put("tables", tables);
            JSONObject news = null;
            try {
                news = json(API + league + "/news?team=" + club + "&limit=50");
                r.put("news", news);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "no news: " + e);
            }
            try {
                rumours(r, news, json(API + league + "/news?limit=100"), club, now);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "no transfer talk: " + e);
            }
            scorers(r, club);
        }
        raw = r;
        Data d = parse(r, now);
        d.at = now;
        r.put("at", now);
        loadImages(d, slow);
        if (slow || now - savedAt > SAVE_MS || now < savedAt) save(r, now);
        return d;
    }

    private void save(JSONObject r, long now) {
        File tmp = new File(context.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(r.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "not saved: " + e);
            return;
        }
        if (tmp.renameTo(new File(context.getFilesDir(), FILE))) savedAt = now;
    }

    /** The match to show: one under way, else one that ended in the last few hours, else the next one. */
    private static JSONObject focus(JSONObject r, long now) throws JSONException {
        JSONObject next = null, last = null;
        JSONArray fixtures = events(r, "fixtures");
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject e = fixtures.getJSONObject(i);
            if (kickoff(e) + RESULT_MS > now && (next == null || kickoff(e) < kickoff(next))) next = e;
        }
        if (next != null && kickoff(next) <= now) return next;
        JSONArray results = events(r, "results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject e = results.getJSONObject(i);
            if (played(e) && (last == null || kickoff(e) > kickoff(last))) last = e;
        }
        if (last != null && kickoff(last) + RESULT_MS > now) return last;
        return next;
    }

    private static JSONArray events(JSONObject r, String key) {
        JSONObject o = r.optJSONObject(key);
        JSONArray a = o == null ? null : o.optJSONArray("events");
        return a == null ? new JSONArray() : a;
    }

    private static String defaultLeague(JSONObject r) {
        JSONObject t = r.optJSONObject("team");
        JSONObject team = t == null ? null : t.optJSONObject("team");
        JSONObject league = team == null ? null : team.optJSONObject("defaultLeague");
        String slug = league == null ? "" : league.optString("slug");
        return slug.isEmpty() ? "eng.1" : slug;
    }

    /** The European competition this season, if the club is in one. */
    private static String europe(JSONObject r) throws JSONException {
        for (String key : new String[] {"fixtures", "results"}) {
            JSONArray a = events(r, key);
            for (int i = 0; i < a.length(); i++) {
                String slug = slug(a.getJSONObject(i));
                if (slug.startsWith("uefa.") && !slug.contains("super_cup")) return slug;
            }
        }
        return null;
    }

    /**
     * Transfer talk about the club from the last three weeks: the items about it in ESPN's daily rumours column
     * (each its own paragraph, "- Manchester City left back …, according to The Sun"), and the club's own transfer
     * stories. A story's items are kept, so each story is only read once.
     */
    private void rumours(JSONObject r, JSONObject news, JSONObject leagueNews, String club, long now) throws JSONException {
        JSONObject read = r.optJSONObject("transferTalk");
        if (read == null) read = new JSONObject();
        JSONObject keep = new JSONObject();
        List<JSONObject> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject feed : new JSONObject[] {news, leagueNews}) {
            JSONArray a = feed == null ? null : feed.optJSONArray("articles");
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject art = a.getJSONObject(i);
                String id = art.optString("id");
                if (id.isEmpty() || !seen.add(id) || !transfers(art)) continue;
                if (now - date(art.optString("published")) > 21 * 86_400_000L) continue;
                candidates.add(art);
            }
        }
        // The club's own first, then newest first.
        candidates.sort((a, b) -> {
            int ours = Boolean.compare(mentions(b, club), mentions(a, club));
            return ours != 0 ? ours : Long.compare(date(b.optString("published")), date(a.optString("published")));
        });
        int fetched = 0;
        for (JSONObject art : candidates) {
            String id = art.optString("id");
            JSONArray items = read.optJSONArray(id);
            if (items == null) {
                if (fetched >= 8) continue;
                fetched++;
                try {
                    items = rumourItems(art, club);
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "story " + id + ": " + e);
                    continue;
                }
            }
            keep.put(id, items);
        }
        r.put("transferTalk", keep);
    }

    private static boolean transfers(JSONObject art) {
        if (art.optString("headline").toLowerCase(Locale.US).startsWith("transfer")) return true;
        JSONArray cats = art.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i);
            if (c != null && "topic".equals(c.optString("type")) && c.optString("description").contains("transfer")) return true;
        }
        return false;
    }

    private static final Pattern PARAGRAPH = Pattern.compile("<(p|h2|h3)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG = Pattern.compile("<strong>(.*?)</strong>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER = Pattern.compile("<a[^>]*player[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL_SOURCE = Pattern.compile("\\(([^()]{2,40})\\)\\s*$");
    private static final Pattern SOURCE = Pattern.compile("according to (?:the )?(?:<a[^>]*>)?([^<,.;]+)", Pattern.CASE_INSENSITIVE);

    private JSONArray rumourItems(JSONObject art, String club) throws IOException, JSONException {
        JSONObject h = json(STORY + art.optString("id")).getJSONArray("headlines").getJSONObject(0);
        String story = h.optString("story");
        JSONArray out = new JSONArray();
        Matcher p = PARAGRAPH.matcher(story);
        String name = data.club.name.isEmpty() ? "Manchester City" : data.club.name;
        String alias = name.startsWith("Manchester ") ? "Man " + name.substring(11) : name;
        boolean column = false;
        while (p.find()) {
            String html = p.group(2), text = plain(html);
            if (!text.startsWith("- ") && !text.startsWith("– ")) continue;
            column = true;
            boolean ours = html.contains("team?id=" + club + "\"") || text.contains(name) || text.contains(alias);
            if (!ours) continue;
            JSONObject item = new JSONObject();
            Matcher s = STRONG.matcher(html), pl = PLAYER.matcher(html);
            item.put("player", s.find() ? plain(s.group(1)) : pl.find() ? plain(pl.group(1)) : "");
            String body = text.substring(2).trim(), source = "";
            Matcher src = SOURCE.matcher(html), tail = TAIL_SOURCE.matcher(body);
            if (src.find()) {
                source = src.group(1).trim();
            } else if (tail.find()) {
                source = tail.group(1).trim();
                body = body.substring(0, tail.start()).trim();
            }
            item.put("text", body);
            item.put("source", source);
            item.put("date", art.optString("published"));
            item.put("headline", art.optString("headline"));
            item.put("story", art.optString("id"));
            out.put(item);
        }
        // Not the rumours column: the club's own transfer story, as one item.
        if (!column && mentions(art, club)) {
            JSONObject item = new JSONObject();
            item.put("player", art.optString("headline"));
            item.put("text", art.optString("description"));
            item.put("source", "ESPN");
            item.put("date", art.optString("published"));
            item.put("headline", art.optString("headline"));
            item.put("story", art.optString("id"));
            out.put(item);
        }
        return out;
    }

    private static boolean mentions(JSONObject art, String club) {
        JSONArray cats = art.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i);
            if (c != null && "team".equals(c.optString("type")) && club.equals(String.valueOf(c.optInt("teamId", -1)))) return true;
        }
        return false;
    }

    /** Who scored for the club in each of the last results (read once per match: finished matches don't change). */
    private void scorers(JSONObject r, String club) throws JSONException {
        JSONObject known = r.optJSONObject("scorers");
        if (known == null) known = new JSONObject();
        JSONObject keep = new JSONObject();
        List<JSONObject> recent = new ArrayList<>();
        JSONArray results = events(r, "results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject e = results.getJSONObject(i);
            if (played(e) && competitive(slug(e))) recent.add(e);
        }
        recent.sort((a, b) -> Long.compare(date(b.optString("date")), date(a.optString("date"))));
        for (JSONObject e : recent.subList(0, Math.min(5, recent.size()))) {
            String id = e.getString("id");
            if (known.has(id)) {
                keep.put(id, known.getString(id));
                continue;
            }
            try {
                JSONObject s = json(API + slug(e) + "/summary?event=" + id);
                keep.put(id, scorerLine(s.optJSONArray("keyEvents"), club));
            } catch (IOException | JSONException ex) {
                Log.w(TAG, "scorers of " + id + ": " + ex);
            }
        }
        r.put("scorers", keep);
    }

    private static String scorerLine(JSONArray events, String club) {
        Map<String, Integer> goals = new LinkedHashMap<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null || !e.optBoolean("scoringPlay")) continue;
            JSONObject t = e.optJSONObject("team");
            if (t == null || !club.equals(t.optString("id"))) continue;
            String type = e.optJSONObject("type") == null ? "" : e.optJSONObject("type").optString("type");
            String name = type.contains("own") ? "OG" : participant(e, 0);
            if (name.isEmpty()) continue;
            goals.merge(name, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> g : goals.entrySet()) parts.add(g.getValue() > 1 ? g.getKey() + " " + g.getValue() : g.getKey());
        return TextUtils.join(", ", parts);
    }

    private static String participant(JSONObject e, int i) {
        JSONArray ps = e.optJSONArray("participants");
        if (ps == null || ps.length() <= i) return "";
        JSONObject a = ps.optJSONObject(i) == null ? null : ps.optJSONObject(i).optJSONObject("athlete");
        if (a == null) return "";
        return surname(a);
    }

    /** "De Bruyne" from "K. De Bruyne" or "Kevin De Bruyne"; a one-word name as it is. */
    private static String surname(JSONObject athlete) {
        String last = athlete.optString("lastName");
        if (!last.isEmpty()) return last;
        String s = athlete.optString("shortName", athlete.optString("displayName"));
        return shortName(s.replaceFirst("^[A-Z]\\.\\s*", ""));
    }

    static String shortName(String s) {
        int space = s.indexOf(' ');
        return space > 0 && space < s.length() - 1 ? s.substring(space + 1) : s;
    }

    /** Crests for everything shown, the club's big one, and the first news pictures; from disk when we have them. */
    private void loadImages(Data d, boolean all) {
        Set<String> ids = new HashSet<>();
        ids.add(d.club.id);
        if (d.match != null) {
            ids.add(d.match.home.id);
            ids.add(d.match.away.id);
        }
        if (!all) {
            ids.remove("");
            for (String id : ids) {
                if (crests.containsKey(id)) continue;
                Bitmap b = image(String.format(Locale.US, CREST, id, CREST_PX, CREST_PX), "crest-" + id + ".png");
                if (b != null) crests.put(id, b);
            }
            return;
        }
        for (Result r : d.results) ids.add(r.opponent.id);
        for (Fixture f : d.fixtures) ids.add(f.opponent.id);
        for (Table t : new Table[] {d.table, d.europe}) if (t != null) for (Row row : t.rows) ids.add(row.id);
        for (Cup c : d.cups) if (c.next != null) ids.add(c.next.opponent.id);
        ids.remove("");
        ids.remove(null);
        for (String id : ids) {
            if (crests.containsKey(id)) continue;
            Bitmap b = image(String.format(Locale.US, CREST, id, CREST_PX, CREST_PX), "crest-" + id + ".png");
            if (b != null) crests.put(id, b);
        }
        if (bigCrest == null && !d.club.id.isEmpty()) {
            bigCrest = image(String.format(Locale.US, CREST, d.club.id, BIG_CREST_PX, BIG_CREST_PX), "crest-" + d.club.id + "-big.png");
        }
        Set<String> want = new HashSet<>();
        for (int i = 0; i < Math.min(5, d.news.size()); i++) want.add(d.news.get(i).image);
        want.remove("");
        photos.keySet().retainAll(want);
        for (String url : want) {
            if (photos.containsKey(url)) continue;
            String path = url.replaceFirst("^https?://[^/]+", "");
            Bitmap b = image(String.format(Locale.US, RESIZE, path, PHOTO_W, PHOTO_H), "photo-" + Integer.toHexString(url.hashCode()) + ".jpg");
            if (b != null) photos.put(url, Backdrop.gpu(b));
        }
    }

    /** From the cache dir, else downloaded and kept there. */
    private Bitmap image(String url, String name) {
        File dir = new File(context.getCacheDir(), "city");
        File f = new File(dir, name);
        try {
            if (!f.exists()) {
                byte[] bytes = bytes(url);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("no cache dir");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (IOException e) {
            Log.w(TAG, "image " + name + ": " + e);
            return null;
        }
    }

    /** A story's text as paragraphs ("## " starts a heading), for the reader; null on failure. Main-thread callback. */
    void story(String id, Consumer<List<String>> done) {
        worker.execute(() -> {
            List<String> out = bodies.get(id);
            if (out == null) {
                try {
                    String html = json(STORY + URLEncoder.encode(id, "UTF-8")).getJSONArray("headlines").getJSONObject(0).optString("story");
                    out = new ArrayList<>();
                    Matcher p = PARAGRAPH.matcher(html);
                    while (p.find()) {
                        String text = plain(p.group(2));
                        if (text.isEmpty() || p.group(2).contains("<photo") || p.group(2).contains("<video")) continue;
                        if (text.startsWith("Transfers home page") || text.startsWith("ALSO:")) continue;
                        out.add(p.group(1).equalsIgnoreCase("p") ? text : "## " + text);
                    }
                    bodies.put(id, out);
                } catch (IOException | JSONException | RuntimeException e) {
                    Log.w(TAG, "story " + id + ": " + e);
                }
            }
            List<String> result = out;
            main.post(() -> done.accept(result));
        });
    }

    private static String plain(String html) {
        return Html.fromHtml(html.replaceAll("<(photo|video|alsoSee|inline)[^>]*>", ""), Html.FROM_HTML_MODE_LEGACY)
                .toString().replace('\u00A0', ' ').replace('\u2011', '-').replace("\u00AD", "").replaceAll("\\s+", " ").trim();
    }

    // MARK: - Parsing (worker thread, from the saved answers)

    private static Data parse(JSONObject r, long now) throws JSONException {
        Data d = new Data();
        String club = r.optString("club");
        d.loaded = r.has("fixtures") || r.has("results");
        d.club.id = club;
        JSONObject teamObj = r.optJSONObject("team") == null ? null : r.optJSONObject("team").optJSONObject("team");
        if (teamObj != null) team(teamObj, d.club);
        d.league = defaultLeague(r);

        JSONArray results = events(r, "results");
        JSONObject scorers = r.optJSONObject("scorers");
        for (int i = 0; i < results.length(); i++) {
            JSONObject e = results.getJSONObject(i);
            if (!played(e) || !competitive(slug(e))) continue;
            Result res = result(e, club);
            if (res == null) continue;
            if (scorers != null) {
                // Saved as given; shown by surname ("Erling Haaland 2" → "Haaland 2").
                List<String> parts = new ArrayList<>();
                for (String part : scorers.optString(res.id).split(",\\s*")) {
                    if (part.isEmpty()) continue;
                    Matcher n = Pattern.compile("^(.*?)( \\d+)?$").matcher(part);
                    parts.add(n.matches() ? (n.group(1).equals("OG") ? "OG" : shortName(n.group(1))) + (n.group(2) == null ? "" : n.group(2)) : part);
                }
                res.scorers = TextUtils.join(", ", parts);
            }
            d.results.add(res);
        }
        d.results.sort((a, b) -> Long.compare(b.date, a.date));

        JSONArray fixtures = events(r, "fixtures");
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject e = fixtures.getJSONObject(i);
            if (kickoff(e) + 3 * 3600_000L < now || !competitive(slug(e))) continue;
            Fixture f = fixture(e, club);
            if (f != null) d.fixtures.add(f);
        }
        d.fixtures.sort((a, b) -> Long.compare(a.date, b.date));

        JSONObject tables = r.optJSONObject("tables");
        if (tables != null) {
            for (java.util.Iterator<String> it = tables.keys(); it.hasNext(); ) {
                String slug = it.next();
                Table t = table(tables.getJSONObject(slug), slug);
                if (t == null || t.row(club) == null) continue;
                if (slug.equals(d.league)) d.table = t;
                else d.europe = t;
            }
        }

        JSONObject focus = focus(r, now);
        JSONObject summary = r.optJSONObject("summary");
        if (focus != null) {
            d.match = match(focus, summary != null && focus.optString("id").equals(summaryId(summary)) ? summary : null, club, now);
            if (summary != null) leaders(summary, club, d);
        }

        cups(d, results, fixtures, club);

        JSONObject news = r.optJSONObject("news");
        JSONArray arts = news == null ? null : news.optJSONArray("articles");
        for (int i = 0; arts != null && i < arts.length() && d.news.size() < NEWS; i++) {
            Story s = story(arts.getJSONObject(i));
            if (s != null) d.news.add(s);
        }

        JSONObject stories = r.optJSONObject("transferTalk");
        if (stories != null) {
            Set<String> texts = new HashSet<>();
            for (java.util.Iterator<String> it = stories.keys(); it.hasNext(); ) {
                JSONArray items = stories.getJSONArray(it.next());
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    Rumour ru = new Rumour();
                    ru.player = o.optString("player");
                    ru.text = o.optString("text");
                    ru.source = o.optString("source");
                    ru.headline = o.optString("headline");
                    ru.story = o.optString("story");
                    ru.date = date(o.optString("date"));
                    String key = ru.text.length() > 80 ? ru.text.substring(0, 80) : ru.text;
                    if (!ru.text.isEmpty() && texts.add(key)) d.rumours.add(ru);
                }
            }
            d.rumours.sort((a, b) -> Long.compare(b.date, a.date));
            while (d.rumours.size() > RUMOURS) d.rumours.remove(d.rumours.size() - 1);
        }
        return d;
    }

    private static String summaryId(JSONObject summary) {
        JSONObject h = summary.optJSONObject("header");
        return h == null ? "" : h.optString("id");
    }

    private static void team(JSONObject t, Team out) {
        out.id = t.optString("id", out.id);
        out.name = t.optString("displayName", out.name);
        out.shortName = t.optString("shortDisplayName", out.name);
        out.abbr = t.optString("abbreviation", out.shortName);
    }

    private static Result result(JSONObject e, String club) throws JSONException {
        JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray cs = comp.getJSONArray("competitors");
        Result res = new Result();
        res.id = e.optString("id");
        res.date = kickoff(e);
        res.league = slug(e);
        res.competition = competition(e);
        res.round = round(e);
        String status = comp.optJSONObject("status") == null ? "" : comp.getJSONObject("status").getJSONObject("type").optString("name");
        res.pens = status.contains("PEN");
        boolean found = false;
        Boolean won = null;
        boolean anyWinner = false;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            JSONObject t = c.getJSONObject("team");
            int score = score(c.opt("score"));
            anyWinner |= c.optBoolean("winner");
            if (club.equals(t.optString("id"))) {
                found = true;
                res.home = "home".equals(c.optString("homeAway"));
                res.goals = score;
                won = c.optBoolean("winner");
            } else {
                team(t, res.opponent);
                res.against = score;
            }
        }
        if (!found) return null;
        res.outcome = !anyWinner ? 'D' : won ? 'W' : 'L';
        return res;
    }

    private static Fixture fixture(JSONObject e, String club) throws JSONException {
        JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray cs = comp.getJSONArray("competitors");
        Fixture f = new Fixture();
        f.id = e.optString("id");
        f.date = kickoff(e);
        f.league = slug(e);
        f.competition = competition(e);
        f.round = round(e);
        JSONObject venue = comp.optJSONObject("venue");
        if (venue != null) f.venue = venue.optString("fullName");
        boolean found = false;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            JSONObject t = c.getJSONObject("team");
            if (club.equals(t.optString("id"))) {
                found = true;
                f.home = "home".equals(c.optString("homeAway"));
            } else {
                team(t, f.opponent);
            }
        }
        return found ? f : null;
    }

    private static Table table(JSONObject o, String slug) throws JSONException {
        JSONArray children = o.optJSONArray("children");
        if (children == null || children.length() == 0) return null;
        JSONObject child = children.getJSONObject(0);
        JSONObject st = child.optJSONObject("standings");
        JSONArray entries = st == null ? null : st.optJSONArray("entries");
        if (entries == null) return null;
        Table t = new Table();
        t.league = slug;
        t.name = o.optString("name").replace("English ", "").replace("UEFA ", "");
        t.stage = children.length() == 1 ? child.optString("name") : "";
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            JSONObject team = e.getJSONObject("team");
            Row row = new Row();
            row.id = team.optString("id");
            row.name = team.optString("shortDisplayName", team.optString("displayName"));
            row.abbr = team.optString("abbreviation");
            JSONObject note = e.optJSONObject("note");
            row.note = note == null ? "" : note.optString("description");
            JSONArray stats = e.getJSONArray("stats");
            for (int j = 0; j < stats.length(); j++) {
                JSONObject s = stats.getJSONObject(j);
                int v = (int) Math.round(s.optDouble("value", 0));
                switch (s.optString("name")) {
                    case "rank": row.rank = v; break;
                    case "gamesPlayed": row.played = v; break;
                    case "wins": row.won = v; break;
                    case "ties": row.drawn = v; break;
                    case "losses": row.lost = v; break;
                    case "pointDifferential": row.gd = v; break;
                    case "points": row.points = v; break;
                    default: break;
                }
            }
            t.rows.add(row);
        }
        t.rows.sort((a, b) -> Integer.compare(a.rank, b.rank));
        return t;
    }

    private static Match match(JSONObject event, JSONObject summary, String club, long now) throws JSONException {
        Match m = new Match();
        m.club = club;
        m.id = event.optString("id");
        m.kickoff = kickoff(event);
        m.league = slug(event);
        m.competition = competition(event);
        m.round = round(event);
        JSONObject comp = event.getJSONArray("competitions").getJSONObject(0);
        JSONObject venue = comp.optJSONObject("venue");
        if (venue != null) m.venue = venue.optString("fullName");
        sides(m, comp);
        status(m, comp);
        if (summary == null) return m;
        m.detailed = true;
        JSONObject h = summary.getJSONObject("header").getJSONArray("competitions").getJSONObject(0);
        sides(m, h);
        status(m, h);
        JSONObject info = summary.optJSONObject("gameInfo");
        JSONObject v = info == null ? null : info.optJSONObject("venue");
        if (v != null && !v.optString("fullName").isEmpty()) m.venue = v.optString("fullName");

        JSONArray rosters = summary.optJSONArray("rosters");
        for (int i = 0; rosters != null && i < rosters.length(); i++) {
            JSONObject ro = rosters.getJSONObject(i);
            Side s = "home".equals(ro.optString("homeAway")) ? m.home : m.away;
            colors(s, ro.optJSONObject("team"));
            JSONArray list = ro.optJSONArray("roster");
            if (list == null || list.length() == 0) continue;
            Lineup l = new Lineup();
            l.formation = ro.optString("formation");
            for (int j = 0; j < list.length(); j++) {
                JSONObject p = list.getJSONObject(j);
                JSONObject a = p.optJSONObject("athlete");
                if (a == null) continue;
                Player pl = new Player();
                pl.id = a.optString("id");
                pl.name = surname(a);
                pl.jersey = p.optString("jersey", a.optString("jersey"));
                JSONObject pos = p.optJSONObject("position");
                pl.position = pos == null ? "" : pos.optString("abbreviation");
                pl.place = p.optInt("formationPlace");
                pl.starter = p.optBoolean("starter");
                pl.subIn = p.optBoolean("subbedIn");
                pl.subOut = p.optBoolean("subbedOut");
                (pl.starter ? l.starters : l.bench).add(pl);
            }
            s.lineup = l;
        }

        JSONArray events = summary.optJSONArray("keyEvents");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            String type = e.optJSONObject("type") == null ? "" : e.getJSONObject("type").optString("type");
            JSONObject t = e.optJSONObject("team");
            if (t == null) continue;
            Event ev = new Event();
            if (e.optBoolean("scoringPlay")) ev.type = "goal";
            else if (type.contains("red")) ev.type = "red";
            else if (type.contains("yellow")) ev.type = "yellow";
            else if (type.contains("substitution")) ev.type = "sub";
            else continue;
            ev.home = t.optString("id").equals(m.home.id);
            ev.own = type.contains("own");
            ev.penalty = type.contains("penalty") || e.optString("text").contains("penalty");
            JSONObject clock = e.optJSONObject("clock");
            ev.minute = clock == null ? "" : clock.optString("displayValue");
            ev.player = participant(e, 0);
            ev.other = participant(e, 1);
            m.events.add(ev);
            mark(ev.home ? m.home : m.away, e, ev);
        }

        JSONObject box = summary.optJSONObject("boxscore");
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject bt = teams.getJSONObject(i);
            JSONObject t = bt.optJSONObject("team");
            Side s = t != null && t.optString("id").equals(m.home.id) ? m.home : m.away;
            colors(s, t);
            JSONArray stats = bt.optJSONArray("statistics");
            for (int j = 0; stats != null && j < stats.length(); j++) {
                JSONObject st = stats.getJSONObject(j);
                s.stats.put(st.optString("name"), st.optString("displayValue"));
            }
        }

        JSONArray five = summary.optJSONArray("lastFiveGames");
        for (int i = 0; five != null && i < five.length(); i++) {
            JSONObject f = five.getJSONObject(i);
            JSONObject t = f.optJSONObject("team");
            Side s = t != null && t.optString("id").equals(m.home.id) ? m.home : m.away;
            JSONArray ev = f.optJSONArray("events");
            List<JSONObject> games = new ArrayList<>();
            for (int j = 0; ev != null && j < ev.length(); j++) games.add(ev.getJSONObject(j));
            games.sort((a, b) -> Long.compare(date(a.optString("gameDate")), date(b.optString("gameDate"))));
            StringBuilder sb = new StringBuilder();
            for (JSONObject g : games) {
                String res = g.optString("gameResult");
                if (!res.isEmpty()) sb.append(res.charAt(0));
            }
            if (sb.length() > 0) s.form = sb.toString();
        }

        JSONArray series = summary.optJSONArray("seasonseries");
        JSONObject ss = series == null || series.length() == 0 ? null : series.getJSONObject(0);
        if (ss != null) {
            m.series = ss.optString("summary");
            JSONArray ev = ss.optJSONArray("events");
            for (int i = 0; ev != null && i < ev.length(); i++) {
                JSONObject e = ev.getJSONObject(i);
                JSONArray cs = e.optJSONArray("competitors");
                if (cs == null || cs.length() < 2) continue;
                Meeting mt = new Meeting();
                mt.date = date(e.optString("date"));
                int ours = -1, theirs = -1;
                String hs = "", as = "";
                for (int j = 0; j < cs.length(); j++) {
                    JSONObject c = cs.getJSONObject(j);
                    JSONObject t = c.optJSONObject("team");
                    String abbr = t == null ? "" : t.optString("abbreviation");
                    int sc = score(c.opt("score"));
                    if ("home".equals(c.optString("homeAway"))) {
                        mt.home = abbr;
                        hs = String.valueOf(sc);
                    } else {
                        mt.away = abbr;
                        as = String.valueOf(sc);
                    }
                    if (t != null && club.equals(t.optString("id"))) ours = sc;
                    else theirs = sc;
                }
                mt.score = hs + "–" + as;
                mt.result = ours > theirs ? 'W' : ours < theirs ? 'L' : 'D';
                m.meetings.add(mt);
            }
            m.meetings.sort((a, b) -> Long.compare(b.date, a.date));
        }

        JSONArray leaders = summary.optJSONArray("leaders");
        for (int i = 0; leaders != null && i < leaders.length(); i++) {
            JSONObject l = leaders.getJSONObject(i);
            JSONObject t = l.optJSONObject("team");
            Side s = t != null && t.optString("id").equals(m.home.id) ? m.home : m.away;
            JSONArray cats = l.optJSONArray("leaders");
            for (int j = 0; cats != null && j < cats.length(); j++) {
                JSONObject cat = cats.getJSONObject(j);
                Leader top = leader(cat);
                if ("goalsLeaders".equals(cat.optString("name"))) s.goals = top;
                else if ("assistsLeaders".equals(cat.optString("name"))) s.assists = top;
            }
        }
        return m;
    }

    private static void leaders(JSONObject summary, String club, Data d) throws JSONException {
        JSONArray leaders = summary.optJSONArray("leaders");
        for (int i = 0; leaders != null && i < leaders.length(); i++) {
            JSONObject l = leaders.getJSONObject(i);
            JSONObject t = l.optJSONObject("team");
            if (t == null || !club.equals(t.optString("id"))) continue;
            JSONArray cats = l.optJSONArray("leaders");
            for (int j = 0; cats != null && j < cats.length(); j++) {
                JSONObject cat = cats.getJSONObject(j);
                if ("goalsLeaders".equals(cat.optString("name"))) d.goals = leader(cat);
                else if ("assistsLeaders".equals(cat.optString("name"))) d.assists = leader(cat);
            }
        }
        JSONObject h = summary.optJSONObject("header");
        JSONObject league = h == null ? null : h.optJSONObject("league");
        d.leadersIn = league == null ? "" : league.optString("shortName", league.optString("name"));
    }

    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)\\s*$");

    private static Leader leader(JSONObject cat) {
        JSONArray ls = cat.optJSONArray("leaders");
        JSONObject top = ls == null || ls.length() == 0 ? null : ls.optJSONObject(0);
        if (top == null || top.optJSONObject("athlete") == null) return null;
        Leader l = new Leader();
        l.name = surname(top.optJSONObject("athlete"));
        Matcher n = LAST_NUMBER.matcher(top.optString("displayValue"));
        l.value = n.find() ? Integer.parseInt(n.group(1)) : (int) top.optDouble("value", 0);
        return l;
    }

    /** Puts goals and cards on the players in the lineup. */
    private static void mark(Side s, JSONObject e, Event ev) {
        if (s.lineup == null) return;
        JSONArray ps = e.optJSONArray("participants");
        JSONObject first = ps == null || ps.length() == 0 ? null : ps.optJSONObject(0);
        JSONObject a = first == null ? null : first.optJSONObject("athlete");
        if (a == null) return;
        String id = a.optString("id");
        List<Player> all = new ArrayList<>(s.lineup.starters);
        all.addAll(s.lineup.bench);
        for (Player p : all) {
            if (!p.id.equals(id)) continue;
            if ("goal".equals(ev.type) && !ev.own) p.goals++;
            else if ("yellow".equals(ev.type)) p.yellow = true;
            else if ("red".equals(ev.type)) p.red = true;
        }
    }

    private static void sides(Match m, JSONObject comp) throws JSONException {
        JSONArray cs = comp.getJSONArray("competitors");
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            Side s = "home".equals(c.optString("homeAway")) ? m.home : m.away;
            JSONObject t = c.getJSONObject("team");
            s.id = t.optString("id", s.id);
            s.name = t.optString("shortDisplayName", t.optString("displayName", s.name));
            s.abbr = t.optString("abbreviation", s.name);
            colors(s, t);
            Object score = c.opt("score");
            String sc = score instanceof JSONObject ? ((JSONObject) score).optString("displayValue", null)
                    : score instanceof String ? (String) score : null;
            if (sc != null && !sc.isEmpty()) s.score = sc;
            Object so = c.opt("shootoutScore");
            if (so != null && so != JSONObject.NULL) s.shootout = String.valueOf(so instanceof Number ? ((Number) so).intValue() : so);
            String form = c.optString("form");
            if (!form.isEmpty()) s.form = form;
            JSONArray record = c.optJSONArray("record");
            for (int j = 0; record != null && j < record.length(); j++) {
                JSONObject rr = record.getJSONObject(j);
                if ("total".equals(rr.optString("type"))) s.record = rr.optString("summary");
                else if ("points".equals(rr.optString("type"))) s.points = rr.optString("summary");
            }
        }
    }

    private static void colors(Side s, JSONObject t) {
        if (t == null) return;
        Integer c = hex(t.optString("color")), a = hex(t.optString("alternateColor"));
        if (c != null) s.color = c;
        if (a != null) s.altColor = a;
    }

    private static Integer hex(String s) {
        if (s == null || s.length() != 6) return null;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void status(Match m, JSONObject comp) {
        JSONObject st = comp.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        if (type == null) return;
        String state = type.optString("state");
        m.state = "in".equals(state) ? LIVE : "post".equals(state) ? FULL_TIME : NEXT;
        m.detail = type.optString("shortDetail");
    }

    /** The cups: each knockout competition this season (not the league, not a European league phase with a table). */
    private static void cups(Data d, JSONArray results, JSONArray fixtures, String club) throws JSONException {
        Map<String, Cup> cups = new LinkedHashMap<>();
        for (JSONArray a : new JSONArray[] {results, fixtures}) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                String slug = slug(e);
                if (!competitive(slug) || slug.equals(d.league) || (d.europe != null && slug.equals(d.europe.league))) continue;
                Cup c = cups.get(slug);
                if (c == null) {
                    c = new Cup();
                    c.league = slug;
                    c.name = competition(e);
                    cups.put(slug, c);
                }
                if (a == results) {
                    if (!played(e)) continue;
                    Result r = result(e, club);
                    if (r != null) c.path.add(r);
                } else {
                    Fixture f = fixture(e, club);
                    if (f != null && (c.next == null || f.date < c.next.date)) c.next = f;
                }
            }
        }
        // English clubs from the top two divisions join the FA Cup in the third round, in January.
        if (d.league.startsWith("eng.") && !cups.containsKey("eng.fa")) {
            Cup fa = new Cup();
            fa.league = "eng.fa";
            fa.name = "FA Cup";
            fa.stage = "Third round";
            fa.waiting = "Enters in the third round, in January";
            cups.put("eng.fa", fa);
        }
        for (Cup c : cups.values()) {
            c.path.sort((a, b) -> Long.compare(a.date, b.date));
            Result last = c.path.isEmpty() ? null : c.path.get(c.path.size() - 1);
            if (c.next != null) {
                c.stage = c.next.round;
            } else if (last != null) {
                c.out = last.outcome == 'L';
                c.stage = c.out ? "Out" : last.round;
            }
            if (!c.waiting.isEmpty() || c.next != null || !c.path.isEmpty()) d.cups.add(c);
        }
        List<Cup> sorted = new ArrayList<>(d.cups);
        sorted.sort((a, b) -> Integer.compare(cupOrder(a.league), cupOrder(b.league)));
        d.cups.clear();
        d.cups.addAll(sorted);
    }

    private static int cupOrder(String slug) {
        return slug.equals("eng.league_cup") ? 0 : slug.equals("eng.fa") ? 1 : 2;
    }

    private static Story story(JSONObject a) {
        if ("Media".equals(a.optString("type")) || a.optString("headline").isEmpty()) return null;
        Story s = new Story();
        s.id = a.optString("id");
        s.headline = a.optString("headline");
        s.description = a.optString("description");
        s.byline = a.optString("byline");
        s.type = a.optString("type");
        s.published = date(a.optString("published"));
        JSONArray images = a.optJSONArray("images");
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            String url = im == null ? "" : im.optString("url");
            if (url.contains("a.espncdn.com/photo/") && (url.endsWith(".jpg") || url.endsWith(".png"))) {
                s.image = url;
                break;
            }
        }
        return s;
    }

    // MARK: - Small helpers

    /** The round, for a knockout ("Quarterfinals" → "Quarter-finals"); empty for a league's matchday. */
    private static String round(JSONObject e) {
        JSONObject st = e.optJSONObject("seasonType");
        String name = st == null ? "" : st.optString("name");
        if (name.isEmpty() || name.matches(".*\\d{4}.*")) return "";
        name = name.replace("Quarterfinals", "Quarter-finals").replace("Semifinals", "Semi-finals")
                .replace("Knockout Round Playoffs", "Knockout play-offs");
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.US);
    }

    private static String competition(JSONObject e) {
        JSONObject l = e.optJSONObject("league");
        if (l == null) return "";
        String s = l.optString("shortName", l.optString("abbreviation", l.optString("name")));
        return s.replace("UEFA ", "").replace("English ", "");
    }

    static String slug(JSONObject e) {
        JSONObject l = e.optJSONObject("league");
        return l == null ? "" : l.optString("slug");
    }

    private static boolean competitive(String slug) {
        return !slug.isEmpty() && !slug.equals("club.friendly") && !slug.endsWith(".charity");
    }

    private static boolean played(JSONObject e) {
        JSONArray cs = e.optJSONArray("competitions");
        JSONObject comp = cs == null ? null : cs.optJSONObject(0);
        JSONObject st = comp == null ? null : comp.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        if (type == null) return false;
        String name = type.optString("name");
        return "post".equals(type.optString("state")) && !name.contains("POSTPONED") && !name.contains("CANCEL")
                && !name.contains("ABANDON");
    }

    private static int score(Object o) {
        String s = o instanceof JSONObject ? ((JSONObject) o).optString("displayValue", "0") : o == null ? "0" : String.valueOf(o);
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long kickoff(JSONObject event) throws JSONException {
        return OffsetDateTime.parse(event.getString("date")).toInstant().toEpochMilli();
    }

    /** ESPN writes dates as "2026-11-21T12:30Z" or "2026-02-11T19:30:00Z"; 0 when unreadable. */
    static long date(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static JSONObject json(String url) throws IOException, JSONException {
        return new JSONObject(new String(bytes(url), StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
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

    // MARK: - Text for the screens

    static String ordinal(int n) {
        int t = n % 100;
        String s = t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + s;
    }

    /** "in 6 days", "in 5 h 12 min", "in 12 min", "now". */
    static String countdown(long at, long now) {
        long min = (at - now) / 60_000;
        if (min <= 0) return "Kick-off now";
        if (min < 60) return "in " + min + " min";
        long h = min / 60;
        if (h < 24) return "in " + h + " h" + (min % 60 > 0 ? " " + min % 60 + " min" : "");
        long days = Math.round(h / 24.0);
        return days == 1 ? "tomorrow" : "in " + days + " days";
    }

    /** "2 h ago", "Yesterday", "3 days ago", "11 Nov". */
    static String ago(long at, long now) {
        long min = (now - at) / 60_000;
        if (at == 0) return "";
        if (min < 1) return "Just now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return min / 60 + " h ago";
        long days = min / (24 * 60);
        if (days == 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        return new java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(new java.util.Date(at));
    }
}
