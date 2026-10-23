package com.argon.launcher.smarthome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The room's lights and plugs, kept in the web remote's process (it runs whenever the TV is on): one connection to
 * the Hue Bridge and one to each plug, straight over the LAN, no cloud. The web remote calls this directly; the home
 * screen, in the launcher's main process, through SmartHomeProvider.
 *
 * files/smarthome.json holds the bridge's key and pinned certificate, the plugs' ids and local keys, and the names,
 * icons and order the remote sets. files/smarthome_energy.json holds each plug's kWh per day (last 40 days),
 * worked out from the power it reports.
 */
public final class SmartHome {
    private static final String TAG = "SmartHome";
    public static final Set<String> ICONS = Set.of("lightbar", "bulb", "lamp", "leaf", "plug");

    public interface Listener {
        void changed();
    }

    private static SmartHome instance;

    private final Context context;
    private final File file, energyFile;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "smarthome");
        t.setDaemon(true);
        return t;
    });
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private JSONObject config;
    private Hue hue;
    private final Map<String, Tuya> plugs = new LinkedHashMap<>();
    private final Map<String, double[]> metered = new LinkedHashMap<>();   // watts and when, for the energy sums
    private JSONObject energy;
    private ScheduledFuture<?> notify, save;
    private String lastSig = "";
    private long lastPublished;
    private volatile int pairLeft;
    private volatile String pairError = "";

    public static synchronized SmartHome get(Context c) {
        if (instance == null) instance = new SmartHome(c.getApplicationContext());
        return instance;
    }

    private SmartHome(Context c) {
        context = c;
        file = new File(c.getFilesDir(), "smarthome.json");
        energyFile = new File(c.getFilesDir(), "smarthome_energy.json");
        config = read(file);
        energy = read(energyFile);
        connect();
        Tuya.listen(this::found);
        timers.scheduleWithFixedDelay(this::meter, 1, 1, TimeUnit.MINUTES);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // MARK: - Connections

    private synchronized void connect() {
        if (hue != null) hue.stop();
        hue = null;
        for (Tuya p : plugs.values()) p.stop();
        plugs.clear();
        JSONObject h = config.optJSONObject("hue");
        if (h != null && !h.optString("key").isEmpty()) {
            try {
                hue = new Hue(h.optString("host"), h.optString("key"), h.optString("pin"), this::changed);
                hue.start();
            } catch (java.security.GeneralSecurityException e) {
                Log.e(TAG, "hue: " + e);
            }
        }
        JSONArray ps = config.optJSONArray("plugs");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            Tuya t = new Tuya(p.optString("id"), p.optString("key"), p.optString("ip", null), plug -> changed());
            plugs.put(t.id, t);
            t.start();
        }
    }

    // A Tuya announcement: a plug's address, saved if the router moved it.
    private void found(String id, String ip) {
        Tuya t;
        synchronized (this) {
            t = plugs.get(id);
        }
        if (t == null || ip.equals(t.ip)) return;
        Log.i(TAG, "plug " + id + " at " + ip);
        t.ip = ip;
        synchronized (this) {
            JSONArray ps = config.optJSONArray("plugs");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                if (id.equals(ps.optJSONObject(i).optString("id"))) put(ps.optJSONObject(i), "ip", ip);
            }
            saveConfig();
        }
    }

    private void changed() {
        meter();
        synchronized (this) {
            // The bridge found again at a new address.
            JSONObject h = config.optJSONObject("hue");
            if (hue != null && h != null && !hue.host.equals(h.optString("host"))) {
                put(h, "host", hue.host);
                saveConfig();
            }
            if (notify != null && !notify.isDone()) return;
            notify = timers.schedule(this::publish, 40, TimeUnit.MILLISECONDS);
        }
    }

    // Power fields move every few seconds while a plug draws anything; they don't change what's on screen enough to
    // redraw the TV for (it drops frames redrawing a whole hub).
    private static final String[] POWER = {"watts", "volts", "amps", "today", "week", "month", "days"};

    /**
     * Tells the pages and the home screen: at once when something they show changed (on or off, level, colour,
     * reachability, names, pairing), at most every 10 s when only power readings did.
     */
    private void publish() {
        JSONObject s = state();
        s.remove("watts");
        JSONArray ds = s.optJSONArray("devices");
        for (int i = 0; ds != null && i < ds.length(); i++) for (String k : POWER) ds.optJSONObject(i).remove(k);
        String sig = s.toString();
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (sig.equals(lastSig) && now - lastPublished < 10_000) {
                // Only power changed: once more when the 10 s are up, unless something else comes first.
                notify = timers.schedule(this::publish, lastPublished + 10_000 - now, TimeUnit.MILLISECONDS);
                return;
            }
            lastSig = sig;
            lastPublished = now;
        }
        for (Listener l : listeners) l.changed();
        context.getContentResolver().notifyChange(SmartHomeProvider.STATE, null);
    }

    // MARK: - State

    private static final class Device {
        String id, kind, name, icon;
        Hue.Light light;
        Tuya plug;
    }

    private synchronized List<Device> devices() {
        Map<String, Device> all = new LinkedHashMap<>();
        if (hue != null) {
            for (Hue.Light l : hue.lights()) {
                Device d = new Device();
                d.id = l.id;
                d.kind = "light";
                d.light = l;
                d.name = l.name;
                d.icon = l.product.toLowerCase().contains("play") ? "lightbar" : "bulb";
                all.put(d.id, d);
            }
        }
        JSONArray ps = config.optJSONArray("plugs");
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            Device d = new Device();
            d.id = p.optString("id");
            d.kind = "plug";
            d.plug = plugs.get(d.id);
            d.name = p.optString("name", "Plug");
            d.icon = "plug";
            if (d.plug != null) all.put(d.id, d);
        }
        JSONObject meta = config.optJSONObject("devices");
        List<Device> out = new ArrayList<>();
        JSONArray order = config.optJSONArray("order");
        for (int i = 0; order != null && i < order.length(); i++) {
            Device d = all.remove(order.optString(i));
            if (d != null) out.add(d);
        }
        out.addAll(all.values());
        out.removeIf(d -> {
            JSONObject m = meta == null ? null : meta.optJSONObject(d.id);
            if (m == null) return false;
            if (!m.optString("name").isEmpty()) d.name = m.optString("name");
            if (ICONS.contains(m.optString("icon"))) d.icon = m.optString("icon");
            return m.optBoolean("hidden");
        });
        return out;
    }

    /** Everything the TV and the remote show, as one message ("t": "home" on the remote's live connection). */
    public JSONObject state() {
        JSONArray list = new JSONArray();
        int on = 0;
        double watts = 0;
        boolean hueOnline;
        synchronized (this) {
            hueOnline = hue != null && hue.online();
        }
        for (Device d : devices()) {
            JSONObject o = obj("id", d.id, "kind", d.kind, "name", d.name, "icon", d.icon);
            boolean isOn;
            if (d.light != null) {
                Hue.Light l = d.light;
                isOn = l.on;
                Palette.Preset p = Palette.nearest(l.whiteMode || !l.color, l.mirek, l.x, l.y);
                int rgb = l.rgb();
                put(o, "on", l.on, "online", hueOnline && l.reachable, "bri", (int) Math.round(l.bri),
                        "rgb", Palette.hex(rgb), "color", p == null ? "" : p.id,
                        "colorName", p != null ? p.name : l.whiteMode ? "White" : "Colour", "colors", l.color, "whites", l.white);
            } else {
                Tuya t = d.plug;
                isOn = t.on;
                watts += t.watts();
                double[] e = energy(d.id);
                put(o, "on", t.on, "online", t.online, "watts", round(t.watts(), 1), "volts", round(t.decivolts / 10.0, 0),
                        "amps", round(t.on ? t.milliamps / 1000.0 : 0, 2), "timer", t.countdown, "since", t.on ? t.changedAt : 0,
                        "today", round(e[0], 3), "week", round(e[1], 3), "month", round(e[2], 3), "days", days(d.id, 7),
                        "rgb", "#f2b04a");
            }
            if (isOn) on++;
            list.put(o);
        }
        JSONObject h = config.optJSONObject("hue");
        return obj("t", "home", "room", config.optString("room", "Living room"), "configured", list.length() > 0,
                "devices", list, "on", on, "watts", round(watts, 1), "scenes", scenes(),
                "hue", obj("paired", h != null && !h.optString("key").isEmpty(), "online", hueOnline, "host", h == null ? "" : h.optString("host")),
                "pairing", obj("left", pairLeft, "error", pairError), "plugsSet", plugs.size());
    }

    // MARK: - Scenes

    private static final String[][] SCENES = {
            // id, name, light brightness, light colour, lamps on
            {"movie", "Movie", "15", "violet", "0"},
            {"unwind", "Unwind", "50", "warm", "1"},
            {"night", "Nighttime", "5", "red", "0"},
            {"bright", "Bright", "100", "daylight", "1"},
    };

    private JSONArray scenes() {
        JSONArray out = new JSONArray();
        List<Device> ds = devices();
        boolean lamps = false;
        for (Device d : ds) lamps |= "lamp".equals(d.icon);
        for (String[] s : SCENES) {
            JSONArray colors = new JSONArray();
            for (Device d : ds) {
                if (d.light != null) colors.put(Palette.hex(Palette.preset(s[3]).rgb));
                else if ("lamp".equals(d.icon)) colors.put(s[4].equals("1") ? "#f2b04a" : "");
            }
            String sub = s[2].equals("100") ? "All on, " + Palette.preset(s[3]).name.toLowerCase()
                    : "Lights at " + s[2] + "%" + (s[0].equals("unwind") ? ", warm" : s[0].equals("night") ? ", deep red" : "");
            if (lamps && !s[2].equals("100")) sub += s[4].equals("1") ? ", lamp on" : ", lamp off";
            out.put(obj("id", s[0], "name", s[1], "sub", sub, "colors", colors));
        }
        return out;
    }

    // MARK: - Commands

    /**
     * One change, from the TV or the remote: {"id", "on"|"toggle"|"bri"|"color"|"timer"}, {"all": on},
     * {"scene": id} or {"dim": true}. Returns what went wrong, or null.
     */
    public String command(JSONObject m) {
        if (m.has("scene")) return scene(m.optString("scene"));
        if (m.has("all")) {
            boolean on = m.optBoolean("all");
            for (Device d : devices()) power(d, on);
            return null;
        }
        if (m.optBoolean("dim")) {
            for (Device d : devices()) {
                if (d.light != null && d.light.on && d.light.bri > 20) hue().set(d.id, obj("dimming", obj("brightness", 20), "dynamics", obj("duration", 400)));
            }
            return null;
        }
        Device d = null;
        for (Device x : devices()) if (x.id.equals(m.optString("id"))) d = x;
        if (d == null) return "That light or plug isn't set up";
        if (m.has("on") || m.optBoolean("toggle")) {
            boolean on = m.has("on") ? m.optBoolean("on") : !(d.light != null ? d.light.on : d.plug.on);
            return power(d, on) ? null : d.name + " isn't answering";
        }
        if (m.has("bri") && d.light != null) {
            int b = Math.max(1, Math.min(100, m.optInt("bri")));
            hue().set(d.id, obj("on", obj("on", true), "dimming", obj("brightness", b)));
            return null;
        }
        if (m.has("color") && d.light != null) {
            Palette.Preset p = Palette.preset(m.optString("color"));
            if (p == null) return "Unknown colour";
            JSONObject body = color(d.light, p);
            if (body == null) return d.name + " can't show that colour";
            hue().set(d.id, put(body, "on", obj("on", true)));
            return null;
        }
        if (m.has("timer") && d.plug != null) {
            int s = Math.max(0, Math.min(86_400, m.optInt("timer")));
            return d.plug.set(obj("9", s)) ? null : d.name + " isn't answering";
        }
        return "Nothing to do";
    }

    private synchronized Hue hue() {
        return hue;
    }

    private boolean power(Device d, boolean on) {
        if (d.light != null) {
            hue().set(d.id, obj("on", obj("on", on)));
            return true;
        }
        return d.plug.set(obj("1", on));
    }

    private static JSONObject color(Hue.Light l, Palette.Preset p) {
        if (p.white() && l.white) return obj("color_temperature", obj("mirek", p.mirek));
        if (l.color) return obj("color", obj("xy", obj("x", p.x, "y", p.y)));
        return null;
    }

    private String scene(String id) {
        String[] s = null;
        for (String[] x : SCENES) if (x[0].equals(id)) s = x;
        if (s == null) return "Unknown scene";
        for (Device d : devices()) {
            if (d.light != null) {
                JSONObject body = color(d.light, Palette.preset(s[3]));
                if (body == null) body = new JSONObject();
                hue().set(d.id, put(body, "on", obj("on", true), "dimming", obj("brightness", Integer.parseInt(s[2])),
                        "dynamics", obj("duration", 800)));
            } else if ("lamp".equals(d.icon)) {
                d.plug.set(obj("1", s[4].equals("1")));
            }
        }
        return null;
    }

    // MARK: - Energy

    // Adds each plug's power since the last reading to today's total (on every change, and once a minute).
    private void meter() {
        long now = System.currentTimeMillis();
        String day = LocalDate.now().toString();
        boolean dirty = false;
        synchronized (this) {
            for (Tuya t : plugs.values()) {
                double[] last = metered.get(t.id);
                double w = t.online ? t.watts() : 0;
                if (last != null && last[0] > 0) {
                    double kwh = last[0] * (now - last[1]) / 3_600_000.0 / 1000.0;
                    JSONObject days = energy.optJSONObject(t.id);
                    if (days == null) put(energy, t.id, days = new JSONObject());
                    put(days, day, days.optDouble(day, 0) + kwh);
                    dirty = true;
                }
                metered.put(t.id, new double[] {w, now});
            }
            if (dirty && (save == null || save.isDone())) save = timers.schedule(this::saveEnergy, 5, TimeUnit.MINUTES);
        }
    }

    /** Today, this week (from Monday) and this month, in kWh. */
    private synchronized double[] energy(String id) {
        JSONObject days = energy.optJSONObject(id);
        double[] out = new double[3];
        if (days == null) return out;
        LocalDate today = LocalDate.now(), monday = today.with(DayOfWeek.MONDAY);
        for (Iterator<String> it = days.keys(); it.hasNext(); ) {
            String k = it.next();
            LocalDate d;
            try {
                d = LocalDate.parse(k);
            } catch (RuntimeException e) {
                continue;
            }
            double v = days.optDouble(k, 0);
            if (d.equals(today)) out[0] += v;
            if (!d.isBefore(monday) && !d.isAfter(today)) out[1] += v;
            if (d.getYear() == today.getYear() && d.getMonth() == today.getMonth()) out[2] += v;
        }
        return out;
    }

    /** The last `n` days' kWh, oldest first, today last. */
    private synchronized JSONArray days(String id, int n) {
        JSONObject days = energy.optJSONObject(id);
        JSONArray out = new JSONArray();
        LocalDate today = LocalDate.now();
        for (int i = n - 1; i >= 0; i--) {
            try {
                out.put(round(days == null ? 0 : days.optDouble(today.minusDays(i).toString(), 0), 4));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private synchronized void saveEnergy() {
        String oldest = LocalDate.now().minusDays(40).toString();
        for (Iterator<String> it = energy.keys(); it.hasNext(); ) {
            JSONObject days = energy.optJSONObject(it.next());
            if (days == null) continue;
            for (Iterator<String> d = days.keys(); d.hasNext(); ) if (d.next().compareTo(oldest) < 0) d.remove();
        }
        write(energyFile, energy);
    }

    // MARK: - Setup (from the web remote's Home screen page)

    /**
     * Pairs with the Hue Bridge: finds it, then asks for a key every 2 s for a minute while someone presses its
     * button. Progress shows in state().pairing.
     */
    public void pairHue() {
        if (pairLeft > 0) return;
        pairLeft = 60;
        pairError = "";
        changed();
        Thread t = new Thread(() -> {
            try {
                JSONObject h = config.optJSONObject("hue");
                String host = h != null && !h.optString("host").isEmpty() ? h.optString("host") : Hue.discover();
                if (host == null) host = Hue.discover();
                if (host == null) throw new IOException("No Hue Bridge found on this network");
                String bridge = Hue.config(host).optString("bridgeid");
                long end = System.currentTimeMillis() + 60_000;
                String key = null;
                while (key == null && System.currentTimeMillis() < end) {
                    key = Hue.pair(host);
                    pairLeft = (int) Math.max(1, (end - System.currentTimeMillis()) / 1000);
                    changed();
                    if (key == null) Thread.sleep(2000);
                }
                if (key == null) throw new IOException("The bridge's button wasn't pressed in time");
                String pin = Hue.pin(host, bridge);
                synchronized (this) {
                    put(config, "hue", obj("host", host, "key", key, "pin", pin, "bridge", bridge));
                    saveConfig();
                    connect();
                }
                Log.i(TAG, "paired with Hue Bridge " + bridge + " at " + host);
            } catch (IOException | JSONException | java.security.GeneralSecurityException e) {
                pairError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                Log.w(TAG, "hue pairing: " + pairError);
            } catch (InterruptedException e) {
                return;
            } finally {
                pairLeft = 0;
                changed();
            }
        }, "hue-pair");
        t.setDaemon(true);
        t.start();
    }

    public static JSONArray regions() {
        JSONArray a = new JSONArray();
        for (String[] r : TuyaCloud.REGIONS) a.put(obj("id", r[0], "name", r[1]));
        return a;
    }

    /** Fetches the plugs' local keys from Tuya's cloud and adds them (or updates their keys). Returns how many. */
    public int addPlugs(String accessId, String secret, String region) throws IOException {
        List<TuyaCloud.Plug> found = new TuyaCloud(accessId, secret, region).plugs();
        synchronized (this) {
            JSONArray ps = config.optJSONArray("plugs");
            if (ps == null) put(config, "plugs", ps = new JSONArray());
            for (TuyaCloud.Plug p : found) {
                JSONObject have = null;
                for (int i = 0; i < ps.length(); i++) if (p.id.equals(ps.optJSONObject(i).optString("id"))) have = ps.optJSONObject(i);
                if (have == null) ps.put(obj("id", p.id, "key", p.key, "name", p.name));
                else put(have, "key", p.key);
            }
            saveConfig();
            connect();
        }
        changed();
        return found.size();
    }

    /** Name, icon, hidden and position of a device, as the remote edits them. */
    public synchronized void setDevice(String id, JSONObject m) {
        JSONObject meta = config.optJSONObject("devices");
        if (meta == null) put(config, "devices", meta = new JSONObject());
        JSONObject d = meta.optJSONObject(id);
        if (d == null) put(meta, id, d = new JSONObject());
        if (m.has("name")) put(d, "name", m.optString("name").trim());
        if (m.has("icon") && ICONS.contains(m.optString("icon"))) put(d, "icon", m.optString("icon"));
        if (m.has("hidden")) put(d, "hidden", m.optBoolean("hidden"));
        if (m.has("order")) put(config, "order", m.optJSONArray("order"));
        if (m.has("room")) put(config, "room", m.optString("room").trim());
        saveConfig();
        changed();
    }

    // MARK: - Files and JSON

    private void saveConfig() {
        write(file, config);
    }

    private static JSONObject read(File f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    // Through a temporary file, so a crash mid-write never leaves half a file.
    private static void write(File f, JSONObject o) {
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "can't save " + f.getName() + ": " + e);
            return;
        }
        if (!part.renameTo(f)) Log.e(TAG, "can't save " + f.getName());
    }

    static JSONObject obj(Object... kv) {
        return put(new JSONObject(), kv);
    }

    static JSONObject put(JSONObject o, Object... kv) {
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
