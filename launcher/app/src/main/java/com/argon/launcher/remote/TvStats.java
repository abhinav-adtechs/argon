package com.argon.launcher.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The Device page: memory, CPU, storage, temperature and Wi-Fi, read in one round trip.
final class TvStats {
    private static final String[] SECTIONS = {
            "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo",
            "head -1 /proc/stat",
            "cat /proc/uptime /proc/loadavg",
            "df -k /data /system | tail -n +2",
            "dumpsys thermalservice | grep -m1 'mName=CPU'",
            "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq",
            "cmd wifi status | grep -m1 WifiInfo",
    };
    private static final String TOP = "ps -A -o RSS,NAME --sort=-rss | head -9";
    private static final Pattern MEM = Pattern.compile("^(\\w+):\\s+(\\d+)", Pattern.MULTILINE);

    private long lastBusy = -1, lastTotal;
    private JSONObject info;

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // `full` adds the biggest processes (ps costs ~100 ms on the TV).
    synchronized JSONObject read(Shell sh, boolean full) {
        String cmds = String.join("; echo @@; ", SECTIONS) + (full ? "; echo @@; " + TOP : "");
        String out = sh.run(cmds, 4000);
        if (!out.contains("@@")) return null;
        String[] p = out.split("@@\n", -1);
        if (p.length < 7) return null;

        Map<String, Long> m = new HashMap<>();
        for (Matcher x = MEM.matcher(p[0]); x.find(); ) m.put(x.group(1), Long.parseLong(x.group(2)) * 1024);

        String[] f = p[1].trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length; i++) {
            long v = (long) num(f[i]);
            total += v;
            if (i == 4 || i == 5) idle += v;
        }
        double cpu = 0;
        if (lastBusy >= 0 && total > lastTotal) cpu = (double) (total - idle - lastBusy) / (total - lastTotal) * 100;
        lastBusy = total - idle;
        lastTotal = total;

        String[] up = p[2].trim().split("\\s+");
        JSONArray load = new JSONArray();
        for (int i = 2; i <= 4 && i < up.length; i++) load.put((Object) num(up[i]));

        JSONArray storage = new JSONArray();
        for (String l : p[3].trim().split("\n")) {
            String[] c = l.trim().split("\\s+");
            if (c.length < 6) continue;
            storage.put(Json.obj("name", c[5].equals("/") ? "System" : "Internal storage",
                    "total", (long) num(c[1]) * 1024, "used", (long) num(c[2]) * 1024));
        }

        JSONArray cores = new JSONArray();
        for (String l : p[5].trim().split("\n")) if (!l.trim().isEmpty()) cores.put(Math.round(num(l.trim()) / 1000));

        String wifi = p[6];
        String ssid = find(wifi, "SSID: \"([^\"]*)\""), ip = find(wifi, "IP: /([\\d.]+)");
        String temp = find(p[4], "mValue=([\\d.]+)");

        JSONArray top = null;
        if (full && p.length > 7) {
            top = new JSONArray();
            String[] rows = p[7].trim().split("\n");
            for (int i = 1; i < rows.length; i++) {
                String[] c = rows[i].trim().split("\\s+");
                if (c.length >= 2) top.put(Json.obj("name", c[1], "bytes", (long) num(c[0]) * 1024));
            }
        }

        return Json.obj(
                "at", System.currentTimeMillis(),
                "mem", Json.obj("total", m.get("MemTotal"), "available", m.get("MemAvailable"),
                        "swapTotal", m.get("SwapTotal"), "swapFree", m.get("SwapFree")),
                "cpu", Json.obj("usage", Math.round(cpu * 10) / 10.0, "cores", cores, "load", load),
                "temp", temp == null ? null : num(temp),
                "uptime", up.length > 0 ? num(up[0]) : 0,
                "storage", storage,
                "wifi", Json.obj("ssid", ssid == null ? "" : ssid,
                        "rssi", (long) num(find(wifi, "RSSI: (-?\\d+)")),
                        "link", (long) num(find(wifi, "Link speed: (\\d+)Mbps")),
                        "freq", (long) num(find(wifi, "Frequency: (\\d+)MHz")),
                        "ip", ip == null ? "" : ip),
                "top", top);
    }

    // Things that don't change while the TV is on; read once.
    synchronized JSONObject deviceInfo(Shell sh) {
        if (info != null) return info;
        String[] props = {"ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
                "ro.build.version.security_patch", "ro.build.display.id", "ro.board.platform",
                "persist.sys.timezone", "persist.sys.locale"};
        StringBuilder cmd = new StringBuilder();
        for (String p : props) cmd.append("getprop ").append(p).append("; ");
        cmd.append("uname -r; wm size | tail -1; wm density | tail -1");
        String[] l = sh.run(cmd.toString()).split("\n", -1);
        if (l.length < props.length + 3 || l[1].isEmpty()) return null;
        info = Json.obj("manufacturer", l[0], "model", l[1], "android", l[2], "patch", l[3], "build", l[4],
                "platform", l[5], "timezone", l[6], "locale", l[7], "kernel", l[8],
                "resolution", l[9].replaceFirst("^.*: ", ""), "density", l[10].replaceFirst("^.*: ", ""));
        return info;
    }
}
