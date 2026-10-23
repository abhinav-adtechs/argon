package com.argon.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvContract.PreviewPrograms;
import android.media.tv.TvContract.WatchNextPrograms;
import android.text.TextUtils;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Reads the "continue watching" (Watch Next) and channel (preview) programs that TV apps publish to
// the system TV provider. Needs READ_TV_LISTINGS; without it the provider returns nothing.
final class Shows {
    private static final String TAG = "Shows";
    private static final int MAX_CONTINUE = 20, MAX_RECOMMENDED = 24;
    private static final String PACKAGE = TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME;
    private static final String[] COMMON = {
            PACKAGE, WatchNextPrograms.COLUMN_TITLE, WatchNextPrograms.COLUMN_EPISODE_TITLE,
            WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            WatchNextPrograms.COLUMN_POSTER_ART_URI, WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            WatchNextPrograms.COLUMN_THUMBNAIL_URI, WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            WatchNextPrograms.COLUMN_INTENT_URI, WatchNextPrograms.COLUMN_BROWSABLE};

    static final class Show {
        final String pkg, title, subtitle, image;
        final Intent intent;
        final float progress;
        final Drawable badge;

        Show(String pkg, String title, String subtitle, String image, Intent intent, float progress, Drawable badge) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.intent = intent;
            this.progress = progress;
            this.badge = badge;
        }

        String key() {
            return pkg + "\n" + title + "\n" + subtitle;
        }

        String label() {
            return TextUtils.isEmpty(subtitle) ? title : title + "  ·  " + subtitle;
        }
    }

    private final Context context;
    private final PackageManager pm;
    private final Map<String, Drawable> badges = new HashMap<>();

    Shows(Context context) {
        this.context = context;
        pm = context.getPackageManager();
    }

    List<Show> continueWatching() {
        String[] projection = concat(COMMON, WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, WatchNextPrograms.COLUMN_DURATION_MILLIS,
                WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
        List<Show> shows = new ArrayList<>();
        List<Long> engaged = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(WatchNextPrograms.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE))
                        == WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue;
                long pos = c.getLong(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS));
                long dur = c.getLong(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_DURATION_MILLIS));
                Show s = read(c, pos > 0 && dur > 0 ? Math.min(1f, pos / (float) dur) : -1f);
                if (s == null) continue;
                // Newest first, by insertion into the sorted position.
                long t = c.getLong(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS));
                int at = 0;
                while (at < engaged.size() && engaged.get(at) >= t) at++;
                engaged.add(at, t);
                shows.add(at, s);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next query failed", e);
        }
        return new ArrayList<>(shows.subList(0, Math.min(MAX_CONTINUE, shows.size())));
    }

    // Channel programs, interleaved app by app (top-row apps first) so no single app fills the row.
    List<Show> recommended(List<String> preferredPkgs, List<Show> exclude) {
        Set<String> seen = new HashSet<>();
        for (Show s : exclude) seen.add(s.pkg + "\n" + s.title);
        Map<String, List<Show>> byPkg = new LinkedHashMap<>();
        for (String pkg : preferredPkgs) byPkg.put(pkg, new ArrayList<>());
        try (Cursor c = context.getContentResolver().query(PreviewPrograms.CONTENT_URI, COMMON, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Show s = read(c, -1f);
                if (s == null || !seen.add(s.pkg + "\n" + s.title)) continue;
                List<Show> list = byPkg.get(s.pkg);
                if (list == null) byPkg.put(s.pkg, list = new ArrayList<>());
                list.add(s);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview query failed", e);
        }
        List<Show> out = new ArrayList<>();
        for (int round = 0; out.size() < MAX_RECOMMENDED; round++) {
            boolean any = false;
            for (List<Show> list : byPkg.values()) {
                if (round < list.size() && out.size() < MAX_RECOMMENDED) {
                    out.add(list.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }

    private Show read(Cursor c, float progress) {
        if (c.getInt(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_BROWSABLE)) == 0) return null;
        String pkg = c.getString(c.getColumnIndexOrThrow(PACKAGE));
        String title = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_TITLE));
        if (pkg == null || TextUtils.isEmpty(title)) return null;
        Drawable badge = badge(pkg);
        Intent intent = intentFor(pkg, c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_INTENT_URI)));
        if (badge == null || intent == null) return null;

        String season = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER));
        String episode = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER));
        String episodeTitle = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_EPISODE_TITLE));
        StringBuilder sub = new StringBuilder();
        if (!TextUtils.isEmpty(season)) sub.append('S').append(season);
        if (!TextUtils.isEmpty(episode)) sub.append(sub.length() > 0 ? " E" : "E").append(episode);
        if (!TextUtils.isEmpty(episodeTitle)) sub.append(sub.length() > 0 ? " " : "").append(episodeTitle);
        return new Show(pkg, title, sub.toString(), image(c), intent, progress, badge);
    }

    // Prefer whichever artwork is closest to the 16:9 cards.
    private static String image(Cursor c) {
        String poster = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_POSTER_ART_URI));
        String thumb = c.getString(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_THUMBNAIL_URI));
        int posterRatio = c.getInt(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO));
        int thumbRatio = c.getInt(c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO));
        boolean posterWide = posterRatio <= WatchNextPrograms.ASPECT_RATIO_4_3;
        boolean thumbWide = thumbRatio <= WatchNextPrograms.ASPECT_RATIO_4_3;
        if (!TextUtils.isEmpty(poster) && (posterWide || TextUtils.isEmpty(thumb) || !thumbWide)) return poster;
        return TextUtils.isEmpty(thumb) ? null : thumb;
    }

    private Drawable badge(String pkg) {
        if (badges.containsKey(pkg)) return badges.get(pkg);
        Drawable d = null;
        if (pm.getLeanbackLaunchIntentForPackage(pkg) != null) {
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        badges.put(pkg, d);
        return d;
    }

    // Only lets a program open its own app, and never with URI grants.
    private static Intent intentFor(String pkg, String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) return null;
            if (i.getPackage() != null && !pkg.equals(i.getPackage())) return null;
            if (i.getComponent() == null) i.setPackage(pkg);
            i.setSelector(null);
            i.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
