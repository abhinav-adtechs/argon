package com.argon.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Home screen settings the web remote can change (through ControlReceiver). Each one falls back to
// res/values/config.xml until it has been set once.
final class Config {
    static final String DOCK = "dock", TEAM = "football_team", AMBIENT = "ambient_volume", WEATHER = "weather",
            WIDGETS = "widgets", WALLPAPER = "wallpaper";
    /** The Welcome hub's widgets, in order: id and size (S or L); a widget left out is off. */
    static final String DEFAULT_WIDGETS = "continue:L,weather:S,match:S,inputs:S,living:S,foryou:L,watch:L,shows:L,movies:S";

    private Config() {
    }

    // The shelf lives in prefs once edited; a changed dock_packages in config.xml resets it.
    static List<String> dock(Context c, SharedPreferences p) {
        String seed = TextUtils.join(",", c.getResources().getStringArray(R.array.dock_packages));
        if (!seed.equals(p.getString("dock_seed", null))) {
            p.edit().putString("dock_seed", seed).putString(DOCK, seed).apply();
        }
        return new ArrayList<>(Arrays.asList(TextUtils.split(p.getString(DOCK, seed), ",")));
    }

    static void saveDock(SharedPreferences p, List<String> pkgs) {
        p.edit().putString(DOCK, TextUtils.join(",", pkgs)).apply();
    }

    static String team(Context c, SharedPreferences p) {
        return p.getString(TEAM, c.getString(R.string.football_team)).trim();
    }

    static int ambient(Context c, SharedPreferences p) {
        return p.getInt(AMBIENT, c.getResources().getInteger(R.integer.ambient_volume));
    }

    // "place|latitude|longitude"; empty = Bengaluru (Weather.DEFAULT_PLACE).
    static String weather(SharedPreferences p) {
        return p.getString(WEATHER, "");
    }

    static String widgets(SharedPreferences p) {
        String v = p.getString(WIDGETS, "").trim();
        return v.isEmpty() ? DEFAULT_WIDGETS : v;
    }

    // "waves" (drawn), "pick" (this hour's top pick) or "photo:<stamp>" (files/wallpaper.jpg, sent from the remote).
    static String wallpaper(SharedPreferences p) {
        String v = p.getString(WALLPAPER, "").trim();
        return v.isEmpty() ? "waves" : v;
    }
}
