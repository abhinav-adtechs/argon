package com.argon.launcher.smarthome;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// A Philips Hue Bridge on the LAN, through its local API (CLIP v2 over HTTPS). Its certificate is self-signed, so
// it's pinned: the SHA-256 saved when pairing, anything else is refused. Changes made anywhere (the Hue app, Google
// Home, a dimmer switch) come in on the bridge's event stream, so what the TV shows is never polled.
final class Hue {
    private static final String TAG = "SmartHome";

    interface Listener {
        void changed();
    }

    static final class Light {
        final String id;
        String name = "", owner = "", product = "";
        boolean on, reachable = true, color, white, whiteMode;
        double bri = 100, x = 0.45, y = 0.41;
        int mirek = 370;

        Light(String id) {
            this.id = id;
        }

        int rgb() {
            return whiteMode || !color ? Palette.mirekRgb(mirek) : Palette.xyRgb(x, y);
        }
    }

    volatile String host;
    private final String key, pin;
    private final Listener listener;
    private final Map<String, Light> lights = new LinkedHashMap<>();
    private final Map<String, JSONObject> pending = new LinkedHashMap<>();
    private final SSLSocketFactory ssl;
    private volatile boolean stopped, online;
    private boolean sending;
    private Thread thread;
    private HttpURLConnection streaming;

    Hue(String host, String key, String pin, Listener listener) throws GeneralSecurityException {
        this.host = host;
        this.key = key;
        this.pin = pin;
        this.listener = listener;
        ssl = pinned(pin);
    }

    void start() {
        thread = new Thread(this::loop, "hue");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        HttpURLConnection c;
        synchronized (this) {
            c = streaming;
        }
        if (c != null) c.disconnect();
        if (thread != null) thread.interrupt();
    }

    boolean online() {
        return online;
    }

    synchronized List<Light> lights() {
        return new ArrayList<>(lights.values());
    }

    synchronized Light light(String id) {
        return lights.get(id);
    }

    // Loads the lights, then follows the event stream until it drops; then again, waiting 1 s, doubling to 30 s.
    private void loop() {
        long wait = 1000;
        int misses = 0;
        while (!stopped) {
            try {
                load();
                setOnline(true);
                wait = 1000;
                misses = 0;
                stream();
            } catch (IOException | JSONException e) {
                if (!stopped) Log.w(TAG, "hue " + host + ": " + e.getMessage());
                // The router may have given the bridge a new address: look for it after a few misses.
                if (++misses % 3 == 0) {
                    String found = discover();
                    if (found != null && !found.equals(host)) {
                        Log.i(TAG, "hue bridge moved to " + found);
                        host = found;
                        listener.changed();
                    }
                }
            }
            setOnline(false);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void setOnline(boolean on) {
        if (online == on) return;
        online = on;
        listener.changed();
    }

    private void load() throws IOException, JSONException {
        JSONArray ls = get("/clip/v2/resource/light").getJSONArray("data");
        JSONArray ds = get("/clip/v2/resource/device").getJSONArray("data");
        JSONArray zs = get("/clip/v2/resource/zigbee_connectivity").getJSONArray("data");
        Map<String, String> products = new HashMap<>(), status = new HashMap<>();
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            products.put(d.getString("id"), d.optJSONObject("product_data") == null ? "" : d.getJSONObject("product_data").optString("product_name"));
        }
        for (int i = 0; i < zs.length(); i++) {
            JSONObject z = zs.getJSONObject(i);
            JSONObject owner = z.optJSONObject("owner");
            if (owner != null) status.put(owner.optString("rid"), z.optString("status"));
        }
        Map<String, Light> next = new LinkedHashMap<>();
        for (int i = 0; i < ls.length(); i++) {
            JSONObject o = ls.getJSONObject(i);
            Light l = new Light(o.getString("id"));
            JSONObject owner = o.optJSONObject("owner");
            l.owner = owner == null ? "" : owner.optString("rid");
            l.product = products.containsKey(l.owner) ? products.get(l.owner) : "";
            l.reachable = !status.containsKey(l.owner) || "connected".equals(status.get(l.owner));
            l.color = o.has("color");
            l.white = o.has("color_temperature");
            l.whiteMode = !l.color;
            apply(l, o);
            next.put(l.id, l);
        }
        synchronized (this) {
            lights.clear();
            lights.putAll(next);
        }
        listener.changed();
    }

    // A light's fields from the bridge (a full resource or the changed part of one).
    private static void apply(Light l, JSONObject o) {
        JSONObject meta = o.optJSONObject("metadata"), on = o.optJSONObject("on"), dim = o.optJSONObject("dimming"),
                color = o.optJSONObject("color"), ct = o.optJSONObject("color_temperature");
        if (meta != null && meta.has("name")) l.name = meta.optString("name");
        if (on != null && on.has("on")) l.on = on.optBoolean("on");
        if (dim != null && dim.has("brightness")) l.bri = dim.optDouble("brightness", l.bri);
        if (color != null && color.optJSONObject("xy") != null) {
            l.x = color.optJSONObject("xy").optDouble("x", l.x);
            l.y = color.optJSONObject("xy").optDouble("y", l.y);
        }
        if (ct != null) {
            if (ct.has("mirek") && !ct.isNull("mirek")) l.mirek = ct.optInt("mirek", l.mirek);
            if (ct.has("mirek_valid")) l.whiteMode = ct.optBoolean("mirek_valid");
        }
    }

    private void stream() throws IOException, JSONException {
        HttpURLConnection c = open("/eventstream/clip/v2", "GET");
        c.setRequestProperty("Accept", "text/event-stream");
        // The bridge sends nothing while nothing changes; a quiet half hour reconnects (and reloads) to be sure.
        c.setReadTimeout(30 * 60_000);
        synchronized (this) {
            streaming = c;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.startsWith("data:")) continue;
                if (onEvents(new JSONArray(line.substring(5).trim()))) return;
            }
        } catch (SocketTimeoutException e) {
            // Reconnects.
        } finally {
            synchronized (this) {
                streaming = null;
            }
            c.disconnect();
        }
    }

    // Returns true when lights were added or removed (the loop reloads them).
    private boolean onEvents(JSONArray events) {
        boolean changed = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;
            JSONArray data = ev.optJSONArray("data");
            for (int j = 0; data != null && j < data.length(); j++) {
                JSONObject d = data.optJSONObject(j);
                if (d == null) continue;
                String type = d.optString("type");
                if (!"update".equals(ev.optString("type"))) {
                    if (type.equals("light")) return true;
                    continue;
                }
                synchronized (this) {
                    if (type.equals("light")) {
                        Light l = lights.get(d.optString("id"));
                        if (l == null) continue;
                        apply(l, d);
                        changed = true;
                    } else if (type.equals("zigbee_connectivity") && d.optJSONObject("owner") != null) {
                        String owner = d.optJSONObject("owner").optString("rid");
                        for (Light l : lights.values()) {
                            if (!l.owner.equals(owner)) continue;
                            l.reachable = "connected".equals(d.optString("status"));
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) listener.changed();
        return false;
    }

    // MARK: - Commands

    /**
     * Changes a light. The change shows at once; the request goes out after the ones before it, and a light's
     * commands that pile up (a dragged slider) merge into one, so the bridge never gets more than ~12 a second.
     */
    void set(String id, JSONObject body) {
        synchronized (this) {
            Light l = lights.get(id);
            if (l == null) return;
            apply(l, body);
            if (body.has("color")) l.whiteMode = false;
            if (body.has("color_temperature")) l.whiteMode = true;
            JSONObject merged = pending.get(id);
            if (merged == null) pending.put(id, body);
            else for (Iterator<String> it = body.keys(); it.hasNext(); ) {
                String k = it.next();
                try {
                    merged.put(k, body.get(k));
                } catch (JSONException ignored) {
                }
            }
            if (!sending) {
                sending = true;
                Thread t = new Thread(this::send, "hue-send");
                t.setDaemon(true);
                t.start();
            }
        }
        listener.changed();
    }

    private void send() {
        while (true) {
            String id;
            JSONObject body;
            synchronized (this) {
                if (pending.isEmpty()) {
                    sending = false;
                    return;
                }
                id = pending.keySet().iterator().next();
                body = pending.remove(id);
            }
            try {
                HttpURLConnection c = open("/clip/v2/resource/light/" + id, "PUT");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code >= 300) Log.w(TAG, "hue put " + code + " " + body);
                c.disconnect();
                Thread.sleep(80);
            } catch (IOException e) {
                Log.w(TAG, "hue put: " + e.getMessage());
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // MARK: - HTTP

    private JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection c = open(path, "GET");
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + path).openConnection();
        c.setSSLSocketFactory(ssl);
        // The bridge is reached by address and its certificate is pinned instead.
        c.setHostnameVerifier((h, s) -> true);
        c.setRequestMethod(method);
        c.setRequestProperty("hue-application-key", key);
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        return c;
    }

    private static SSLSocketFactory pinned(String pin) throws GeneralSecurityException {
        return trusting(chain -> {
            if (pin == null || !pin.equals(fingerprint(chain[0]))) throw new CertificateException("not the paired bridge");
        });
    }

    private interface Check {
        void check(X509Certificate[] chain) throws CertificateException;
    }

    private static SSLSocketFactory trusting(Check check) throws GeneralSecurityException {
        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
                check.check(chain);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx.getSocketFactory();
    }

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    // MARK: - Pairing and finding the bridge

    /** The bridge's public config (no key needed): its id and name. */
    static JSONObject config(String host) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/api/config").openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** One try: the bridge hands out a key only in the 30 s after its button is pressed. Null until then. */
    static String pair(String host) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/api").openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write("{\"devicetype\":\"argon#tv\"}".getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = c.getInputStream()) {
            JSONObject r = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject(0);
            return r.has("success") ? r.getJSONObject("success").getString("username") : null;
        } finally {
            c.disconnect();
        }
    }

    /** The certificate's fingerprint, checked against the bridge id it must be issued to (trust on first use). */
    static String pin(String host, String bridgeId) throws IOException, GeneralSecurityException {
        String[] seen = new String[1];
        SSLSocketFactory f = trusting(chain -> {
            String subject = chain[0].getSubjectX500Principal().getName().toLowerCase();
            if (!subject.contains("cn=" + bridgeId.toLowerCase())) throw new CertificateException("not issued to " + bridgeId);
            seen[0] = fingerprint(chain[0]);
        });
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + "/api/config").openConnection();
        c.setSSLSocketFactory(f);
        c.setHostnameVerifier((h, s) -> true);
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        try (InputStream in = c.getInputStream()) {
            in.readAllBytes();
        } finally {
            c.disconnect();
        }
        if (seen[0] == null) throw new IOException("no certificate");
        return seen[0];
    }

    /** The first bridge answering a multicast DNS query for _hue._tcp, or null within 3 s. */
    static String discover() {
        byte[] q = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4, '_', 'h', 'u', 'e', 4, '_', 't', 'c', 'p', 5, 'l', 'o', 'c', 'a', 'l', 0,
                0, 12, (byte) 0x80, 1};
        try (MulticastSocket s = new MulticastSocket()) {
            s.setSoTimeout(3000);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("224.0.0.251"), 5353));
            long end = System.currentTimeMillis() + 3000;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String body = new String(p.getData(), 0, p.getLength(), StandardCharsets.ISO_8859_1);
                if (body.contains("_hue") && p.getLength() > q.length) return p.getAddress().getHostAddress();
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
