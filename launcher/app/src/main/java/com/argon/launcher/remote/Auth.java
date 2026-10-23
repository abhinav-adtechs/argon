package com.argon.launcher.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// The password hash, signed-in devices and the history. Creating or resetting the password takes a
// code shown on the TV, so only someone in front of it can.
final class Auth {
    static final String COOKIE = "argon_session";
    // A device stays signed in until it goes this long without opening the remote.
    private static final long IDLE_MS = 180L * 86_400_000;
    private static final int KEEP = 2000;
    private static final int ITERATIONS = 200_000;
    private static final long CODE_MS = 5 * 60_000;
    private static final Pattern TOKEN = Pattern.compile("(?:^|;\\s*)" + COOKIE + "=([\\w-]+)");

    static final class Session {
        String id; // sha256 of the cookie token; the token itself is never stored
        String device, ip;
        long created, seen;

        JSONObject json() {
            return Json.obj("id", id, "device", device, "ip", ip, "created", created, "seen", seen);
        }
    }

    static final class Result {
        final int status;
        final String error, token;
        final Session session;

        Result(int status, String error, Session session, String token) {
            this.status = status;
            this.error = error;
            this.session = session;
            this.token = token;
        }

        boolean ok() {
            return error == null;
        }
    }

    private final File authFile, historyFile;
    private final Consumer<String> showCode;
    private final SecureRandom random = new SecureRandom();
    private String hash = "";
    private final List<Session> sessions = new ArrayList<>();
    private final Deque<JSONObject> recent = new ArrayDeque<>();
    private final Map<String, long[]> fails = new HashMap<>();
    private String code;
    private long codeUntil;
    private int codeTries;
    private boolean dirty;

    Auth(File dir, Consumer<String> showCode) {
        dir.mkdirs();
        authFile = new File(dir, "auth.json");
        historyFile = new File(dir, "history.jsonl");
        this.showCode = showCode;
        load();
    }

    private void load() {
        try {
            if (authFile.exists()) {
                JSONObject o = new JSONObject(read(authFile));
                hash = o.optString("hash");
                JSONArray a = o.optJSONArray("sessions");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    Session s = new Session();
                    s.id = j.getString("id");
                    s.device = j.optString("device");
                    s.ip = j.optString("ip");
                    s.created = j.optLong("created");
                    s.seen = j.optLong("seen");
                    sessions.add(s);
                }
            }
            if (historyFile.exists()) {
                String[] lines = read(historyFile).split("\n");
                for (int i = Math.max(0, lines.length - KEEP); i < lines.length; i++)
                    if (!lines[i].isEmpty()) recent.add(new JSONObject(lines[i]));
                if (lines.length > KEEP * 2) {
                    StringBuilder sb = new StringBuilder();
                    for (JSONObject e : recent) sb.append(e).append('\n');
                    write(historyFile, sb.toString(), false);
                }
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("can't read " + authFile, e);
        }
    }

    private synchronized void save() {
        JSONArray a = new JSONArray();
        for (Session s : sessions) a.put(s.json());
        try {
            write(authFile, Json.obj("hash", hash, "sessions", a).toString(), false);
        } catch (IOException ignored) {
        }
        dirty = false;
    }

    // Called once a minute: last-seen times are saved in batches.
    synchronized void flush() {
        if (dirty) save();
    }

    synchronized boolean isSetUp() {
        return !hash.isEmpty();
    }

    synchronized List<Session> sessions() {
        return new ArrayList<>(sessions);
    }

    synchronized Session sessionOf(String cookie, String ip) {
        if (cookie == null) return null;
        Matcher m = TOKEN.matcher(cookie);
        if (!m.find()) return null;
        String id = sha(m.group(1));
        for (Session s : sessions) {
            if (!s.id.equals(id)) continue;
            long now = System.currentTimeMillis();
            if (now - s.seen > IDLE_MS) {
                revoke(s.id);
                return null;
            }
            if (now - s.seen > 60_000 || !s.ip.equals(ip)) {
                s.seen = now;
                s.ip = ip;
                dirty = true;
            }
            return s;
        }
        return null;
    }

    static String setCookie(String token) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + 400 * 86400;
    }

    static final String CLEAR_COOKIE = COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";

    static String guessDevice(String ua) {
        if (ua == null) ua = "";
        String os = ua.contains("iPhone") ? "iPhone"
                : ua.contains("iPad") ? "iPad"
                : ua.contains("Android") ? (ua.contains("Mobile") ? "Android phone" : "Android tablet")
                : ua.contains("Macintosh") ? "Mac"
                : ua.contains("Windows") ? "Windows PC"
                : ua.contains("Linux") ? "Linux PC"
                : "Browser";
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : "";
        return browser.isEmpty() ? os : os + " · " + browser;
    }

    private static String deviceName(Object device, String ua) {
        String s = device == null || device == JSONObject.NULL ? "" : String.valueOf(device);
        s = s.replaceAll("[\\x00-\\x1f]", "").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? guessDevice(ua) : s;
    }

    private static String weak(String p) {
        return p == null || p.length() < 6 ? "Use at least 6 characters" : null;
    }

    // After 5 wrong tries from one address, each further try waits longer: 30 s, 1 min, 2 min … 15 min.
    private long waitSeconds(String ip) {
        long[] f = fails.get(ip);
        long now = System.currentTimeMillis();
        return f != null && f[1] > now ? (f[1] - now + 999) / 1000 : 0;
    }

    private void failed(String ip) {
        long[] f = fails.computeIfAbsent(ip, k -> new long[2]);
        f[0]++;
        if (f[0] >= 5) f[1] = System.currentTimeMillis() + Math.min(30_000L << Math.min(f[0] - 5, 10), 15 * 60_000L);
    }

    private Result start(String device, String ip, String what) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Session s = new Session();
        s.id = sha(token);
        s.device = device;
        s.ip = ip;
        s.created = s.seen = System.currentTimeMillis();
        sessions.add(s);
        save();
        record(s.device, s.ip, "auth", what);
        return new Result(200, null, s, token);
    }

    // Puts a fresh code on the TV for the next five minutes.
    synchronized void requestCode() {
        if (code == null || System.currentTimeMillis() > codeUntil) {
            code = String.format("%06d", random.nextInt(1_000_000));
            codeTries = 0;
        }
        codeUntil = System.currentTimeMillis() + CODE_MS;
        showCode.accept(code);
    }

    // Creates the password, or replaces a forgotten one (which signs every device out).
    synchronized Result setup(Object codeIn, Object password, Object device, String ip, String ua) {
        long wait = waitSeconds(ip);
        if (wait > 0) return new Result(429, "Too many wrong tries. Wait " + wait + " s.", null, null);
        String name = deviceName(device, ua);
        // A code lasts five minutes and five tries in all, whoever makes them; then the TV shows a new one.
        if (code == null || System.currentTimeMillis() > codeUntil) {
            code = null;
            requestCode();
            return new Result(401, "That code ran out. There's a new one on the TV now.", null, null);
        }
        if (!code.equals(String.valueOf(codeIn).trim())) {
            failed(ip);
            record(name, ip, "auth", "Wrong code");
            if (++codeTries < 5) return new Result(401, "Wrong code", null, null);
            code = null;
            requestCode();
            return new Result(401, "Wrong code. There's a new one on the TV now.", null, null);
        }
        String err = weak(password instanceof String ? (String) password : null);
        if (err != null) return new Result(400, err, null, null);
        fails.remove(ip);
        code = null;
        boolean reset = !hash.isEmpty();
        hash = hash((String) password);
        sessions.clear();
        return start(name, ip, reset ? "Reset the password" : "Set the password");
    }

    synchronized Result login(Object password, Object device, String ip, String ua) {
        long wait = waitSeconds(ip);
        if (wait > 0) return new Result(429, "Too many wrong tries. Wait " + wait + " s.", null, null);
        if (hash.isEmpty()) return new Result(409, "No password has been set yet", null, null);
        String name = deviceName(device, ua);
        if (!(password instanceof String) || !verify((String) password, hash)) {
            failed(ip);
            record(name, ip, "auth", "Wrong password");
            return new Result(401, "Wrong password", null, null);
        }
        fails.remove(ip);
        return start(name, ip, "Signed in");
    }

    synchronized Session revoke(String id) {
        for (Iterator<Session> it = sessions.iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (s.id.equals(id)) {
                it.remove();
                save();
                return s;
            }
        }
        return null;
    }

    // A new password signs out every other device; returns the ids that were signed out.
    synchronized List<String> changePassword(Session s, Object current, Object next, String[] error) {
        if (!(current instanceof String) || !verify((String) current, hash)) {
            error[0] = "Current password is wrong";
            return null;
        }
        String err = weak(next instanceof String ? (String) next : null);
        if (err != null) {
            error[0] = err;
            return null;
        }
        hash = hash((String) next);
        List<String> dropped = new ArrayList<>();
        for (Iterator<Session> it = sessions.iterator(); it.hasNext(); ) {
            Session x = it.next();
            if (!x.id.equals(s.id)) {
                dropped.add(x.id);
                it.remove();
            }
        }
        save();
        record(s.device, s.ip, "auth", "Changed the password");
        return dropped;
    }

    /* ---------- History ---------- */

    synchronized void record(String device, String ip, String kind, String text) {
        JSONObject e = Json.obj("at", System.currentTimeMillis(), "kind", kind, "device", device, "ip", ip, "text", text);
        recent.add(e);
        while (recent.size() > KEEP) recent.removeFirst();
        try {
            write(historyFile, e + "\n", true);
        } catch (IOException ignored) {
        }
    }

    synchronized JSONArray history(int limit) {
        JSONArray a = new JSONArray();
        Iterator<JSONObject> it = recent.descendingIterator();
        for (int i = 0; i < limit && it.hasNext(); i++) a.put(it.next());
        return a;
    }

    /* ---------- Hashing ---------- */

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2-sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(pbkdf2(password, salt, ITERATIONS));
    }

    private static boolean verify(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) return false;
        Base64.Decoder b64 = Base64.getDecoder();
        return MessageDigest.isEqual(b64.decode(p[3]), pbkdf2(password, b64.decode(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s, boolean append) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
