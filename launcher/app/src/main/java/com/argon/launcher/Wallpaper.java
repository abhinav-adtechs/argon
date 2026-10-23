package com.argon.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

// The Welcome hub's background. "Waves" is drawn here once (a deep blue glow, light streaks and faint line-art
// shapes, like the PS5's own) and kept as a PNG in the files dir; "photo" is the picture sent from the web remote
// (files/wallpaper.jpg). Both come back in GPU memory, 1920×1080.
final class Wallpaper {
    private static final String TAG = "Wallpaper";
    private static final int W = 1920, H = 1080, VERSION = 2;
    static final String PHOTO = "wallpaper.jpg";

    private Wallpaper() {}

    static Bitmap waves(Context c) {
        File f = new File(c.getFilesDir(), "waves-v" + VERSION + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return opaque(b);
        }
        Bitmap b = drawWaves();
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "waves not saved: " + e.getMessage());
        }
        return opaque(b);
    }

    /** On the GPU, marked opaque: drawn without blending, which this TV's GPU feels on a full-screen picture. */
    private static Bitmap opaque(Bitmap b) {
        b.setHasAlpha(false);
        return Backdrop.gpu(b);
    }

    /** The photo from the web remote, cropped to fill the screen; null when there's none. */
    static Bitmap photo(Context c) {
        File f = new File(c.getFilesDir(), PHOTO);
        if (!f.exists()) return null;
        try {
            Bitmap src = Backdrop.decode(f, W);
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
            float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
            Canvas canvas = new Canvas(out);
            canvas.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            src.recycle();
            topScrim(canvas);
            return opaque(out);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "photo: " + e);
            return null;
        }
    }

    private static Bitmap drawWaves() {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(1380, 240, 1500, new int[] {0xFF1D56C4, 0xFF0D2C74, 0xFF061331, 0xFF02040C},
                new float[] {0, 0.26f, 0.58f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        Shader streak = new LinearGradient(0, H, W, 0, new int[] {0x002F8CFF, 0xFF5CB6FF, 0x00D6EFFF},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        Path a = wave(-120, 860, 380, 660, 860, 800, 1260, 430, 1880, 40, 2080, -60);
        Path d = wave(-120, 990, 520, 760, 1040, 880, 1480, 520, 2000, 220, 2100, 140);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(18);
        glow.setShader(streak);
        glow.setAlpha(128);
        glow.setMaskFilter(new BlurMaskFilter(12, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(a, glow);
        c.drawPath(d, glow);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f);
        line.setShader(streak);
        line.setAlpha(230);
        c.drawPath(a, line);
        c.drawPath(wave(-120, 870, 380, 672, 870, 812, 1272, 444, 1890, 56, 2090, -44), line);
        c.drawPath(wave(-120, 882, 380, 686, 880, 826, 1284, 458, 1900, 72, 2100, -28), line);
        c.drawPath(d, line);
        c.drawPath(wave(-120, 1004, 520, 776, 1052, 896, 1494, 536, 2010, 238, 2110, 158), line);

        Paint art = new Paint(Paint.ANTI_ALIAS_FLAG);
        art.setStyle(Paint.Style.STROKE);
        art.setStrokeWidth(3);
        art.setStrokeJoin(Paint.Join.ROUND);
        art.setStrokeCap(Paint.Cap.ROUND);
        art.setColor(0x2B86BCFF);
        c.drawCircle(1590, 236, 104, art);
        Path tri = new Path();
        tri.moveTo(1236, 140);
        tri.lineTo(1330, 302);
        tri.lineTo(1142, 302);
        tri.close();
        c.drawPath(tri, art);
        c.save();
        c.rotate(12, 1768, 368);
        c.drawRoundRect(1700, 300, 1836, 436, 10, 10, art);
        c.restore();
        c.drawLine(1352, 330, 1468, 446, art);
        c.drawLine(1468, 330, 1352, 446, art);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFA9D6FF);
        float[][] dots = {{980, 610, 2, .6f}, {1120, 560, 1.5f, .5f}, {1410, 500, 2.5f, .55f}, {1660, 190, 1.5f, .6f},
                {1820, 120, 2, .5f}, {1530, 420, 1.5f, .45f}, {760, 720, 1.5f, .4f}};
        for (float[] o : dots) {
            dot.setAlpha(Math.round(255 * o[3]));
            c.drawCircle(o[0], o[1], o[2], dot);
        }
        topScrim(c);
        return b;
    }

    /** The top darkened a little for the tabs, the clock and the row (baked in: cheaper than drawing it live). */
    private static void topScrim(Canvas c) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 360, 0xA603040A, 0x0003040A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, 360, p);
    }

    /** A cubic curve, then a smooth one (the SVG "C … S …" the design was drawn with). */
    private static Path wave(float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1,
                             float d2x, float d2y, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x0, y0);
        p.cubicTo(c1x, c1y, c2x, c2y, x1, y1);
        p.cubicTo(2 * x1 - c2x, 2 * y1 - c2y, d2x, d2y, x2, y2);
        return p;
    }
}
