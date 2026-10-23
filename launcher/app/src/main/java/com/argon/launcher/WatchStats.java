package com.argon.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.WatchNextPrograms;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Today and this week (from Monday), worked out from WatchLog for the home screen widgets, the
// stats screen and the web remote (as JSON, through StatsProvider).
final class WatchStats {
    static final long MIN = 60_000, HOUR = 60 * MIN;
    private static final int MAX_SHOWS = 8;
    private static final long MIN_PLAY_MS = 10_000;
    // How far a Continue watching update can be from untitled time and still name it.
    private static final long MATCH_MS = 30 * MIN;

    // Home screens, settings and system screens: on screen, but not watching.
    private static final Set<String> NOT_WATCHING = Set.of(
            "com.argon.launcher", "com.tvzooms.launcher", "com.google.android.apps.tv.launcherx", "com.android.tv.settings",
            "com.android.systemui", "android", "com.google.android.tungsten.setupwraith", "com.android.vending",
            "com.google.android.katniss", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.google.android.tv.remote.service");
    private static final Set<String> SCREENSAVERS = Set.of("com.google.android.apps.tv.dreamx", "com.android.dreams.basic");
    private static final Map<String, Integer> COLORS = Map.of(
            "com.google.android.youtube.tv", 0xFFF0566B,
            "com.netflix.ninja", 0xFFF5B65C,
            "in.startv.hotstar", 0xFF8C8CFF,
            "com.amazon.amazonvideo.livingroom", 0xFF5EC8F2,
            "com.sonyliv", 0xFFE3A2EE,
            "com.spotify.tv.android", 0xFF6FD39A,
            "com.mitv.livetv", 0xFFB4AFC6);
    private static final int[] SPARE = {0xFF7FD1C9, 0xFFE8A0BF, 0xFFC9B37E, 0xFF9DB4FF, 0xFFB4AFC6};

    static final class App {
        final String pkg, name;
        final int color;
        long today, week;
        int opens;

        App(String pkg, String name, int color) {
            this.pkg = pkg;
            this.name = name;
            this.color = color;
        }
    }

    static final class Show {
        final String pkg, title;
        String app, subtitle;
        int color;
        long ms, last;
        float progress = -1f;

        Show(String pkg, String title) {
            this.pkg = pkg;
            this.title = title;
        }
    }

    boolean tracking;
    long now, todayStart, weekStart, since = -1;
    long watchedToday, screenToday, screensaverToday, watchedWeek, screenWeek;
    long firstOnToday = -1;
    final long[] hourOn = new long[24], dayOn = new long[7];
    final List<Map<String, Long>> hourApps = new ArrayList<>(), dayApps = new ArrayList<>();
    // Watched this week, most watched today first.
    final List<App> apps = new ArrayList<>();
    // Newest first: plays with titles, then Continue watching rows not played this week.
    final List<Show> shows = new ArrayList<>();
    Show playing;
    long playingSince = -1;
    private final Map<String, String> labels = new HashMap<>();

    private interface Bucket {
        void add(int i, long ms);
    }

    static WatchStats load(Context c) {
        WatchStats s = new WatchStats();
        WatchLog log = WatchLog.get(c);
        s.tracking = log.allowed();
        log.sync();
        s.compute(c, log);
        return s;
    }

    private void compute(Context c, WatchLog log) {
        ZoneId zone = ZoneId.systemDefault();
        now = System.currentTimeMillis();
        ZonedDateTime midnight = LocalDate.now(zone).atStartOfDay(zone);
        ZonedDateTime monday = midnight.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        todayStart = midnight.toInstant().toEpochMilli();
        weekStart = monday.toInstant().toEpochMilli();
        long[] hourEdges = new long[25], dayEdges = new long[8];
        for (int i = 0; i <= 24; i++) hourEdges[i] = midnight.plusHours(i).toInstant().toEpochMilli();
        for (int i = 0; i <= 7; i++) dayEdges[i] = monday.plusDays(i).toInstant().toEpochMilli();
        for (int i = 0; i < 24; i++) hourApps.add(new HashMap<>());
        for (int i = 0; i < 7; i++) dayApps.add(new HashMap<>());
        since = log.firstRecord();

        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        Map<String, List<long[]>> appSpans = new HashMap<>();
        for (WatchLog.Span span : log.apps(weekStart, now)) {
            long a = Math.max(span.start, weekStart), b = Math.min(span.end, now);
            if (b <= a) continue;
            if (SCREENSAVERS.contains(span.pkg)) {
                screensaverToday += overlap(a, b, todayStart, now);
                continue;
            }
            if (NOT_WATCHING.contains(span.pkg)) continue;
            appSpans.computeIfAbsent(span.pkg, k -> new ArrayList<>()).add(new long[]{a, b});
            App app = byPkg.get(span.pkg);
            if (app == null) byPkg.put(span.pkg, app = new App(span.pkg, label(pm, span.pkg), color(span.pkg, byPkg.size())));
            App fin = app;
            app.week += b - a;
            app.today += overlap(a, b, todayStart, now);
            if (span.start >= weekStart) app.opens++;
            spread(a, b, dayEdges, (i, ms) -> dayApps.get(i).merge(fin.pkg, ms, Long::sum));
            spread(a, b, hourEdges, (i, ms) -> hourApps.get(i).merge(fin.pkg, ms, Long::sum));
        }
        apps.addAll(byPkg.values());
        apps.removeIf(x -> x.week < MIN);
        apps.sort((x, y) -> x.today != y.today ? Long.compare(y.today, x.today) : Long.compare(y.week, x.week));
        for (App app : apps) {
            watchedToday += app.today;
            watchedWeek += app.week;
        }

        for (WatchLog.Span span : log.screens(weekStart, now)) {
            long a = Math.max(span.start, weekStart), b = Math.min(span.end, now);
            if (b <= a) continue;
            screenWeek += b - a;
            screenToday += overlap(a, b, todayStart, now);
            if (b > todayStart && (firstOnToday < 0 || Math.max(a, todayStart) < firstOnToday)) {
                firstOnToday = Math.max(a, todayStart);
            }
            spread(a, b, dayEdges, (i, ms) -> dayOn[i] += ms);
            spread(a, b, hourEdges, (i, ms) -> hourOn[i] += ms);
        }

        loadShows(log, pm, byPkg, appSpans);
    }

    // Shows come from three places: plays with a title (YouTube, JioHotstar, Sony LIV); time an app
    // played without saying what (Netflix, and Prime Video, which gives its own name), matched to the
    // Continue watching rows it touched; and Continue watching rows on their own, for their progress.
    private void loadShows(WatchLog log, PackageManager pm, Map<String, App> byPkg, Map<String, List<long[]>> appSpans) {
        Map<String, Show> byTitle = new LinkedHashMap<>();
        List<WatchLog.Play> live = MediaListener.playing();
        List<WatchLog.Play> plays = new ArrayList<>(log.plays(weekStart));
        plays.addAll(live);
        Map<String, List<WatchLog.Play>> untitled = new HashMap<>();
        for (WatchLog.Play p : plays) {
            if (untitled(pm, p)) untitled.computeIfAbsent(p.pkg, k -> new ArrayList<>()).add(p);
            else add(byTitle, p.pkg, p.title, p.subtitle, Math.max(p.start, weekStart), p.end);
        }

        List<WatchLog.Program> current = log.snapshotWatchNext();
        Map<String, List<WatchLog.Program>> touches = new HashMap<>();
        List<WatchLog.Program> all = new ArrayList<>(log.watchNext(weekStart - MATCH_MS));
        all.addAll(current);
        all.sort((x, y) -> Long.compare(x.engaged, y.engaged));
        for (WatchLog.Program p : all) touches.computeIfAbsent(p.pkg, k -> new ArrayList<>()).add(p);
        for (Map.Entry<String, List<WatchLog.Program>> e : touches.entrySet()) {
            String pkg = e.getKey(), other = "Other on " + appName(pm, pkg);
            for (long[] t : untitledTime(pkg, untitled.get(pkg), plays, appSpans.get(pkg))) {
                attribute(byTitle, pkg, t[0], t[1], e.getValue(), other);
            }
        }
        byTitle.values().removeIf(s -> s.ms < MIN_PLAY_MS);

        for (WatchLog.Program p : current) {
            Show s = byTitle.get(key(p.pkg, p.title));
            if (s == null) {
                byTitle.put(key(p.pkg, p.title), s = new Show(p.pkg, p.title));
                s.last = p.engaged;
            }
            if (s.subtitle == null) s.subtitle = p.episode;
            s.progress = p.position > 0 && p.duration > 0 ? Math.min(1f, p.position / (float) p.duration) : -1f;
        }

        for (Show s : byTitle.values()) {
            App app = byPkg.get(s.pkg);
            s.app = app != null ? app.name : appName(pm, s.pkg);
            s.color = app != null ? app.color : color(s.pkg, SPARE.length - 1);
            shows.add(s);
        }
        shows.sort((x, y) -> Long.compare(y.last, x.last));
        while (shows.size() > MAX_SHOWS) shows.remove(shows.size() - 1);

        WatchLog.Play newest = null;
        for (WatchLog.Play p : live) if (newest == null || p.start > newest.start) newest = p;
        if (newest != null) {
            String title = newest.title;
            if (untitled(pm, newest)) {
                // Whatever the app touched last in Continue watching, if that was today.
                List<WatchLog.Program> t = touches.get(newest.pkg);
                WatchLog.Program last = t == null || t.isEmpty() ? null : t.get(t.size() - 1);
                title = last != null && now - last.engaged < 6 * HOUR ? last.title : appName(pm, newest.pkg);
            }
            playing = byTitle.get(key(newest.pkg, title));
            if (playing == null) {
                playing = new Show(newest.pkg, title);
                playing.subtitle = newest.subtitle;
                App app = byPkg.get(newest.pkg);
                playing.app = app != null ? app.name : appName(pm, newest.pkg);
                playing.color = app != null ? app.color : color(newest.pkg, 0);
            }
            playingSince = newest.start;
        }
    }

    // No title, or the app's own name where the title should be (Prime Video).
    private boolean untitled(PackageManager pm, WatchLog.Play p) {
        return p.title.isEmpty() || squash(p.title).equals(squash(appName(pm, p.pkg)));
    }

    // An app's time with no title: its untitled plays, plus its time in the app wherever none of its
    // plays was recorded (before untitled plays were kept, or an app that never reports playing).
    private List<long[]> untitledTime(String pkg, List<WatchLog.Play> untitled, List<WatchLog.Play> plays, List<long[]> spans) {
        List<long[]> out = new ArrayList<>();
        if (untitled != null) for (WatchLog.Play p : untitled) out.add(new long[]{Math.max(p.start, weekStart), p.end});
        if (spans == null) return out;
        for (long[] s : spans) {
            boolean covered = false;
            for (WatchLog.Play p : plays) {
                if (p.pkg.equals(pkg) && p.end > s[0] && p.start < s[1]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(s);
        }
        return out;
    }

    // Splits [a, b) between the titles the app touched in Continue watching (it updates a row when
    // you stop or move on): time up to each touch goes to that title. What's left after the last
    // touch goes to the next touch within MATCH_MS, else the last one touched; a stretch with no
    // touch inside goes to the touch just before it, if that was within MATCH_MS, else to `other`.
    private static void attribute(Map<String, Show> byTitle, String pkg, long a, long b,
            List<WatchLog.Program> touches, String other) {
        if (b - a < 5_000) return;
        long cursor = a;
        WatchLog.Program before = null, hit = null, after = null;
        for (WatchLog.Program p : touches) {
            if (p.engaged <= a) {
                before = p;
            } else if (p.engaged < b) {
                if (p.engaged > cursor) {
                    add(byTitle, pkg, p.title, p.episode, cursor, p.engaged);
                    cursor = p.engaged;
                }
                hit = p;
            } else {
                if (p.engaged - b <= MATCH_MS) after = p;
                break;
            }
        }
        WatchLog.Program rest = after != null ? after : hit != null ? hit
                : before != null && a - before.engaged <= MATCH_MS ? before : null;
        if (rest != null) add(byTitle, pkg, rest.title, rest.episode, cursor, b);
        else add(byTitle, pkg, other, null, cursor, b);
    }

    private static void add(Map<String, Show> byTitle, String pkg, String title, String subtitle, long a, long b) {
        if (b <= a) return;
        Show s = byTitle.get(key(pkg, title));
        if (s == null) byTitle.put(key(pkg, title), s = new Show(pkg, title));
        s.ms += b - a;
        if (b >= s.last) {
            s.last = b;
            if (subtitle != null) s.subtitle = subtitle;
        }
    }

    private static String key(String pkg, String title) {
        return pkg + "\n" + title.toLowerCase(Locale.ROOT);
    }

    private String appName(PackageManager pm, String pkg) {
        return labels.computeIfAbsent(pkg, p -> label(pm, p));
    }

    // ---------- Views of the numbers ----------

    // Top `n` apps watched today, for the glance and the Apps card.
    List<App> todayApps(int n) {
        List<App> out = new ArrayList<>();
        for (App a : apps) if (a.today >= MIN && out.size() < n) out.add(a);
        return out;
    }

    App mostThisWeek() {
        App best = null;
        for (App a : apps) if (best == null || a.week > best.week) best = a;
        return best;
    }

    List<BarsView.Bar> hourBars() {
        return bars(hourOn, hourApps);
    }

    List<BarsView.Bar> dayBars() {
        return bars(dayOn, dayApps);
    }

    private List<BarsView.Bar> bars(long[] on, List<Map<String, Long>> watched) {
        List<BarsView.Bar> out = new ArrayList<>();
        for (int i = 0; i < on.length; i++) {
            List<Long> values = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (App a : apps) {
                Long ms = watched.get(i).get(a.pkg);
                if (ms == null || ms < MIN / 2) continue;
                values.add(ms);
                colors.add(a.color);
            }
            out.add(new BarsView.Bar(on[i], values, colors));
        }
        return out;
    }

    // Hour of the day (0–23) or day of the week (0 = Monday) with the most watching, or -1.
    int busiestHour() {
        return busiest(hourApps);
    }

    int busiestDay() {
        return busiest(dayApps);
    }

    long watchedIn(List<Map<String, Long>> buckets, int i) {
        long sum = 0;
        for (long ms : buckets.get(i).values()) sum += ms;
        return sum;
    }

    private int busiest(List<Map<String, Long>> buckets) {
        int best = -1;
        long most = 0;
        for (int i = 0; i < buckets.size(); i++) {
            long sum = watchedIn(buckets, i);
            if (sum > most) {
                most = sum;
                best = i;
            }
        }
        return best;
    }

    // Days of this week with the TV on, for "daily average".
    int daysOn() {
        int n = 0;
        for (long ms : dayOn) if (ms > 0) n++;
        return Math.max(1, n);
    }

    // "Thu 15 – Sun 18 Oct": this week, from the first day there's a record of.
    String weekRange() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate start = Instant.ofEpochMilli(Math.max(weekStart, since)).atZone(zone).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault());
        DateTimeFormatter full = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault());
        if (start.equals(end)) return end.format(full);
        return (start.getMonth() == end.getMonth() ? start.format(day) : start.format(full)) + " – " + end.format(full);
    }

    static String duration(long ms) {
        long m = ms / MIN;
        if (m < 60) return m + "m";
        return (m / 60) + "h " + String.format(Locale.US, "%02dm", m % 60);
    }

    static String hourName(int h) {
        int twelve = h % 12 == 0 ? 12 : h % 12;
        return twelve + (h < 12 ? " AM" : " PM");
    }

    // "● YouTube  1h 05m    ● Netflix  23m", dots in each app's colour, times dimmed.
    static CharSequence legend(List<App> apps, boolean today) {
        if (apps.isEmpty()) return today ? "Nothing watched yet today" : "Nothing watched yet this week";
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (App a : apps) {
            if (b.length() > 0) b.append("    ");
            b.append(dotted(a.name, a.color)).append("  ");
            int time = b.length();
            b.append(duration(today ? a.today : a.week));
            b.setSpan(new ForegroundColorSpan(0x80FFFFFF), time, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    // "● YouTube" with the dot in the app's colour.
    static CharSequence dotted(String name, int color) {
        SpannableStringBuilder b = new SpannableStringBuilder("● ").append(name);
        b.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new RelativeSizeSpan(0.8f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    static String hex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    // ---------- JSON for the web remote ----------

    JSONObject json() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("tracking", tracking);
        o.put("at", now);
        o.put("since", since);
        o.put("todayStart", todayStart);
        o.put("weekStart", weekStart);
        o.put("weekRange", weekRange());
        JSONObject today = new JSONObject();
        today.put("watched", watchedToday);
        today.put("screen", screenToday);
        today.put("screensaver", screensaverToday);
        today.put("firstOn", firstOnToday);
        today.put("busiestHour", busiestHour());
        o.put("today", today);
        JSONObject week = new JSONObject();
        week.put("watched", watchedWeek);
        week.put("screen", screenWeek);
        week.put("busiestDay", busiestDay());
        week.put("daysOn", daysOn());
        o.put("week", week);
        o.put("hours", buckets(hourOn, hourApps));
        o.put("days", buckets(dayOn, dayApps));
        JSONArray appList = new JSONArray();
        for (App a : apps) {
            appList.put(new JSONObject().put("pkg", a.pkg).put("name", a.name).put("color", hex(a.color))
                    .put("today", a.today).put("week", a.week).put("opens", a.opens));
        }
        o.put("apps", appList);
        JSONArray showList = new JSONArray();
        for (Show s : shows) showList.put(show(s));
        o.put("shows", showList);
        o.put("playing", playing == null ? JSONObject.NULL : show(playing).put("since", playingSince));
        return o;
    }

    private static JSONArray buckets(long[] on, List<Map<String, Long>> watched) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < on.length; i++) {
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, Long> e : watched.get(i).entrySet()) apps.put(e.getKey(), e.getValue());
            out.put(new JSONObject().put("on", on[i]).put("apps", apps));
        }
        return out;
    }

    private static JSONObject show(Show s) throws JSONException {
        return new JSONObject().put("pkg", s.pkg).put("app", s.app).put("title", s.title)
                .put("subtitle", s.subtitle == null ? JSONObject.NULL : s.subtitle).put("color", hex(s.color))
                .put("ms", s.ms).put("last", s.last).put("progress", s.progress);
    }

    // ---------- Helpers ----------

    private static long overlap(long a, long b, long from, long to) {
        return Math.max(0, Math.min(b, to) - Math.max(a, from));
    }

    private static void spread(long a, long b, long[] edges, Bucket add) {
        for (int i = 0; i + 1 < edges.length; i++) {
            long ms = overlap(a, b, edges[i], edges[i + 1]);
            if (ms > 0) add.add(i, ms);
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // "Prime Video" and "PrimeVideo" alike: letters and digits only, lower case.
    private static String squash(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private static int color(String pkg, int index) {
        Integer c = COLORS.get(pkg);
        return c != null ? c : SPARE[index % SPARE.length];
    }
}
