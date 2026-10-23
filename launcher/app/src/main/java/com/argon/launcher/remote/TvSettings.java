package com.argon.launcher.remote;

import static com.argon.launcher.remote.Shell.quote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The Settings and Apps pages.
final class TvSettings {
    private TvSettings() {
    }

    private static final class Def {
        final String ns, key, kind;

        Def(String ns, String key, String kind) {
            this.ns = ns;
            this.key = key;
            this.kind = kind;
        }
    }

    // The Android settings the web page may change. Anything else stays read-only.
    private static final Map<String, Def> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("soundEffects", new Def("system", "sound_effects_enabled", "bool"));
        SETTINGS.put("cecOneTouch", new Def("global", "hdmi_cec_one_touch_play_enabled", "bool"));
        SETTINGS.put("fontScale", new Def("system", "font_scale", "float"));
        SETTINGS.put("screensaver", new Def("secure", "screensaver_enabled", "bool"));
        // On Android TV the screen-off timeout is when the screen saver starts; sleep_timeout is when it powers down.
        SETTINGS.put("screensaverAfter", new Def("system", "screen_off_timeout", "int"));
        SETTINGS.put("sleepAfter", new Def("secure", "sleep_timeout", "int"));
        SETTINGS.put("screensaverComponent", new Def("secure", "screensaver_components", "str"));
        SETTINGS.put("autoTime", new Def("global", "auto_time", "bool"));
        SETTINGS.put("time24", new Def("system", "time_12_24", "str"));
        SETTINGS.put("windowAnim", new Def("global", "window_animation_scale", "float"));
        SETTINGS.put("transitionAnim", new Def("global", "transition_animation_scale", "float"));
        SETTINGS.put("animatorAnim", new Def("global", "animator_duration_scale", "float"));
        SETTINGS.put("highContrast", new Def("secure", "high_text_contrast_enabled", "bool"));
        SETTINGS.put("captions", new Def("secure", "accessibility_captioning_enabled", "bool"));
        SETTINGS.put("bluetooth", new Def("global", "bluetooth_on", "bool"));
    }

    // Pages of the TV's own Settings app, for "Open on TV".
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry("all", "-a android.settings.SETTINGS"),
            Map.entry("picture", "-a android.settings.DISPLAY_SETTINGS"),
            Map.entry("sound", "-a android.settings.SOUND_SETTINGS"),
            Map.entry("network", "-a android.settings.WIFI_SETTINGS"),
            Map.entry("accounts", "-a android.settings.SYNC_SETTINGS"),
            Map.entry("apps", "-a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"),
            Map.entry("datetime", "-a android.settings.DATE_SETTINGS"),
            Map.entry("language", "-a android.settings.LOCALE_SETTINGS"),
            Map.entry("keyboard", "-a android.settings.INPUT_METHOD_SETTINGS"),
            Map.entry("accessibility", "-a android.settings.ACCESSIBILITY_SETTINGS"),
            Map.entry("captions", "-a android.settings.CAPTIONING_SETTINGS"),
            Map.entry("about", "-a android.settings.DEVICE_INFO_SETTINGS"),
            Map.entry("storage", "-a android.settings.INTERNAL_STORAGE_SETTINGS"),
            Map.entry("security", "-a android.settings.SECURITY_SETTINGS"),
            Map.entry("developer", "-a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Map.entry("location", "-a android.settings.LOCATION_SOURCE_SETTINGS"),
            Map.entry("remotes", "-n com.android.tv.settings/.accessories.AddAccessoryActivity"),
            Map.entry("energy", "-n com.android.tv.settings/.device.eco.EnergyModesActivity"));

    private static Object parse(String kind, String raw) {
        if (raw == null || raw.equals("null")) return null;
        switch (kind) {
            case "bool":
                return raw.equals("1");
            case "int":
            case "float":
                try {
                    return Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    return null;
                }
            default:
                return raw;
        }
    }

    private static Map<String, String> table(String t) {
        Map<String, String> m = new HashMap<>();
        for (String l : t.split("\n")) {
            int i = l.indexOf('=');
            if (i > 0) m.put(l.substring(0, i), l.substring(i + 1));
        }
        return m;
    }

    private static List<String> lines(String t) {
        List<String> out = new ArrayList<>();
        for (String l : t.trim().split("\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    private static final Pattern BONDED = Pattern.compile("^([0-9A-F:]{17}) \\[\\s*(\\w+)\\s*\\] (.+)$");

    static JSONObject read(Shell sh) {
        String out = sh.run("settings list global; echo @@; settings list secure; echo @@; settings list system; echo @@; "
                + "cmd package query-services --brief -a android.service.dreams.DreamService | grep /; echo @@; "
                + "dumpsys bluetooth_manager | grep -A12 'Bonded devices:' | tail -n +2 | grep -E '^ +[0-9A-F:]{17}'", 8000);
        String[] parts = out.split("@@\n", -1);
        if (parts.length < 5) return null;
        Map<String, Map<String, String>> all = Map.of("global", table(parts[0]), "secure", table(parts[1]),
                "system", table(parts[2]));
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Def> e : SETTINGS.entrySet())
            Json.put(values, e.getKey(), parse(e.getValue().kind, all.get(e.getValue().ns).get(e.getValue().key)));
        JSONArray bluetooth = new JSONArray();
        for (String l : lines(parts[4])) {
            Matcher m = BONDED.matcher(l);
            if (m.matches()) bluetooth.put(Json.obj("address", m.group(1), "type", m.group(2), "name", m.group(3)));
        }
        return Json.obj("values", values, "dreams", new JSONArray(lines(parts[3])), "bluetooth", bluetooth);
    }

    static JSONObject write(Shell sh, String id, Object value) {
        Def d = SETTINGS.get(id);
        if (d == null || d.key.equals("bluetooth_on")) return Json.obj("ok", false, "error", "Not changeable");
        String v;
        if (d.kind.equals("bool")) v = truthy(value) ? "1" : "0";
        else if (d.kind.equals("str")) v = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        else {
            double n;
            try {
                n = Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                n = Double.NaN;
            }
            if (!Double.isFinite(n)) return Json.obj("ok", false, "error", "Not a number");
            v = d.kind.equals("int") ? String.valueOf(Math.round(n)) : number(n);
        }
        String out = sh.run("settings put " + d.ns + " " + d.key + " " + quote(v)).trim();
        return out.isEmpty() ? Json.obj("ok", true) : Json.obj("ok", false, "error", out);
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return false;
    }

    // Like JavaScript's String(n): 1.5 stays 1.5, 2.0 is written 2.
    static String number(double n) {
        return n == Math.rint(n) && Math.abs(n) < 1e15 ? String.valueOf((long) n) : String.valueOf(n);
    }

    static JSONObject openPage(Shell sh, String page) {
        String intent = PAGES.get(page);
        if (intent == null) return Json.obj("ok", false, "error", "Unknown page");
        String out = sh.run("am start " + intent);
        return out.contains("Error") || out.contains("Exception") ? Json.obj("ok", false, "error", lastLine(out))
                : Json.obj("ok", true);
    }

    private static String lastLine(String s) {
        String[] l = s.trim().split("\n");
        return l[l.length - 1];
    }

    // Apps: third-party and disabled packages, with labels from the launcher where it knows them.
    static JSONArray listApps(Shell sh, Map<String, String> labels) {
        String out = sh.run("pm list packages -3; echo @@; pm list packages -d; echo @@; pm list packages -s", 8000);
        String[] parts = out.split("@@\n", -1);
        if (parts.length < 3) return null;
        Set<String> off = new HashSet<>(pkgs(parts[1])), sys = new HashSet<>(pkgs(parts[2]));
        Set<String> seen = new LinkedHashSet<>(pkgs(parts[0]));
        seen.addAll(off);
        seen.addAll(labels.keySet());
        List<JSONObject> apps = new ArrayList<>();
        for (String pkg : seen)
            apps.add(Json.obj("pkg", pkg, "label", labels.getOrDefault(pkg, ""), "enabled", !off.contains(pkg),
                    "system", sys.contains(pkg), "launchable", labels.containsKey(pkg)));
        apps.sort((a, b) -> sortName(a).compareToIgnoreCase(sortName(b)));
        return new JSONArray(apps);
    }

    private static String sortName(JSONObject a) {
        String l = a.optString("label");
        return l.isEmpty() ? a.optString("pkg") : l;
    }

    private static List<String> pkgs(String t) {
        List<String> out = new ArrayList<>();
        for (String l : t.split("\n")) {
            String p = l.replaceFirst("^package:", "").trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_.]+$");

    static JSONObject appAction(Shell sh, String pkg, String action) {
        if (!PKG.matcher(pkg).matches()) return Json.obj("ok", false, "error", "Bad package name");
        String cmd;
        switch (action) {
            case "stop": cmd = "am force-stop " + pkg; break;
            case "cache": cmd = "pm clear --cache-only " + pkg; break;
            case "disable": cmd = "pm disable-user --user 0 " + pkg; break;
            case "enable": cmd = "pm enable --user 0 " + pkg; break;
            case "info": cmd = "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + pkg; break;
            case "open": cmd = "monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1"; break;
            default: return Json.obj("ok", false, "error", "Unknown action");
        }
        String out = sh.run(cmd, 15000);
        return out.contains("Error") || out.contains("Exception") || out.contains("Failure")
                ? Json.obj("ok", false, "error", lastLine(out)) : Json.obj("ok", true);
    }

    static JSONObject power(Shell sh, String action) {
        if (action.equals("screensaver")) sh.run("cmd dreams start-dreaming");
        else if (action.equals("reboot")) sh.run("svc power reboot || reboot", 3000);
        else return Json.obj("ok", false, "error", "Unknown action");
        return Json.obj("ok", true);
    }
}
