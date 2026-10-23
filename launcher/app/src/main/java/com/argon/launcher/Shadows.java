package com.argon.launcher;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

// Soft drop shadows as cached textures. A blur mask filter is redone on the CPU every time it's drawn at a new size,
// which made focus moves miss frames on the TV; here each resting size is blurred once (an ALPHA_8 bitmap, which
// draws in the paint's color) and then only scaled.
final class Shadows {
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();

    /** The shadow of a w × h rounded rectangle, centered on (cx, cy) and drawn `scale` times larger. */
    void draw(Canvas c, float cx, float cy, float w, float h, float radius, float blur, int color, float scale) {
        if ((color >>> 24) == 0) return;
        int b = Math.max(1, Math.round(blur)), m = 2 * b;
        Bitmap mask = mask(Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), Math.round(radius), b);
        float hw = (w / 2 + m) * scale, hh = (h / 2 + m) * scale;
        dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
        paint.setColor(color);
        c.drawBitmap(mask, null, dst, paint);
    }

    private Bitmap mask(int w, int h, int radius, int blur) {
        String key = w + "x" + h + "r" + radius + "b" + blur;
        Bitmap mask = cache.get(key);
        if (mask != null) return mask;
        int m = 2 * blur;
        mask = Bitmap.createBitmap(w + 2 * m, h + 2 * m, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        new Canvas(mask).drawRoundRect(m, m, m + w, m + h, radius, radius, p);
        cache.put(key, mask);
        return mask;
    }
}
