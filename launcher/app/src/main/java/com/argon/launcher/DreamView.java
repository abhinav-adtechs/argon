package com.argon.launcher;

// From Free My TV (MIT, github.com/simbasjungles/free-my-tv); see launcher/NOTICE.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, like the Welcome hub's showcase:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan and a crossfade between them;
 *    each photo is loaded 10% larger than the screen, so the zoom never upscales it;
 *  - top left, a large light clock with the date and the weather;
 *  - bottom left, the photo's title and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits.
 * Without photos (no TMDB key, or before the first fetch): the clock over the home screen's wallpaper.
 */
final class DreamView extends View implements Choreographer.FrameCallback, Weather.Listener, Recs.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 120, TITLE_W = 1000, META_BASE = 1000, CLOCK_BASE = 230;
    private static final double DRIFT_PERIOD_S = 600;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image;
        final boolean zoomIn;
        final float panX, panY;
        final RenderNode caption = new RenderNode("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, int n) {
            this.pick = pick;
            this.image = image;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    private final Weather data;
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RenderNode content = new RenderNode("clock");     // without photos: the clock over the wallpaper
    private final RenderNode chrome = new RenderNode("corner");     // the clock, the date and the weather
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the wallpaper with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Bitmap wallpaper;
    private long startedAt, firstAt, lastFrame;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", ampm = "", date = "";
    private Paint clockPaint, ampmPaint, datePaint, weatherPaint, metaPaint;
    private TextPaint titlePaint;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = Weather.get(c);
        recs = Recs.get(c);
        content.setUseCompositingLayer(true, null);
        black.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = SystemClock.uptimeMillis();
        lastFrame = 0;
        failed.clear();
        fallback = recs.showcase().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        if (wallpaper == null) {
            final Context c = getContext().getApplicationContext();
            worker.execute(() -> {
                Bitmap b = Wallpaper.waves(c);
                handler.post(() -> {
                    wallpaper = b;
                    invalidate();
                });
            });
        }
        prepareNext();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final Set<Bitmap> gone = new HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) gone.add(s.image);
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        slides = 0;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onWeather() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        clockPaint = Ui.numbers(Ui.thin, 148 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.03f);
        ampmPaint = Ui.text(Ui.light, 40 * k, 0xFFFFFFFF);
        datePaint = Ui.text(Ui.regular, 32 * k, 0xE0FFFFFF);
        weatherPaint = Ui.numbers(Ui.regular, 28 * k, 0xD1FFFFFF);
        titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(-0.005f);
        metaPaint = Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF);
        for (Paint p : new Paint[] {clockPaint, ampmPaint, datePaint, weatherPaint, titlePaint, metaPaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x59000000);
        }
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        List<Recs.Pick> list = recs.showcase();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image);
            return;
        }
        File file = recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap image = null;
            float[] light = new float[2];
            try {
                Bitmap[] images = Backdrop.photo(file, PW, PH, light);
                images[1].recycle();
                image = Backdrop.gpu(withScrims(images[0], light[0], light[1]));
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w("Screensaver", "photo for “" + chosen.title + "”: " + e.getMessage());
            }
            final Bitmap ready = image;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) ready.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image) {
        Slide s = new Slide(pick, image, slides++);
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            if (old.image != current.image) {
                Bitmap gone = old.image;     // once the last frame that uses it has been drawn
                handler.postDelayed(gone::recycle, 1000);
            }
            prepareNext();
        }
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame); at 20 there's room to spare on the TV's GPU.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = SystemClock.uptimeMillis();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            ampm = Ui.h24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(new Date(now));
            date = Ui.date(now);
            dirty = true;
            chromeDirty = true;
        }
        if (running) advance(up);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        canvas.drawRenderNode(chrome);
        drawCaptions(canvas, up);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer).
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption, float lightClock) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = scrimStrength(lightCaption, 0.5f), corner = scrimStrength(lightClock, 0.46f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.6f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        RadialGradient clockSide = new RadialGradient(0, 0, w * 0.6f,
                new int[] {black(corner * 1.3f), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.62f, 0, 0);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(0, 0, w * 0.6f, w * 0.6f * 0.62f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Top left: the clock, the date and the weather

    private void recordChrome() {
        RecordingCanvas c = chrome.beginRecording(w, h);
        try {
            drawClock(c, MARGIN * k, CLOCK_BASE * k);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    /** "12:08 AM" large and light, then the date, then the weather; `x`, `base`: the clock's start and baseline. */
    private void drawClock(Canvas c, float x, float base) {
        c.drawText(clock, x - Ui.leftBearing(clockPaint, clock), base, clockPaint);
        if (!ampm.isEmpty()) c.drawText(ampm, x + clockPaint.measureText(clock) + 14 * k, base, ampmPaint);
        float y = base + 58 * k;
        c.drawText(date, x, y, datePaint);
        Weather.Data wx = data.data;
        if (!Double.isNaN(wx.temp)) {
            y += 48 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float size = 28 * k, sw = Symbols.width(getContext(), symbol, size);
            Symbols.draw(getContext(), c, symbol, x + sw / 2, y - Ui.halfCap(weatherPaint), size, 0xD1FFFFFF);
            c.drawText(Weather.degrees(wx.temp) + " " + Weather.condition(wx.code, wx.isDay), x + sw + 12 * k, y, weatherPaint);
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        RecordingCanvas c = s.caption.beginRecording(w, h);
        try {
            float x = MARGIN * k, metaBase = META_BASE * k, titleBase = metaBase - 46 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            if (s.pick.meta != null && !s.pick.meta.isEmpty()) c.drawText(s.pick.meta, x, metaBase, metaPaint);
        } finally {
            s.caption.endRecording();
        }
        s.caption.setPosition(0, 0, w, h);
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed. */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a);
            current.caption.setTranslationY(14 * k * (1 - a));
            c.drawRenderNode(current.caption);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out);
            current.caption.setTranslationY(0);
            c.drawRenderNode(current.caption);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in);
            incoming.caption.setTranslationY(14 * k * (1 - in));
            c.drawRenderNode(incoming.caption);
        }
    }

    // MARK: - Without photos: the clock over the wallpaper, drifting slowly (no burn-in)

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (wallpaper != null) {
            photoPaint.setAlpha(255);
            canvas.drawBitmap(wallpaper, null, new RectF(0, 0, w, h), photoPaint);
        } else {
            canvas.drawRect(0, 0, w, h, black);
        }
        if (dirty) {
            RecordingCanvas rc = content.beginRecording(w, h);
            try {
                drawClock(rc, MARGIN * k, h * 0.5f);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        content.setTranslationX((float) (40 * k * Math.sin(phase)));
        content.setTranslationY((float) (26 * k * Math.sin(2 * phase + 0.7)));
        content.setAlpha(alpha);
        canvas.drawRenderNode(content);
    }
}
