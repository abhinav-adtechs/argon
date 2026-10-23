package com.argon.launcher.remote;

import static com.argon.launcher.remote.Shell.quote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.argon.launcher.ContinueFeed;
import com.argon.launcher.smarthome.SmartHome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The web remote, served from the TV itself: the page in assets/remote, keys through a virtual
// remote, everything else through adb shell commands on the TV's own adbd.
final class Server {
    interface Assets {
        InputStream open(String path) throws IOException;
    }

    // Linux input codes; the TV maps them through Generic.kl.
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("UP", 103);
        KEYS.put("DOWN", 108);
        KEYS.put("LEFT", 105);
        KEYS.put("RIGHT", 106);
        KEYS.put("OK", 353);
        KEYS.put("BACK", 158);
        KEYS.put("HOME", 172);
        KEYS.put("MENU", 139);
        KEYS.put("POWER", 116);
        KEYS.put("PLAY_PAUSE", 164);
        KEYS.put("REWIND", 168);
        KEYS.put("FAST_FORWARD", 208);
        KEYS.put("VOL_UP", 115);
        KEYS.put("VOL_DOWN", 114);
        KEYS.put("MUTE", 113);
        KEYS.put("ENTER", 28);
        KEYS.put("DEL", 14);
    }

    private static final String[][] APPS = {
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.netflix.ninja", "Netflix"},
            {"com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"in.startv.hotstar", "JioHotstar"},
            {"com.sonyliv", "Sony LIV"},
    };
    private static final String LIVE_TV = "com.mitv.livetv";
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("com.argon.launcher", "Home");
        NAMES.put("com.android.tv.settings", "Settings");
        NAMES.put(LIVE_TV, "Live TV");
        for (String[] a : APPS) NAMES.put(a[0], a[1]);
    }

    private static final Map<String, String> APP_ACTIONS = Map.of("stop", "Force-stopped", "cache",
            "Cleared the cache of", "disable", "Disabled", "enable", "Enabled", "info", "Opened app info for",
            "open", "Opened");

    // What the sign-in page needs, plus the icons a phone fetches when adding the page to its home screen.
    private static final Set<String> PUBLIC_FILES = Set.of("/login.html", "/app.css", "/manifest.webmanifest",
            "/icon-180.png", "/icon-192.png", "/icon-512.png");

    private static final String VOLUME_CMD = "cmd media_session volume --stream 3 --get | grep 'volume is'; "
            + "dumpsys audio | grep -A1 '^- STREAM_MUSIC:' | head -2";
    private static final Pattern TOP_PKG = Pattern.compile("u0 ([\\w.]+)/");
    private static final Pattern VOLUME = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]");
    private static final Pattern MUTED = Pattern.compile("Muted: (true|false)");
    private static final Pattern INPUT = Pattern.compile("id=([^,]+), pkg=[^}]*mLabel=(HDMI \\d|AV)");

    private final Link link;
    private final Assets assets;
    private final Consumer<String> log;
    final Auth auth;
    private final Shell act, poll, mgmt, pinger, watcher;
    private final Remote remote;
    private final TvStats stats = new TvStats();
    private final SmartHome home;
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "remote-timer");
        t.setDaemon(true);
        return t;
    });
    private final Set<Client> clients = new CopyOnWriteArraySet<>();
    private final Map<String, String> components = new ConcurrentHashMap<>();

    // The live state every page mirrors.
    private boolean connected, muted, kbd;
    private String nowPkg = "", nowName = "";
    private int volume = -1;
    private List<String[]> inputs = new ArrayList<>();

    private volatile long lastKeyAt;
    private ScheduledFuture<?> refreshTimer;
    private boolean settingUp;
    private JSONObject watchCache;
    private long watchAt;

    // The TV picture (one screencap at a time, shared by pages asking within 400 ms), Continue Watching as the
    // pages last saw it (resume and art go by index), and its art made small.
    private final Context context;
    private final TvInputManager tvInputs;
    private final Object screenLock = new Object();
    private byte[] screenJpeg;
    private long screenAt;
    private volatile List<ContinueFeed.Item> continueItems = new ArrayList<>();
    private final Map<String, byte[]> continueArt = new ConcurrentHashMap<>();

    // `typed` mirrors what this page has typed into the TV's text box, so a search can go in the history.
    private final class Client {
        final Http.WebSocket ws;
        final Auth.Session sess;
        final Map<Integer, ScheduledFuture<?>> held = new ConcurrentHashMap<>();
        // Everything but keys runs here, in order, so a slow shell command never holds up the next key.
        final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "remote-client");
            t.setDaemon(true);
            return t;
        });
        String typed = "";

        Client(Http.WebSocket ws, Auth.Session sess) {
            this.ws = ws;
            this.sess = sess;
        }
    }

    Server(Context context, Link link, File dataDir, Assets assets, int port, Consumer<String> log) throws IOException {
        this.context = context;
        this.link = link;
        tvInputs = context.getSystemService(TvInputManager.class);
        // An HDMI device switched on or unplugged changes the page's input rings.
        tvInputs.registerCallback(new TvInputManager.TvInputCallback() {
            @Override
            public void onInputStateChanged(String inputId, int state) {
                timers.execute(Server.this::pushState);
            }
        }, new Handler(Looper.getMainLooper()));
        this.assets = assets;
        this.log = log;
        act = new Shell(link);
        poll = new Shell(link);
        mgmt = new Shell(link);
        pinger = new Shell(link);
        // `content read` takes ~2.4 s on the TV (most of it starting the tool), so stats get their own shell.
        watcher = new Shell(link);
        auth = new Auth(dataDir, this::showCode);
        remote = new Remote();
        home = SmartHome.get(context);
        // Lights and plugs change from anywhere (the TV, Google Home, a switch); every page follows.
        home.addListener(() -> broadcast(home.state().toString()));
        new Http(port, this::serve, log);
        timers.scheduleWithFixedDelay(this::safeRefreshTick, 4, 4, TimeUnit.SECONDS);
        timers.scheduleWithFixedDelay(() -> {
            if (!clients.isEmpty() && remote.ready) remote.ping();
        }, 2, 2, TimeUnit.SECONDS);
        timers.scheduleWithFixedDelay(auth::flush, 1, 1, TimeUnit.MINUTES);
        // Waits for adb first, or the first commands time out and those apps fall back to `monkey`.
        Thread t = new Thread(() -> {
            try {
                link.get(Long.MAX_VALUE);
                setup();
            } catch (InterruptedException ignored) {
            }
        }, "remote-setup");
        t.setDaemon(true);
        t.start();
    }

    private void safeRefreshTick() {
        try {
            if (!clients.isEmpty() && now() - lastKeyAt > 2500) refresh(false);
        } catch (RuntimeException e) {
            log.accept("refresh: " + e);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private void showCode(String code) {
        notice("Code: " + code + "\\nValid for 5 minutes", 300);
    }

    // A card over whatever is on screen (NoticeReceiver); a new one replaces the last.
    private void notice(String text, int seconds) {
        timers.execute(() -> act.run("am broadcast -a com.argon.launcher.NOTICE -p com.argon.launcher "
                + "--es title 'Web remote' --es text " + quote(text) + " --ei seconds " + seconds));
    }

    /* ---------- Virtual remote ---------- */

    // A virtual remote made with the TV's own `uinput` tool. The stream stays open, so a key costs
    // a write instead of starting a new `input keyevent` (~140 ms).
    private final class Remote {
        private volatile Adb.Stream stream;
        volatile boolean ready;
        volatile double rtt;

        Remote() {
            Thread t = new Thread(this::loop, "uinput");
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            while (true) {
                try {
                    Adb.Stream s = link.open("exec:uinput -", 60_000);
                    if (s == null) continue;
                    JSONArray codes = new JSONArray();
                    for (int c : KEYS.values()) codes.put(c);
                    s.write(Json.obj("id", 1, "command", "register", "name", "Argon Remote", "vid", 0x18d1,
                            "pid", 0x7a01, "bus", "bluetooth", "configuration", new JSONArray()
                                    .put(Json.obj("type", 100, "data", new JSONArray().put(1)))
                                    .put(Json.obj("type", 101, "data", codes))) + "\n");
                    // The uinput tool needs a moment to create the device before the first key.
                    Thread.sleep(400);
                    stream = s;
                    ready = true;
                    log.accept("virtual remote ready");
                    // Drains anything uinput prints until it exits.
                    while (s.poll(Long.MAX_VALUE) != Adb.EOF) ;
                    log.accept("uinput exited, restarting");
                } catch (IOException e) {
                    log.accept("uinput: " + e.getMessage());
                } catch (InterruptedException e) {
                    return;
                }
                ready = false;
                stream = null;
                lost();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        // Returns the latest TV round trip, as the best estimate of how long the key took.
        private synchronized double inject(int... events) {
            Adb.Stream s = stream;
            JSONArray ev = new JSONArray();
            for (int e : events) ev.put(e);
            if (s != null) {
                try {
                    s.write(Json.obj("id", 1, "command", "inject", "events", ev) + "\n");
                } catch (IOException ignored) {
                }
            }
            return rtt;
        }

        double tap(int code) {
            return inject(1, code, 1, 0, 0, 0, 1, code, 0, 0, 0, 0);
        }

        double down(int code) {
            return inject(1, code, 1, 0, 0, 0);
        }

        double up(int code) {
            return inject(1, code, 0, 0, 0, 0);
        }

        // Times an `echo` over its own shell: the same link the keys travel on.
        void ping() {
            long t0 = System.nanoTime();
            String out = pinger.run("echo p", 3000);
            rtt = out.contains("p") ? (System.nanoTime() - t0) / 1e6 : 3000;
        }
    }

    private void lost() {
        boolean changed;
        synchronized (this) {
            changed = connected;
            connected = false;
        }
        if (changed) pushState();
    }

    /* ---------- Live state ---------- */

    private static String prettify(String pkg) {
        String last = pkg;
        for (String p : pkg.split("\\.")) if (!p.matches("^(android|tv|app|com|in|google|apps)$")) last = p;
        return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    private synchronized JSONObject stateJson() {
        JSONArray apps = new JSONArray();
        for (String[] a : APPS) apps.put(Json.obj("name", a[1], "pkg", a[0]));
        JSONArray ins = new JSONArray();
        for (String[] i : inputs) {
            JSONObject o = Json.obj("id", i[0], "label", i[1]);
            if (i[0].contains("/.HdmiInputService/")) Json.put(o, "connected", hdmiConnected(i[0]));
            ins.put(o);
        }
        return Json.obj("t", "state", "address", link.address, "connected", connected,
                "now", Json.obj("pkg", nowPkg, "name", nowName), "volume", volume, "muted", muted, "kbd", kbd,
                "apps", apps, "inputs", ins);
    }

    private boolean hdmiConnected(String id) {
        try {
            return tvInputs.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void broadcast(String s) {
        for (Client c : clients) c.ws.send(s);
    }

    private void pushState() {
        broadcast(stateJson().toString());
    }

    private void refresh(boolean volumeOnly) {
        String out = poll.run(volumeOnly ? VOLUME_CMD
                : "dumpsys activity activities | grep -m1 topResumedActivity; " + VOLUME_CMD
                + "; dumpsys input_method | grep -m1 'mInputShown='");
        Matcher pm = TOP_PKG.matcher(out), vm = VOLUME.matcher(out), mm = MUTED.matcher(out);
        String pkg = pm.find() ? pm.group(1) : null;
        boolean hasVol = vm.find();
        boolean isConnected = pkg != null || hasVol;
        boolean changed;
        boolean needSetup;
        List<Client> typedOut = new ArrayList<>();
        synchronized (this) {
            changed = isConnected != connected;
            connected = isConnected;
            if (pkg != null && !pkg.equals(nowPkg)) {
                nowPkg = pkg;
                nowName = NAMES.containsKey(pkg) ? NAMES.get(pkg) : prettify(pkg);
                changed = true;
            }
            if (hasVol) {
                double lo = Double.parseDouble(vm.group(2)), hi = Double.parseDouble(vm.group(3));
                int v = (int) Math.round((Double.parseDouble(vm.group(1)) - lo) / (hi - lo) * 100);
                if (v != volume) {
                    volume = v;
                    changed = true;
                }
            }
            if (mm.find() && mm.group(1).equals("true") != muted) {
                muted = mm.group(1).equals("true");
                changed = true;
            }
            if (!volumeOnly) {
                boolean k = out.contains("mInputShown=true");
                if (k != kbd) {
                    kbd = k;
                    changed = true;
                }
                // Some apps search as you type and close the keyboard themselves, without an Enter.
                if (!k) for (Client c : clients) if (!c.typed.trim().isEmpty()) typedOut.add(c);
            }
            needSetup = connected && components.isEmpty();
        }
        for (Client c : typedOut) {
            record(c.sess, "action", "Typed \"" + c.typed.trim() + "\"");
            c.typed = "";
        }
        if (changed) pushState();
        // On its own thread: setup() ends with a refresh, which would call back in here.
        if (needSetup) timers.execute(this::setup);
    }

    // The status poll runs dumpsys on the TV, which can stall key handling for a few hundred ms,
    // so it waits while someone is navigating.
    private synchronized void refreshSoon(long ms) {
        if (refreshTimer != null) refreshTimer.cancel(false);
        refreshTimer = timers.schedule(() -> {
            synchronized (this) {
                refreshTimer = null;
            }
            long idle = now() - lastKeyAt;
            if (idle < 700) refreshSoon(750 - idle);
            else refresh(false);
        }, ms, TimeUnit.MILLISECONDS);
    }

    // App components and inputs; re-run when the TV comes back if it was offline at start.
    private void setup() {
        synchronized (this) {
            if (settingUp) return;
            settingUp = true;
        }
        try {
            List<String> pkgs = new ArrayList<>();
            for (String[] a : APPS) pkgs.add(a[0]);
            pkgs.add(LIVE_TV);
            for (String pkg : pkgs) {
                String[] lines = poll.run("cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER "
                        + "-a android.intent.action.MAIN " + pkg + " | tail -1").trim().split("\n");
                String comp = lines[lines.length - 1];
                if (comp.contains("/")) components.put(pkg, comp);
            }
            String tv = poll.run("dumpsys tv_input | grep 'TvInputInfo{id=com.mediatek.tis'");
            Map<String, String> found = new LinkedHashMap<>();
            for (Matcher m = INPUT.matcher(tv); m.find(); ) found.put(m.group(1), m.group(2));
            List<String[]> list = new ArrayList<>();
            for (Map.Entry<String, String> e : found.entrySet()) list.add(new String[]{e.getKey(), e.getValue()});
            list.sort((a, b) -> a[1].compareTo(b[1]));
            if (components.containsKey(LIVE_TV)) list.add(new String[]{"live", "Live TV"});
            synchronized (this) {
                inputs = list;
            }
        } finally {
            synchronized (this) {
                settingUp = false;
            }
        }
        refresh(false);
        pushState();
        synchronized (this) {
            log.accept("TV: " + components.size() + " apps, " + inputs.size() + " inputs");
        }
    }

    private void record(Auth.Session s, String kind, String text) {
        auth.record(s.device, s.ip, kind, text);
    }

    /* ---------- WebSocket messages ---------- */

    private static void ack(Client c, Object id, double ms) {
        if (id == null || id == JSONObject.NULL || "".equals(id) || Integer.valueOf(0).equals(id)) return;
        c.ws.send(Json.obj("t", "ack", "id", id, "adb", Math.round(ms * 10) / 10.0).toString());
    }

    private void afterKey(String k) {
        if (k.equals("OK") || k.equals("BACK") || k.equals("HOME")) refreshSoon(900);
        if (k.startsWith("VOL") || k.equals("MUTE")) timers.schedule(() -> refresh(true), 150, TimeUnit.MILLISECONDS);
    }

    // Keys are injected on the connection's own thread, in the order they arrive.
    private void handleKey(Client c, JSONObject m) {
        String t = m.optString("t"), k = m.optString("k");
        Integer code = KEYS.get(k);
        if (code == null || !remote.ready) return;
        Object id = m.opt("id");
        lastKeyAt = now();
        if (k.equals("POWER") && (t.equals("tap") || t.equals("down"))) record(c.sess, "action", "Pressed power");
        switch (t) {
            case "tap":
                ack(c, id, remote.tap(code));
                afterKey(k);
                break;
            case "down": {
                ScheduledFuture<?> old = c.held.remove(code);
                if (old != null) old.cancel(false);
                // Never leave a key stuck down if the phone sleeps mid-press.
                c.held.put(code, timers.schedule(() -> {
                    c.held.remove(code);
                    remote.up(code);
                }, 8, TimeUnit.SECONDS));
                ack(c, id, remote.down(code));
                break;
            }
            case "up": {
                ScheduledFuture<?> f = c.held.remove(code);
                if (f == null) return;
                f.cancel(false);
                remote.up(code);
                afterKey(k);
                break;
            }
            default: // long
                ack(c, id, remote.down(code));
                timers.schedule(() -> {
                    remote.up(code);
                    afterKey(k);
                }, 650, TimeUnit.MILLISECONDS);
        }
    }

    private void handle(Client c, JSONObject m) {
        switch (m.optString("t")) {
            case "app": {
                String pkg = m.optString("pkg", null);
                if (pkg == null) {
                    int i = m.optInt("i", -1);
                    if (i >= 0 && i < APPS.length) pkg = APPS[i][0];
                }
                if (pkg == null || !pkg.matches("^[\\w.]+$")) return;
                long t0 = System.nanoTime();
                String comp = components.get(pkg);
                act.run(comp != null ? "am start -n " + comp
                        : "monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1");
                ack(c, m.opt("id"), (System.nanoTime() - t0) / 1e6);
                String name;
                synchronized (this) {
                    nowPkg = pkg;
                    nowName = name = NAMES.containsKey(pkg) ? NAMES.get(pkg) : m.optString("name", prettify(pkg));
                }
                pushState();
                record(c.sess, "action", "Opened " + name);
                refreshSoon(1500);
                return;
            }
            case "input": {
                String[] inp = null;
                synchronized (this) {
                    for (String[] i : inputs) if (i[0].equals(m.optString("id"))) inp = i;
                }
                if (inp == null) return;
                record(c.sess, "action", "Switched to " + inp[1]);
                if (inp[0].equals("live")) act.run("am start -n " + components.get(LIVE_TV));
                else {
                    String uri;
                    try {
                        uri = "content://android.media.tv/passthrough/"
                                + URLEncoder.encode(inp[0], "UTF-8").replace("+", "%20");
                    } catch (java.io.UnsupportedEncodingException e) {
                        return;
                    }
                    act.run("am start -a android.intent.action.VIEW -d " + quote(uri));
                }
                refreshSoon(1500);
                return;
            }
            case "type": {
                // Deletes and text share one shell so they reach the TV in order.
                int n = Math.min(Math.max(m.optInt("del", 0), 0), 200);
                if (n > 0) act.run("input keyevent" + " 67".repeat(n));
                String s = m.optString("s", "").replaceAll("[^\\x20-\\x7e]", "");
                if (!s.isEmpty()) act.run("input text " + quote(s.replace(" ", "%s")));
                String typed = c.typed;
                typed = typed.substring(0, Math.max(0, typed.length() - n)) + s;
                c.typed = typed.length() > 200 ? typed.substring(typed.length() - 200) : typed;
                if (m.optBoolean("enter")) {
                    act.run("input keyevent 66");
                    if (!c.typed.trim().isEmpty()) record(c.sess, "action", "Typed \"" + c.typed.trim() + "\"");
                    c.typed = "";
                    refreshSoon(900);
                }
                return;
            }
            case "volume": {
                int v = (int) Math.max(0, Math.min(100, Math.round(m.optDouble("v", 0))));
                act.run("cmd media_session volume --stream 3 --set " + v);
                synchronized (this) {
                    volume = v;
                }
                String s = stateJson().toString();
                for (Client o : clients) if (o != c) o.ws.send(s);
                return;
            }
            case "home": {
                if (m.has("show")) {
                    act.run("am start -n com.argon.launcher/.HomeActivity --es device " + quote(m.optString("show")));
                    return;
                }
                String what = homeAction(m);
                String error = home.command(m);
                if (error != null) c.ws.send(Json.obj("t", "homeError", "error", error).toString());
                else if (what != null) record(c.sess, "action", what);
                return;
            }
            case "ping":
                c.ws.send(Json.obj("t", "pong", "c", m.opt("c"), "adb", Math.round(remote.rtt * 10) / 10.0).toString());
        }
    }

    // A light or plug change in words, for the history; null for the ones not worth a line (brightness, colour).
    private String homeAction(JSONObject m) {
        if (m.has("scene")) {
            JSONArray scenes = home.state().optJSONArray("scenes");
            for (int i = 0; scenes != null && i < scenes.length(); i++) {
                if (scenes.optJSONObject(i).optString("id").equals(m.optString("scene")))
                    return "Set the lights to " + scenes.optJSONObject(i).optString("name");
            }
            return null;
        }
        if (m.has("all")) return m.optBoolean("all") ? "Turned on every light and plug" : "Turned off every light and plug";
        if (!m.has("on") && !m.optBoolean("toggle")) return null;
        JSONArray ds = home.state().optJSONArray("devices");
        for (int i = 0; ds != null && i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (!d.optString("id").equals(m.optString("id"))) continue;
            boolean on = m.has("on") ? m.optBoolean("on") : !d.optBoolean("on");
            return (on ? "Turned on " : "Turned off ") + d.optString("name");
        }
        return null;
    }

    private void onMessage(Client c, String raw) {
        JSONObject m = Json.parse(raw);
        String t = m.optString("t");
        if (t.equals("tap") || t.equals("down") || t.equals("up") || t.equals("long")) handleKey(c, m);
        else c.work.execute(() -> {
            try {
                handle(c, m);
            } catch (RuntimeException e) {
                log.accept("ws " + t + ": " + e);
            }
        });
    }

    // Closes the live connections of devices that were just signed out, so their pages go to sign-in.
    private void kick(List<String> ids) {
        for (Client c : clients) if (ids.contains(c.sess.id)) c.ws.close(4001, "Signed out");
    }

    /* ---------- HTTP ---------- */

    private static void json(Http.Request req, Object o) throws IOException {
        json(req, o, 200);
    }

    private static void json(Http.Request req, Object o, int status, String... headers) throws IOException {
        String[] h = Arrays.copyOf(headers, headers.length + 2);
        h[headers.length] = "Cache-Control";
        h[headers.length + 1] = "no-cache";
        req.send(status, "application/json", String.valueOf(o).getBytes(StandardCharsets.UTF_8), h);
    }

    private static void offline(Http.Request req) throws IOException {
        json(req, Json.obj("ok", false, "error", "TV not connected"), 503);
    }

    private static final Map<String, String> MIME = Map.of("html", "text/html; charset=utf-8", "css",
            "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8", "png", "image/png",
            "webmanifest", "application/manifest+json", "svg", "image/svg+xml", "json", "application/json");

    private void file(Http.Request req, String path) throws IOException {
        if (path.contains("..")) {
            req.send(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body;
        try (InputStream in = assets.open(path.substring(1))) {
            body = in.readAllBytes();
        } catch (IOException e) {
            req.send(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1);
        req.send(200, MIME.getOrDefault(ext, "application/octet-stream"), body, "Cache-Control", "no-cache");
    }

    /* ---------- The TV picture, Continue Watching art, the wallpaper ---------- */

    // What's on screen: a 960×540 JPEG for the page's mirror, or the full PNG to save. While protected video
    // (Netflix and the like) plays, screencap fails with no output (409, the page says the picture is hidden); on
    // some screens it's black instead. The mirror reads raw pixels: `screencap -p` spends ~2.3 s of CPU on the PNG,
    // the raw dump ~0.2 s.
    private void screen(Http.Request req, boolean full) throws IOException {
        byte[] jpg = null, png = null, raw = null;
        synchronized (screenLock) {
            if (full) {
                png = raw = capture("exec:screencap -p");
            } else if (screenJpeg != null && System.currentTimeMillis() - screenAt < 400) {
                jpg = screenJpeg;
            } else {
                raw = capture("exec:screencap");
                jpg = mirrorJpeg(raw);
                if (jpg != null) {
                    screenJpeg = jpg;
                    screenAt = System.currentTimeMillis();
                }
            }
        }
        if (png != null && png.length > 100) {
            String name = "tv-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png";
            req.send(200, "image/png", png, "Content-Disposition", "attachment; filename=\"" + name + "\"", "Cache-Control", "no-store");
        } else if (jpg != null) {
            req.send(200, "image/jpeg", jpg, "Cache-Control", "no-store");
        } else if (raw != null && raw.length == 0) {
            json(req, Json.obj("ok", false, "error", "protected"), 409);
        } else {
            offline(req);
        }
    }

    private byte[] capture(String service) {
        try (Adb.Stream s = link.open(service, 5000)) {
            if (s == null) return null;
            ByteArrayOutputStream all = new ByteArrayOutputStream(1 << 20);
            for (byte[] b; (b = s.poll(10_000)) != null && b != Adb.EOF; ) all.write(b, 0, b.length);
            return all.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // Raw screencap: width, height, format and color space (little-endian ints), then RGBA_8888 rows.
    private static byte[] mirrorJpeg(byte[] raw) {
        if (raw == null || raw.length < 16) return null;
        ByteBuffer head = ByteBuffer.wrap(raw, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
        int w = head.getInt(), h = head.getInt(), format = head.getInt();
        if (format != 1 || w <= 0 || h <= 0 || raw.length < 16 + (long) w * h * 4) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 16, w * h * 4));
        Bitmap small = Bitmap.createScaledBitmap(b, w / 2, h / 2, true);
        b.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 78, out);
        small.recycle();
        return out.toByteArray();
    }

    // A Continue Watching item's picture, 640×360 (from the app's https or content:// artwork, as the home screen).
    private byte[] continueArt(String index) {
        int i;
        try {
            i = Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return null;
        }
        List<ContinueFeed.Item> items = continueItems;
        if (i < 0 || i >= items.size() || items.get(i).image == null) return null;
        String uri = items.get(i).image;
        byte[] hit = continueArt.get(uri);
        if (hit != null) return hit;
        HttpURLConnection conn = null;
        try {
            Uri u = Uri.parse(uri);
            InputStream in;
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                conn = (HttpURLConnection) new URL(uri).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                in = conn.getInputStream();
            } else {
                in = context.getContentResolver().openInputStream(u);
                if (in == null) return null;
            }
            byte[] raw;
            try (InputStream stream = in) {
                raw = stream.readNBytes(8 << 20);
            }
            Bitmap out = cover(raw, 640, 360);
            if (out == null) return null;
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 82, jpg);
            out.recycle();
            if (continueArt.size() > 30) continueArt.clear();
            continueArt.put(uri, jpg.toByteArray());
            return jpg.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.accept("continue art: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // A picture sent from the Home page becomes the Welcome hub's wallpaper: cropped to 1920×1080 and saved where
    // the launcher (this app's main process) reads it, files/wallpaper.jpg; the setting's new stamp reloads it.
    private JSONObject wallpaper(Http.Request req) throws IOException {
        if (req.length() <= 0 || req.length() > 15L << 20) return Json.obj("ok", false, "error", "Pick a JPG or PNG under 15 MB");
        Bitmap out = cover(req.body().readAllBytes(), 1920, 1080);
        if (out == null) return Json.obj("ok", false, "error", "The TV can't read that picture");
        File target = new File(context.getFilesDir(), "wallpaper.jpg"), part = new File(target.getPath() + ".part");
        try (FileOutputStream f = new FileOutputStream(part)) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, f);
        } finally {
            out.recycle();
        }
        if (!part.renameTo(target)) return Json.obj("ok", false, "error", "Couldn't save the picture");
        JSONObject r = TvHome.set(mgmt, "wallpaper", "photo:" + System.currentTimeMillis());
        return r.optBoolean("ok") ? Json.obj("ok", true) : r;
    }

    // Decoded no larger than needed, then cropped to fill w × h.
    private static Bitmap cover(byte[] raw, int w, int h) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= w && bounds.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        new Canvas(out).drawBitmap(src, null, new RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    private void serve(Http.Request req) throws IOException {
        String p = req.path;
        // Stops another website open in the same browser from using this device's sign-in.
        String origin = req.header("origin");
        if ((!req.method.equals("GET") || p.equals("/ws")) && origin != null
                && !origin.equals("http://" + req.header("host"))) {
            req.send(403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            if (p.startsWith("/api/auth/") && authApi(req)) return;
            Auth.Session sess = auth.sessionOf(req.header("cookie"), req.ip);
            if (sess == null) {
                if (p.startsWith("/api/") || p.equals("/ws"))
                    json(req, Json.obj("ok", false, "error", "Sign in first"), 401);
                else if (p.equals("/") || p.equals("/index.html")) file(req, "/login.html");
                else if (PUBLIC_FILES.contains(p)) file(req, p);
                else req.send(401, "text/plain", "sign in first".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (p.equals("/ws")) {
                socket(req, sess);
                return;
            }
            if (p.startsWith("/api/")) {
                api(req, sess);
                return;
            }
        } catch (RuntimeException e) {
            log.accept("api error " + p + ": " + e);
            json(req, Json.obj("ok", false, "error", String.valueOf(e)), 500);
            return;
        }
        file(req, p.equals("/") ? "/index.html" : p);
    }

    private void socket(Http.Request req, Auth.Session sess) throws IOException {
        Http.WebSocket ws = req.upgrade();
        if (ws == null) {
            req.send(400, "text/plain", "upgrade failed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Client c = new Client(ws, sess);
        clients.add(c);
        ws.send(stateJson().toString());
        ws.send(home.state().toString());
        refreshSoon(0);
        try {
            for (String raw; (raw = ws.receive()) != null; ) onMessage(c, raw);
        } finally {
            clients.remove(c);
            c.work.shutdown();
            for (Map.Entry<Integer, ScheduledFuture<?>> e : c.held.entrySet()) {
                e.getValue().cancel(false);
                remote.up(e.getKey());
            }
        }
    }

    private JSONObject body(Http.Request req) throws IOException {
        return req.post() ? Json.parse(req.text(1 << 20)) : new JSONObject();
    }

    // Sign-in endpoints, reachable without a session. False when the path isn't one of them.
    private boolean authApi(Http.Request req) throws IOException {
        String p = req.path, ua = req.header("user-agent");
        if (p.equals("/api/auth/status")) {
            Auth.Session s = auth.sessionOf(req.header("cookie"), req.ip);
            json(req, Json.obj("ok", true, "setUp", auth.isSetUp(), "signedIn", s != null,
                    "device", s != null ? s.device : Auth.guessDevice(ua)));
            return true;
        }
        if (p.equals("/api/auth/code") && req.post()) {
            auth.requestCode();
            json(req, Json.obj("ok", true));
            return true;
        }
        if ((p.equals("/api/auth/login") || p.equals("/api/auth/setup")) && req.post()) {
            JSONObject b = body(req);
            Auth.Result r = p.equals("/api/auth/login")
                    ? auth.login(b.opt("password"), b.opt("device"), req.ip, ua)
                    : auth.setup(b.opt("code"), b.opt("password"), b.opt("device"), req.ip, ua);
            if (!r.ok()) {
                json(req, Json.obj("ok", false, "error", r.error), r.status);
                return true;
            }
            // A reset signs out everyone else.
            if (p.equals("/api/auth/setup")) {
                List<String> others = new ArrayList<>();
                for (Client c : clients) others.add(c.sess.id);
                kick(others);
                notice("Password set on " + r.session.device, 5);
            }
            json(req, Json.obj("ok", true), 200, "Set-Cookie", Auth.setCookie(r.token));
            return true;
        }
        return false;
    }

    private static String words(String id) {
        return id.replaceAll("([A-Z])", " $1").toLowerCase();
    }

    private void logged(Http.Request req, Auth.Session sess, JSONObject r, String text) throws IOException {
        if (r.optBoolean("ok", true)) record(sess, "action", text);
        json(req, r);
    }

    private void api(Http.Request req, Auth.Session sess) throws IOException {
        String p = req.path;
        boolean post = req.post();

        if (p.equals("/api/admin")) {
            Set<String> online = new java.util.HashSet<>();
            for (Client c : clients) online.add(c.sess.id);
            List<Auth.Session> list = auth.sessions();
            list.sort((a, b) -> Long.compare(b.created, a.created));
            JSONArray ss = new JSONArray();
            for (Auth.Session s : list) ss.put(Json.put(s.json(), "online", online.contains(s.id)));
            json(req, Json.obj("ok", true, "me", sess.id, "sessions", ss, "history", auth.history(500)));
        } else if (p.equals("/api/admin/revoke") && post) {
            Auth.Session s = auth.revoke(body(req).optString("id"));
            if (s == null) {
                json(req, Json.obj("ok", false, "error", "Already signed out"));
                return;
            }
            record(sess, "auth", "Signed out " + s.device);
            kick(List.of(s.id));
            json(req, Json.obj("ok", true));
        } else if (p.equals("/api/auth/logout") && post) {
            auth.revoke(sess.id);
            record(sess, "auth", "Signed out");
            kick(List.of(sess.id));
            json(req, Json.obj("ok", true), 200, "Set-Cookie", Auth.CLEAR_COOKIE);
        } else if (p.equals("/api/auth/password") && post) {
            JSONObject b = body(req);
            String[] error = new String[1];
            List<String> dropped = auth.changePassword(sess, b.opt("current"), b.opt("next"), error);
            if (dropped == null) {
                json(req, Json.obj("ok", false, "error", error[0]));
                return;
            }
            kick(dropped);
            json(req, Json.obj("ok", true, "signedOut", dropped.size()));
        } else if (p.equals("/api/stats")) {
            JSONObject s = stats.read(poll, req.param("full").equals("1"));
            if (s == null) offline(req);
            else json(req, s);
        } else if (p.equals("/api/device")) {
            JSONObject d = stats.deviceInfo(mgmt);
            if (d == null) offline(req);
            else json(req, d);
        } else if (p.equals("/api/watch")) {
            // Viewing stats, worked out by the launcher's StatsProvider. The widget and the Watching
            // page share one read for 20 s.
            JSONObject w;
            synchronized (this) {
                w = watchCache != null && System.currentTimeMillis() - watchAt <= 20_000 ? watchCache : null;
            }
            if (w == null) {
                String out = watcher.run("content read --uri content://com.argon.launcher.stats/stats.json", 8000);
                if (!out.trim().startsWith("{")) {
                    offline(req);
                    return;
                }
                w = Json.parse(out);
                synchronized (this) {
                    watchCache = w;
                    watchAt = System.currentTimeMillis();
                }
            }
            json(req, w);
        } else if (p.equals("/api/files")) {
            boolean on;
            synchronized (this) {
                on = connected;
            }
            if (!on) offline(req);
            else json(req, TvFiles.list(mgmt, req.param("path").isEmpty() ? "/sdcard" : req.param("path")));
        } else if (p.equals("/api/files/raw")) {
            boolean inline = req.param("inline").equals("1");
            if (!inline) record(sess, "action", "Downloaded " + req.param("path"));
            TvFiles.download(link, req, req.param("path"), inline);
        } else if (p.equals("/api/files/upload") && post) {
            logged(req, sess, TvFiles.upload(link, req.param("dir"), req.param("name"), req),
                    "Uploaded " + req.param("name") + " to " + req.param("dir"));
        } else if (p.equals("/api/files/delete") && post) {
            String path = body(req).optString("path");
            logged(req, sess, TvFiles.remove(mgmt, path), "Deleted " + path);
        } else if (p.equals("/api/settings")) {
            JSONObject s = TvSettings.read(mgmt);
            if (s == null) offline(req);
            else json(req, s);
        } else if (p.equals("/api/settings/set") && post) {
            JSONObject b = body(req);
            Object value = b.opt("value");
            String v = Boolean.TRUE.equals(value) ? "on" : Boolean.FALSE.equals(value) ? "off"
                    : value instanceof Number ? TvSettings.number(((Number) value).doubleValue()) : String.valueOf(value);
            logged(req, sess, TvSettings.write(mgmt, b.optString("id"), value),
                    "Set " + words(b.optString("id")) + " to " + v);
        } else if (p.equals("/api/settings/open") && post) {
            String page = body(req).optString("page");
            logged(req, sess, TvSettings.openPage(act, page), "Opened " + page + " settings on the TV");
        } else if (p.equals("/api/settings/power") && post) {
            String action = body(req).optString("action");
            logged(req, sess, TvSettings.power(act, action),
                    action.equals("reboot") ? "Restarted the TV" : "Started the screen saver");
        } else if (p.equals("/api/apps")) {
            JSONObject h = TvHome.state(mgmt);
            Map<String, String> labels = new HashMap<>();
            JSONArray apps = h.optBoolean("ok") ? h.optJSONObject("state").optJSONArray("apps") : null;
            for (int i = 0; apps != null && i < apps.length(); i++)
                labels.put(apps.optJSONObject(i).optString("pkg"), apps.optJSONObject(i).optString("label"));
            JSONArray list = TvSettings.listApps(mgmt, labels);
            if (list == null) offline(req);
            else json(req, list);
        } else if (p.equals("/api/apps/action") && post) {
            JSONObject b = body(req);
            String pkg = b.optString("pkg"), action = b.optString("action");
            String name = NAMES.containsKey(pkg) ? NAMES.get(pkg) : prettify(pkg);
            logged(req, sess, TvSettings.appAction(mgmt, pkg, action),
                    APP_ACTIONS.getOrDefault(action, action) + " " + name);
        } else if (p.equals("/api/home")) {
            JSONObject h = TvHome.state(mgmt);
            if (!h.optBoolean("ok")) {
                json(req, h, "TV not connected".equals(h.optString("error")) ? 503 : 200);
                return;
            }
            JSONObject state = h.optJSONObject("state");
            String team = state.optString("team", "");
            json(req, Json.obj("ok", true, "state", state, "team", team.isEmpty() ? null : TvHome.teamName(team)));
        } else if (p.equals("/api/home/set") && post) {
            JSONObject b = body(req);
            String key = b.optString("key"), value = b.optString("value", "");
            logged(req, sess, TvHome.set(mgmt, key, value), "Home screen: set " + key.replace("_", " ") + " to "
                    + (value.isEmpty() ? "nothing" : value));
        } else if (p.equals("/api/home/banner")) {
            byte[] png = TvHome.banner(mgmt, req.param("pkg"));
            if (png == null) req.send(404, "image/png", new byte[0]);
            else req.send(200, "image/png", png, "Cache-Control", "max-age=86400");
        } else if (p.equals("/api/home/teams")) {
            json(req, TvHome.searchTeams(req.param("q")));
        } else if (p.equals("/api/home/places")) {
            json(req, TvHome.searchPlaces(req.param("q")));
        } else if (p.equals("/api/home/wallpaper") && post) {
            logged(req, sess, wallpaper(req), "Home screen: set a new wallpaper");
        } else if (p.equals("/api/smarthome")) {
            json(req, Json.put(home.state(), "ok", true, "regions", SmartHome.regions()));
        } else if (p.equals("/api/smarthome/pair") && post) {
            home.pairHue();
            notice("Press the round button on top of the Hue Bridge", 60);
            logged(req, sess, Json.obj("ok", true), "Started pairing the Hue Bridge");
        } else if (p.equals("/api/smarthome/plugs") && post) {
            JSONObject b = body(req);
            try {
                int n = home.addPlugs(b.optString("accessId"), b.optString("secret"), b.optString("region", "in"));
                logged(req, sess, Json.obj("ok", true, "found", n), "Added " + n + (n == 1 ? " plug" : " plugs") + " from Tuya");
            } catch (IOException e) {
                json(req, Json.obj("ok", false, "error", e.getMessage()));
            }
        } else if (p.equals("/api/smarthome/device") && post) {
            JSONObject b = body(req);
            home.setDevice(b.optString("id"), b);
            json(req, Json.obj("ok", true));
        } else if (p.equals("/api/screen")) {
            screen(req, req.param("full").equals("1"));
        } else if (p.equals("/api/notice") && post) {
            String text = body(req).optString("text").trim();
            if (text.isEmpty() || text.length() > 500) {
                json(req, Json.obj("ok", false, "error", text.isEmpty() ? "Nothing to show" : "That's too long for a notice"));
                return;
            }
            // NoticeReceiver turns a literal \n into a new line (am can't pass a real one).
            String out = act.run("am broadcast -a com.argon.launcher.NOTICE -p com.argon.launcher --es title "
                    + quote("From " + sess.device) + " --es text " + quote(text.replace("\n", "\\n")) + " --ei seconds 12", 8000);
            logged(req, sess, out.contains("Broadcast completed") ? Json.obj("ok", true) : Json.obj("ok", false, "error", "TV not connected"),
                    "Showed a notice on the TV: " + text);
        } else if (p.equals("/api/continue")) {
            List<ContinueFeed.Item> items = ContinueFeed.load(context, 10);
            continueItems = items;
            JSONArray a = new JSONArray();
            for (ContinueFeed.Item it : items) {
                a.put(Json.obj("title", it.title, "subtitle", it.subtitle, "app", it.app, "pkg", it.pkg, "progress", (double) it.progress));
            }
            json(req, Json.obj("ok", true, "items", a));
        } else if (p.equals("/api/continue/image")) {
            byte[] jpg = continueArt(req.param("i"));
            if (jpg == null) req.send(404, "text/plain", "no picture".getBytes(StandardCharsets.UTF_8));
            else req.send(200, "image/jpeg", jpg, "Cache-Control", "max-age=600");
        } else if (p.equals("/api/continue/resume") && post) {
            int i = body(req).optInt("index", -1);
            List<ContinueFeed.Item> items = continueItems;
            if (i < 0 || i >= items.size()) {
                json(req, Json.obj("ok", false, "error", "That show isn't in Continue Watching any more"));
                return;
            }
            ContinueFeed.Item it = items.get(i);
            String out = act.run("am start " + quote(it.intentUri), 10000);
            boolean ok = out.contains("Starting") && !out.contains("Error");
            logged(req, sess, ok ? Json.obj("ok", true) : Json.obj("ok", false, "error", "Couldn't start it on the TV"),
                    "Resumed " + it.title + " on " + it.app);
        } else {
            json(req, Json.obj("ok", false, "error", "Not found"), 404);
        }
    }
}
