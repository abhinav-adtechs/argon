package com.argon.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.tv.TvContract;
import android.media.tv.TvContract.WatchNextPrograms;
import android.os.Process;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// What was on screen, kept in the launcher's own database (databases/watch.db) because the system
// keeps only a few days of usage events. App and screen time are rebuilt from those events in
// batches, whenever stats are read; plays with titles come from MediaListener as they happen.
final class WatchLog extends SQLiteOpenHelper {
    private static final long BACKFILL_MS = 7L * 24 * 3600_000;
    // Screen state before the first event ever read: assume on from the first app change.
    private static final long UNKNOWN = -2;
    private static WatchLog instance;

    static final class Span {
        final String pkg;
        final long start, end;

        Span(String pkg, long start, long end) {
            this.pkg = pkg;
            this.start = start;
            this.end = end;
        }
    }

    // `title` is "" when the app played without saying what (Netflix).
    static final class Play {
        final String pkg, title, subtitle;
        final long start, end;

        Play(String pkg, String title, String subtitle, long start, long end) {
            this.pkg = pkg;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle;
            this.start = start;
            this.end = end;
        }
    }

    // One state of an app's Continue watching row: what, how far in, and when the app last touched it.
    static final class Program {
        final String pkg, title, episode;
        final long position, duration, engaged;

        Program(String pkg, String title, String episode, long position, long duration, long engaged) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.position = position;
            this.duration = duration;
            this.engaged = engaged;
        }
    }

    private final Context context;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    static synchronized WatchLog get(Context c) {
        if (instance == null) instance = new WatchLog(c.getApplicationContext());
        return instance;
    }

    static synchronized boolean started() {
        return instance != null;
    }

    private WatchLog(Context c) {
        super(c, "watch.db", null, 2);
        context = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app (pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX app_end ON app (end)");
        db.execSQL("CREATE TABLE screen (start INTEGER NOT NULL, end INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX screen_end ON screen (end)");
        db.execSQL("CREATE TABLE play (pkg TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT,"
                + " start INTEGER NOT NULL, end INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX play_end ON play (end)");
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, num INTEGER, text TEXT)");
        createWatchNext(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) createWatchNext(db);
    }

    // Version 2: snapshots of Continue watching rows, to tell which title an app's untitled time went to.
    private static void createWatchNext(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE watchnext (pkg TEXT NOT NULL, title TEXT NOT NULL, episode TEXT,"
                + " position INTEGER NOT NULL, duration INTEGER NOT NULL, engaged INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX watchnext_key ON watchnext (pkg, title, engaged)");
    }

    // Usage access is granted with appops (tools/build_install.sh); without it there's nothing to read.
    boolean allowed() {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    // Turns the usage events since the last call into closed app and screen spans. The span still
    // going on is kept in `state` and added by the readers below.
    synchronized void sync() {
        if (!allowed()) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        long last = num(db, "last", -1);
        long from = last < 0 ? now - BACKFILL_MS : last + 1;
        String cur = text(db, "cur");
        long curSince = num(db, "cur_since", -1);
        long screenSince = last < 0 ? UNKNOWN : num(db, "screen_since", -1);
        long seen = from - 1;

        UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        db.beginTransaction();
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                long t = e.getTimeStamp();
                switch (e.getEventType()) {
                    case UsageEvents.Event.ACTIVITY_RESUMED: {
                        String pkg = e.getPackageName();
                        if (screenSince == UNKNOWN) screenSince = t;
                        if (pkg.equals(cur)) break;
                        if (cur != null && curSince >= 0) insertApp(db, cur, curSince, t);
                        cur = pkg;
                        curSince = screenSince >= 0 ? t : -1;
                        break;
                    }
                    case UsageEvents.Event.DEVICE_STARTUP:
                        cur = null;
                        if (screenSince < 0) screenSince = t;
                        break;
                    case UsageEvents.Event.SCREEN_INTERACTIVE:
                        if (screenSince < 0) {
                            screenSince = t;
                            curSince = cur != null ? t : -1;
                        }
                        break;
                    case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                    case UsageEvents.Event.DEVICE_SHUTDOWN:
                        if (screenSince >= 0) insertScreen(db, screenSince, t);
                        if (cur != null && curSince >= 0) insertApp(db, cur, curSince, t);
                        screenSince = -1;
                        curSince = -1;
                        break;
                    default:
                        break;
                }
                seen = Math.max(seen, t);
            }
            put(db, "last", seen, null);
            put(db, "cur", 0, cur);
            put(db, "cur_since", curSince, null);
            put(db, "screen_since", screenSince, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // App spans that end after `from`, the one still going on included and cut at `now`.
    List<Span> apps(long from, long now) {
        SQLiteDatabase db = getReadableDatabase();
        List<Span> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT pkg, start, end FROM app WHERE end > ? ORDER BY start",
                new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) out.add(new Span(c.getString(0), c.getLong(1), c.getLong(2)));
        }
        String cur = text(db, "cur");
        long curSince = num(db, "cur_since", -1);
        if (cur != null && curSince >= 0 && now > curSince) out.add(new Span(cur, curSince, now));
        return out;
    }

    List<Span> screens(long from, long now) {
        SQLiteDatabase db = getReadableDatabase();
        List<Span> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT start, end FROM screen WHERE end > ? ORDER BY start",
                new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) out.add(new Span(null, c.getLong(0), c.getLong(1)));
        }
        long since = num(db, "screen_since", -1);
        if (since >= 0 && now > since) out.add(new Span(null, since, now));
        return out;
    }

    List<Play> plays(long from) {
        List<Play> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT pkg, title, subtitle, start, end FROM play WHERE end > ? ORDER BY start",
                new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) {
                out.add(new Play(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)));
            }
        }
        return out;
    }

    // Start of the oldest span kept, or -1 before anything has been recorded.
    long firstRecord() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MIN(start) FROM app", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
        }
    }

    // Kept Continue watching states touched after `from`, oldest first.
    List<Program> watchNext(long from) {
        List<Program> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT pkg, title, episode, position, duration, engaged FROM watchnext WHERE engaged > ? ORDER BY engaged",
                new String[]{String.valueOf(from)})) {
            while (c.moveToNext()) {
                out.add(new Program(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5)));
            }
        }
        return out;
    }

    // Reads the Continue watching rows apps publish now, keeps any state not seen before (in the
    // background) and returns them. Called when stats are read and, through MediaListener, whenever
    // an app changes its rows.
    List<Program> snapshotWatchNext() {
        List<Program> now = new ArrayList<>();
        String[] projection = {TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE,
                WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                WatchNextPrograms.COLUMN_EPISODE_TITLE, WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                WatchNextPrograms.COLUMN_DURATION_MILLIS, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE};
        try (Cursor c = context.getContentResolver().query(WatchNextPrograms.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (TextUtils.isEmpty(c.getString(0)) || TextUtils.isEmpty(c.getString(1)) || c.getLong(7) <= 0
                        || c.getInt(8) == WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                now.add(new Program(c.getString(0), c.getString(1), episode(c.getString(2), c.getString(3), c.getString(4)),
                        c.getLong(5), c.getLong(6), c.getLong(7)));
            }
        } catch (RuntimeException e) {
            return now;
        }
        if (now.isEmpty()) return now;
        writer.execute(() -> {
            SQLiteDatabase db = getWritableDatabase();
            for (Program p : now) {
                ContentValues v = new ContentValues();
                v.put("pkg", p.pkg);
                v.put("title", p.title);
                v.put("episode", p.episode);
                v.put("position", p.position);
                v.put("duration", p.duration);
                v.put("engaged", p.engaged);
                db.insertWithOnConflict("watchnext", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        });
        return now;
    }

    // "S2 E4 · Remedial Chaos Theory", or whichever parts the app gave.
    private static String episode(String season, String number, String title) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(season) && !TextUtils.isEmpty(number)) parts.add("S" + season + " E" + number);
        else if (!TextUtils.isEmpty(number)) parts.add("E" + number);
        if (!TextUtils.isEmpty(title)) parts.add(title);
        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }

    void play(String pkg, String title, String subtitle, long start, long end) {
        if (end <= start) return;
        writer.execute(() -> {
            ContentValues v = new ContentValues();
            v.put("pkg", pkg);
            v.put("title", title == null ? "" : title);
            v.put("subtitle", subtitle);
            v.put("start", start);
            v.put("end", end);
            getWritableDatabase().insert("play", null, v);
        });
    }

    private static void insertApp(SQLiteDatabase db, String pkg, long start, long end) {
        if (end <= start) return;
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("start", start);
        v.put("end", end);
        db.insert("app", null, v);
    }

    private static void insertScreen(SQLiteDatabase db, long start, long end) {
        if (end <= start) return;
        ContentValues v = new ContentValues();
        v.put("start", start);
        v.put("end", end);
        db.insert("screen", null, v);
    }

    private static long num(SQLiteDatabase db, String key, long fallback) {
        try (Cursor c = db.rawQuery("SELECT num FROM state WHERE key = ?", new String[]{key})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : fallback;
        }
    }

    private static String text(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT text FROM state WHERE key = ?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static void put(SQLiteDatabase db, String key, long num, String text) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("num", num);
        v.put("text", text);
        db.insertWithOnConflict("state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
