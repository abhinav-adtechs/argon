package com.argon.launcher.remote;

import static com.argon.launcher.remote.Shell.quote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

// The Files page: what the shell user can see on the TV.
final class TvFiles {
    private TvFiles() {
    }

    static String clean(String p) {
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) parts.pollLast();
            else parts.addLast(seg);
        }
        return "/" + String.join("/", parts);
    }

    // Lists one folder. Links are followed, so /sdcard reads as a folder.
    static JSONObject list(Shell sh, String path) {
        String dir = clean(path);
        String out = sh.run("if [ -d " + quote(dir) + " ]; then find " + quote(dir.equals("/") ? "/" : dir + "/")
                + " -maxdepth 1 -mindepth 1 -exec stat -L -c '%F|%s|%Y|%A|%n' {} + 2>&1; else echo NOTDIR; fi", 10000);
        if (out.startsWith("NOTDIR")) return Json.obj("path", dir, "error", "Not a folder", "entries", new JSONArray());
        List<JSONObject> entries = new ArrayList<>();
        boolean denied = false;
        for (String line : out.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.startsWith("find:") && line.contains("Permission denied")) {
                if (line.contains(dir + "/:") || line.contains(dir + ":")) denied = true;
                continue;
            }
            String[] f = line.split("\\|", 5);
            if (f.length < 5 || f[4].isEmpty() || f[3].isEmpty()) continue;
            try {
                entries.add(Json.obj("name", f[4].substring(f[4].lastIndexOf('/') + 1),
                        "dir", f[0].equals("directory"), "size", Long.parseLong(f[1]),
                        "mtime", Long.parseLong(f[2]) * 1000, "perm", f[3]));
            } catch (NumberFormatException ignored) {
            }
        }
        entries.sort((a, b) -> {
            boolean ad = a.optBoolean("dir"), bd = b.optBoolean("dir");
            return ad == bd ? a.optString("name").compareToIgnoreCase(b.optString("name")) : ad ? -1 : 1;
        });
        return Json.obj("path", dir,
                "error", denied && entries.isEmpty() ? "The TV doesn't let adb read this folder" : "",
                "entries", new JSONArray(entries));
    }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"), Map.entry("mkv", "video/x-matroska"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "audio/ogg"), Map.entry("m4a", "audio/mp4"), Map.entry("wav", "audio/wav"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("json", "application/json"), Map.entry("xml", "text/plain; charset=utf-8"),
            Map.entry("prop", "text/plain; charset=utf-8"), Map.entry("sh", "text/plain; charset=utf-8"),
            Map.entry("rc", "text/plain; charset=utf-8"), Map.entry("conf", "text/plain; charset=utf-8"),
            Map.entry("kl", "text/plain; charset=utf-8"), Map.entry("csv", "text/plain; charset=utf-8"),
            Map.entry("md", "text/plain; charset=utf-8"), Map.entry("pdf", "application/pdf"),
            Map.entry("apk", "application/vnd.android.package-archive"));

    // Streams a file off the TV.
    static void download(Link link, Http.Request req, String path, boolean inline) throws IOException {
        String p = clean(path);
        String name = p.substring(p.lastIndexOf('/') + 1);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String type = TYPES.getOrDefault(ext, "application/octet-stream");
        try (Adb.Stream s = link.open("exec:cat " + quote(p) + " 2>/dev/null", 5000)) {
            OutputStream out = req.stream(200, "Content-Type", type,
                    "Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                            + URLEncoder.encode(name, "UTF-8").replace("+", "%20"),
                    "Cache-Control", "no-cache");
            if (s == null) return;
            for (byte[] b; (b = s.poll(30_000)) != null && b != Adb.EOF; ) out.write(b);
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // `head -c` takes exactly the body's length, so the shell knows where the file ends and says so.
    static JSONObject upload(Link link, String dir, String name, Http.Request req) {
        String target = clean(dir) + "/" + name.replaceAll("[/\\\\]", "_");
        String error = "Upload failed";
        try (Adb.Stream s = link.open("exec:head -c " + req.length() + " > " + quote(target) + " 2>&1 && echo UPLOADED", 5000)) {
            if (s == null) return Json.obj("ok", false, "error", "TV not connected");
            InputStream body = req.body();
            byte[] buf = new byte[64 * 1024];
            try {
                for (int n; (n = body.read(buf)) > 0; ) s.write(buf, 0, n);
            } catch (IOException ignored) {
                // The shell stopped reading; its output says why.
            }
            String out = new String(s.readAll(), StandardCharsets.UTF_8).trim();
            if (out.contains("UPLOADED")) return Json.obj("ok", true, "path", target);
            if (!out.isEmpty()) error = out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Json.obj("ok", false, "error", error);
    }

    static JSONObject remove(Shell sh, String path) {
        String p = clean(path);
        if (p.split("/").length < 3) return Json.obj("ok", false, "error", "Won't delete a top-level folder");
        String out = sh.run("rm -rf " + quote(p) + " && echo DELETED", 30000);
        return out.contains("DELETED") ? Json.obj("ok", true)
                : Json.obj("ok", false, "error", out.trim().isEmpty() ? "Delete failed" : out.trim());
    }
}
