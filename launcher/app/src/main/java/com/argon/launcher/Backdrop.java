package com.argon.launcher;

// From Free My TV (MIT, github.com/simbasjungles/free-my-tv); see launcher/NOTICE.

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.File;
import java.io.IOException;

/**
 * A pick's or a show's picture, built once in the background:
 *  - the hero (1280×720, in GPU memory): the full-screen background of an app hub;
 *  - the card (640×360, in GPU memory): what cards and widgets crop from (drawn through a shader, so no clipping).
 *    Sampling the hero down to a 360-wide card read four times the pixels, which this TV's GPU felt on every hub;
 *  - a tiny frosted, darkened version (192×108): the stats screen's background.
 */
final class Backdrop {
    static final int W = 1920, H = 1080, HERO_W = 1280, HERO_H = 720, FW = 192, FH = 108;
    final Bitmap hero, card, nightImage;
    /** The one behind the home screen right now (the stats screen uses it too). */
    static volatile Backdrop shown;

    private Backdrop(Bitmap hero, Bitmap card, Bitmap nightImage) {
        this.hero = hero;
        this.card = card;
        this.nightImage = nightImage;
    }

    /** Takes ownership of `src` (recycled). Any size works: smaller pictures are scaled up to fill. */
    static Backdrop make(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(HERO_W, HERO_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Bitmap b = src;
        // Halve while it stays larger (a true average), then one filtered draw to the exact size.
        while (b.getWidth() / 2 >= HERO_W && b.getHeight() / 2 >= HERO_H) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        float scale = Math.max((float) HERO_W / b.getWidth(), (float) HERO_H / b.getHeight());
        float sw = b.getWidth() * scale, sh = b.getHeight() * scale;
        c.drawBitmap(b, null, new RectF((HERO_W - sw) / 2, (HERO_H - sh) / 2, (HERO_W + sw) / 2, (HERO_H + sh) / 2), photo);
        b.recycle();
        int[] frost = darken(reduce(out, FW, FH, 3, 3), 0.76f);
        Bitmap night = Bitmap.createBitmap(frost, FW, FH, Bitmap.Config.ARGB_8888);
        // Exactly half: each card pixel is the average of four.
        Bitmap card = Bitmap.createScaledBitmap(out, HERO_W / 2, HERO_H / 2, true);
        out.setHasAlpha(false);
        card.setHasAlpha(false);
        return new Backdrop(gpu(out), gpu(card), night);
    }

    /** Decodes a file at least `minWidth` wide where possible (halving while the picture stays larger). */
    static Bitmap decode(File f, int minWidth) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) throw new IOException("unreadable image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= minWidth) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException("unreadable image");
        return b;
    }

    /** How many times smaller the screensaver photo's frosted version is. */
    static final int PHOTO_FROST = 6;

    /**
     * Screensaver: the whole original from disk at w × h (center-cropped to that aspect ratio), filtered down, plus the
     * frosted version (1/6, blurred). `light` receives the luminance (0 to 1) where the caption (bottom left) and the
     * clock (top left) sit, for the scrims. Returns {photo (mutable), frosted}.
     */
    static Bitmap[] photo(File f, int w, int h, float[] light) throws IOException {
        Bitmap src = decode(f, w);
        float ratio = (float) w / h;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        if (b != src) src.recycle();
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            b.recycle();
            b = half;
        }
        Bitmap out = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (out != b) b.recycle();
        if (light != null && light.length >= 2) {
            light[0] = luminance(out, 0, 0.6f, 0.5f, 0.95f);
            light[1] = luminance(out, 0.04f, 0.04f, 0.4f, 0.36f);
        }
        int fw = w / PHOTO_FROST, fh = h / PHOTO_FROST;
        Bitmap frost = Bitmap.createBitmap(reduce(out, fw, fh, 3, 2), fw, fh, Bitmap.Config.ARGB_8888);
        if (!out.isMutable()) {
            Bitmap copy = out.copy(Bitmap.Config.ARGB_8888, true);
            out.recycle();
            out = copy;
        }
        return new Bitmap[] {out, frost};
    }

    /** The picture with transparent rounded corners (takes ownership of `b`). */
    static Bitmap rounded(Bitmap b, float radius) {
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, b.getWidth(), b.getHeight(), radius, radius, p);
        b.recycle();
        return out;
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        b.setHasMipMap(true);
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black, keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }
}
