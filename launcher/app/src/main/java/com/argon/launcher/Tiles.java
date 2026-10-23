package com.argon.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

// Square app tiles, like the PS5's game tiles, made once at the focused size with rounded corners (drawn without
// clipping). A brand app gets its colors edge to edge with the logo lifted from its banner; any other app shows its
// 16:9 banner across the middle, fading into a soft field of the banner's own colors above and below.
final class Tiles {
    private Tiles() {}

    static Bitmap app(Apps.Item it, int size, float radius) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (it.logo != null && it.style != null) brand(c, it, size);
        else banner(c, it.tile, size);
        return Backdrop.gpu(Backdrop.rounded(b, radius));
    }

    private static void brand(Canvas c, Apps.Item it, int size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, it.style[0], it.style[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        if (it.style[2] == 1) p.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        // The same amount of ink for every logo (plus each one's own tweak), never past 74% × 40% of the tile.
        float lw = it.logo.getWidth(), lh = it.logo.getHeight();
        float scale = (float) Math.sqrt(size * size * 0.055f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(size * 0.74f / lw, size * 0.4f / lh));
        Bitmap logo = downscale(it.logo, Math.round(lw * scale), Math.round(lh * scale));
        c.drawBitmap(logo, Math.round((size - logo.getWidth()) / 2f), Math.round((size - logo.getHeight()) / 2f), p);
        if (logo != it.logo) logo.recycle();
    }

    private static void banner(Canvas c, Bitmap banner, int size) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        // A tiny copy scaled back up: the banner's colors, blurred into a field.
        Bitmap field = Bitmap.createScaledBitmap(downscale(banner, 24, 14), 6, 6, true);
        c.drawBitmap(field, null, new Rect(0, 0, size, size), p);
        field.recycle();
        c.drawColor(0x26000000);
        int bh = Math.round(size * 9 / 16f), top = (size - bh) / 2;
        RectF band = new RectF(0, top, size, top + bh);
        int save = c.saveLayer(band, null);
        c.drawBitmap(banner, null, band, p);
        Paint mask = new Paint();
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mask.setShader(new LinearGradient(0, band.top, 0, band.bottom, new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[] {0, 0.14f, 0.86f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(band, mask);
        c.restoreToCount(save);
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }
}
