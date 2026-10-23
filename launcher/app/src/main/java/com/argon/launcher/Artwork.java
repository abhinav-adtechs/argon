package com.argon.launcher;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Continue Watching artwork as Backdrops (the same hero pictures as the TMDB picks). Each
// picture is fetched once (https, or content:// from the app that published it), kept as-is in the cache dir for 14
// days, and the last few are kept built in memory.
final class Artwork {
    private static final String TAG = "Artwork";
    private static final int MAX_BYTES = 6 * 1024 * 1024, KEEP = 10;
    private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;

    private final Context context;
    private final File dir;
    private final Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Backdrop> ready = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>(), failed = new HashSet<>();

    Artwork(Context c, Runnable listener) {
        context = c.getApplicationContext();
        dir = new File(context.getCacheDir(), "artwork");
        this.listener = listener;
        io.execute(this::prune);
    }

    /** Ready to draw, or null: it's built in the background and `listener` runs when it's done. */
    Backdrop get(String uri) {
        if (uri == null) return null;
        synchronized (ready) {
            Backdrop b = ready.get(uri);
            if (b != null || failed.contains(uri) || !pending.add(uri)) return b;
        }
        io.execute(() -> {
            Backdrop b = null;
            try {
                File f = file(uri);
                if (f.length() == 0) fetch(uri, f);
                else f.setLastModified(System.currentTimeMillis());
                b = Backdrop.make(Backdrop.decode(f, Backdrop.HERO_W));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "artwork: " + e);
            }
            synchronized (ready) {
                pending.remove(uri);
                if (b == null) {
                    failed.add(uri);
                } else {
                    ready.put(uri, b);
                    while (ready.size() > KEEP) ready.remove(ready.keySet().iterator().next());
                }
            }
            if (b != null) main.post(listener);
        });
        return null;
    }

    void shutdown() {
        io.shutdownNow();
    }

    private File file(String uri) {
        return new File(dir, UUID.nameUUIDFromBytes(uri.getBytes(StandardCharsets.UTF_8)).toString());
    }

    private void fetch(String uri, File f) throws IOException {
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        HttpURLConnection conn = null;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        try {
            InputStream in;
            if ("http".equals(scheme) || "https".equals(scheme)) {
                conn = (HttpURLConnection) new URL(uri).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + conn.getResponseCode());
                in = conn.getInputStream();
            } else {
                in = context.getContentResolver().openInputStream(u);
                if (in == null) throw new IOException("no stream for " + scheme);
            }
            try (InputStream stream = in; FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int total = 0;
                for (int n; (n = stream.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if ((total += n) > MAX_BYTES) throw new IOException("artwork too large");
                }
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the artwork");
        } finally {
            if (conn != null) conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        //noinspection ResultOfMethodCallIgnored
        for (File f : files) if (f.lastModified() < cutoff) f.delete();
    }
}
