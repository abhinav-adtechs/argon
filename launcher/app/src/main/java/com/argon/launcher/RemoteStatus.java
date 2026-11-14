package com.argon.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.argon.launcher.remote.RemoteService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web remote's server (remote/RemoteService, in the `:remote` process) as the Inputs tab shows it: asked for
 * GET /api/health on the TV itself every 10 s while the home screen shows, and restarted from here (its process
 * killed and the service started again) when it's stuck.
 */
final class RemoteStatus {
    interface Listener {
        void onRemoteStatus();
    }

    static final int CHECKING = 0, RUNNING = 1, TROUBLE = 2, NOT_RESPONDING = 3, STOPPED = 4, RESTARTING = 5;
    private static final long EVERY_MS = 10_000, FAST_MS = 1000, RESTART_MS = 30_000;

    int state = CHECKING;
    boolean answering, adb, keys, mdns;
    int pages;
    long since;
    String address = "";

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::probe;
    private boolean watching;
    private long restartedAt;
    private String look = "";

    RemoteStatus(Context c, Listener listener) {
        context = c;
        this.listener = listener;
    }

    void start() {
        if (watching) return;
        watching = true;
        probe();
    }

    void stop() {
        watching = false;
        handler.removeCallbacks(tick);
    }

    boolean ok() {
        return state == RUNNING;
    }

    /** Stops the service, kills its process (whatever it was stuck on goes with it) and starts it again. */
    void restart() {
        if (state == RESTARTING) return;
        restartedAt = SystemClock.uptimeMillis();
        state = RESTARTING;
        look = "";
        listener.onRemoteStatus();
        handler.removeCallbacks(tick);
        io.execute(() -> {
            Intent service = new Intent(context, RemoteService.class);
            context.stopService(service);
            int pid = remotePid();
            if (pid > 0) Process.killProcess(pid);
            for (int i = 0; i < 30 && remotePid() > 0; i++) SystemClock.sleep(100);
            try {
                context.startForegroundService(service);
            } catch (RuntimeException e) {
                Log.w("Home", "web remote restart: " + e);
            }
            handler.postDelayed(tick, FAST_MS);
        });
    }

    private void probe() {
        handler.removeCallbacks(tick);
        io.execute(() -> {
            JSONObject h = health();
            boolean alive = h != null || remotePid() > 0;
            handler.post(() -> apply(h, alive));
        });
    }

    private void apply(JSONObject h, boolean alive) {
        boolean restarting = restartedAt > 0 && SystemClock.uptimeMillis() - restartedAt < RESTART_MS;
        answering = h != null;
        adb = h != null && h.optBoolean("adb");
        keys = h != null && h.optBoolean("keys");
        mdns = h != null && h.optBoolean("mdns");
        pages = h == null ? 0 : h.optInt("pages");
        since = h == null ? 0 : h.optLong("since");
        address = h == null ? "" : h.optString("address");
        int s;
        if (answering && adb && keys && mdns) s = RUNNING;
        else if (restarting) s = RESTARTING;
        else if (answering) s = TROUBLE;
        else s = alive ? NOT_RESPONDING : STOPPED;
        if (s != RESTARTING) restartedAt = 0;
        state = s;
        String now = s + "|" + adb + keys + mdns + "|" + pages + "|" + since + "|" + address;
        if (!now.equals(look)) {
            look = now;
            listener.onRemoteStatus();
        }
        if (watching) handler.postDelayed(tick, s == RESTARTING ? FAST_MS : EVERY_MS);
    }

    /** The server's own report, or null when nothing answers within a few seconds. A plain socket: the app's
     *  network policy forbids cleartext HTTP, even to the TV itself. */
    private static JSONObject health() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", RemoteService.PORT), 2000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String reply = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int body = reply.indexOf("\r\n\r\n");
            if (!reply.startsWith("HTTP/1.1 200") || body < 0) return null;
            return new JSONObject(reply.substring(body + 4));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private int remotePid() {
        String name = context.getPackageName() + ":remote";
        for (ActivityManager.RunningAppProcessInfo p : context.getSystemService(ActivityManager.class).getRunningAppProcesses())
            if (p.processName.equals(name)) return p.pid;
        return 0;
    }
}
