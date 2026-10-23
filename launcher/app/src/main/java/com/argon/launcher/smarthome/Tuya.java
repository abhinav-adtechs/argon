package com.argon.launcher.smarthome;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

// A Tuya Wi-Fi plug on the LAN (Wipro and most other Indian brands), over Tuya's local protocol 3.3: frames of
// AES-128-ECB JSON on TCP 6668, keyed with the plug's local key from Tuya's cloud. One connection stays open with a
// heartbeat: the plug pushes every change on it (from its button, Google Home or its app) and accepts only a few
// clients, so the TV keeps one and doesn't reconnect per command.
final class Tuya {
    private static final String TAG = "SmartHome";
    private static final int PREFIX = 0x000055AA, SUFFIX = 0x0000AA55;
    private static final int CONTROL = 7, STATUS = 8, HEART_BEAT = 9, DP_QUERY = 10, CONTROL_NEW = 13;
    private static final byte[] V33 = "3.3\0\0\0\0\0\0\0\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);

    interface Listener {
        void changed(Tuya plug);
    }

    final String id;
    volatile String ip;
    private final SecretKeySpec key;
    private final Listener listener;

    // The plug's data points: 1 on, 9 countdown (s), 18 current (mA), 19 power (0.1 W), 20 voltage (0.1 V).
    volatile boolean online, on;
    volatile int countdown, milliamps, deciwatts, decivolts;
    volatile long changedAt;         // when it was last switched, as seen here (0: before the TV knew)
    private boolean known;

    private volatile boolean stopped;
    private Thread thread;
    private Socket socket;
    private OutputStream out;
    private int seq = 1;

    Tuya(String id, String localKey, String ip, Listener listener) {
        this.id = id;
        this.ip = ip;
        this.listener = listener;
        key = new SecretKeySpec(localKey.getBytes(StandardCharsets.UTF_8), "AES");
    }

    void start() {
        thread = new Thread(this::loop, "tuya-" + id.substring(Math.max(0, id.length() - 4)));
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        closeSocket();
        if (thread != null) thread.interrupt();
    }

    double watts() {
        return on ? deciwatts / 10.0 : 0;
    }

    /** Sets data points ({"1": true} switches it); false when the plug isn't connected right now. */
    boolean set(JSONObject dps) {
        try {
            JSONObject p = new JSONObject().put("devId", id).put("uid", id).put("t", String.valueOf(System.currentTimeMillis() / 1000)).put("dps", dps);
            send(CONTROL, p);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "tuya " + id + " set: " + e.getMessage());
            closeSocket();
            return false;
        }
        // Shown at once; the plug confirms with a status push.
        apply(dps);
        listener.changed(this);
        return true;
    }

    // Connects, asks for the state, then reads pushes, with a heartbeat every 10 s; again after 1 s doubling to 30 s.
    private void loop() {
        long wait = 1000;
        while (!stopped) {
            String addr = ip;
            if (addr != null) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(addr, 6668), 4000);
                    s.setSoTimeout(10_000);
                    s.setTcpNoDelay(true);
                    synchronized (this) {
                        socket = s;
                        out = s.getOutputStream();
                    }
                    query();
                    read(new DataInputStream(s.getInputStream()));
                    wait = 1000;
                } catch (IOException | JSONException e) {
                    if (!stopped && online) Log.w(TAG, "tuya " + id + " " + addr + ": " + e.getMessage());
                }
                closeSocket();
            }
            if (online) {
                online = false;
                listener.changed(this);
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 30_000);
        }
    }

    private void query() throws IOException, JSONException {
        String t = String.valueOf(System.currentTimeMillis() / 1000);
        send(DP_QUERY, new JSONObject().put("gwId", id).put("devId", id).put("uid", id).put("t", t));
    }

    private void read(DataInputStream in) throws IOException, JSONException {
        int missed = 0;
        while (!stopped) {
            Frame f;
            try {
                f = frame(in);
                missed = 0;
            } catch (SocketTimeoutException e) {
                if (++missed > 2) throw e;
                send(HEART_BEAT, new JSONObject());
                continue;
            }
            if (!online) {
                online = true;
                listener.changed(this);
            }
            if (f.text.contains("data unvalid")) {
                // Plugs with 22-character ids answer the query only as a control with empty data points.
                JSONObject dps = new JSONObject();
                for (int dp : new int[] {1, 9, 18, 19, 20}) dps.put(String.valueOf(dp), JSONObject.NULL);
                send(CONTROL_NEW, new JSONObject().put("devId", id).put("uid", id).put("t", String.valueOf(System.currentTimeMillis() / 1000)).put("dps", dps));
                continue;
            }
            if (!f.text.startsWith("{")) continue;
            JSONObject o = new JSONObject(f.text);
            JSONObject dps = o.optJSONObject("dps");
            if (dps == null && o.optJSONObject("data") != null) dps = o.optJSONObject("data").optJSONObject("dps");
            if (dps != null) {
                apply(dps);
                listener.changed(this);
            }
        }
    }

    private void apply(JSONObject dps) {
        for (Iterator<String> it = dps.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = dps.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            switch (k) {
                case "1":
                    if (v instanceof Boolean && ((Boolean) v != on || !known)) {
                        if (known) changedAt = System.currentTimeMillis();
                        on = (Boolean) v;
                        known = true;
                    }
                    break;
                case "9": countdown = dps.optInt(k); break;
                case "18": milliamps = dps.optInt(k); break;
                case "19": deciwatts = dps.optInt(k); break;
                case "20": decivolts = dps.optInt(k); break;
                default: break;
            }
        }
    }

    // MARK: - Frames

    private static final class Frame {
        int cmd;
        String text = "";
    }

    private void send(int cmd, JSONObject payload) throws IOException {
        byte[] body = crypt(payload.toString().getBytes(StandardCharsets.UTF_8), true);
        if (cmd != DP_QUERY && cmd != HEART_BEAT) {
            byte[] b = new byte[V33.length + body.length];
            System.arraycopy(V33, 0, b, 0, V33.length);
            System.arraycopy(body, 0, b, V33.length, body.length);
            body = b;
        }
        synchronized (this) {
            if (out == null) throw new IOException("not connected");
            ByteBuffer buf = ByteBuffer.allocate(16 + body.length + 8);
            buf.putInt(PREFIX).putInt(seq++).putInt(cmd).putInt(body.length + 8).put(body);
            CRC32 crc = new CRC32();
            crc.update(buf.array(), 0, 16 + body.length);
            buf.putInt((int) crc.getValue()).putInt(SUFFIX);
            out.write(buf.array());
            out.flush();
        }
    }

    private Frame frame(DataInputStream in) throws IOException {
        int prefix = in.readInt();
        if (prefix != PREFIX) throw new IOException("out of step");
        in.readInt();
        Frame f = new Frame();
        f.cmd = in.readInt();
        int len = in.readInt();
        if (len < 8 || len > 8192) throw new IOException("bad length " + len);
        byte[] rest = new byte[len];
        in.readFully(rest);
        byte[] payload = Arrays.copyOf(rest, len - 8);
        int at = 0;
        // Answers start with a 4-byte return code; pushes with the version header instead.
        if (payload.length >= 4 && !(payload[0] == '3' && payload[1] == '.')) at = 4;
        if (payload.length - at >= 15 && payload[at] == '3' && payload[at + 1] == '.') at += 15;
        int n = payload.length - at;
        if (n > 0 && n % 16 == 0) {
            byte[] plain = crypt(Arrays.copyOfRange(payload, at, payload.length), false);
            if (plain != null) f.text = new String(plain, StandardCharsets.UTF_8);
        } else if (n > 0) {
            f.text = new String(payload, at, n, StandardCharsets.UTF_8);
        }
        return f;
    }

    private byte[] crypt(byte[] data, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key);
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            return encrypt ? new byte[0] : null;
        }
    }

    private void closeSocket() {
        Socket s;
        synchronized (this) {
            s = socket;
            socket = null;
            out = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    // MARK: - Finding plugs

    /**
     * Listens for the announcements Tuya devices broadcast every few seconds on UDP 6667 (encrypted with a key every
     * device shares) and reports each device id with its address, so a plug the router moved is found again.
     */
    static Thread listen(BiConsumer<String, String> found) {
        Thread t = new Thread(() -> {
            SecretKeySpec k;
            try {
                k = new SecretKeySpec(MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".getBytes(StandardCharsets.UTF_8)), "AES");
            } catch (GeneralSecurityException e) {
                return;
            }
            while (true) {
                try (DatagramSocket s = new DatagramSocket(null)) {
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(6667));
                    byte[] buf = new byte[1024];
                    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
                    while (true) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        if (p.getLength() < 28) continue;
                        int len = ByteBuffer.wrap(buf, 12, 4).getInt();
                        if (len < 28 || 16 + len > p.getLength()) continue;
                        try {
                            c.init(Cipher.DECRYPT_MODE, k);
                            byte[] plain = c.doFinal(buf, 20, len - 12);
                            JSONObject o = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                            found.accept(o.optString("gwId"), p.getAddress().getHostAddress());
                        } catch (GeneralSecurityException | JSONException ignored) {
                            // Another device's packet (or a protocol 3.1 one in the clear).
                        }
                    }
                } catch (IOException | GeneralSecurityException e) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "tuya-listen");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
