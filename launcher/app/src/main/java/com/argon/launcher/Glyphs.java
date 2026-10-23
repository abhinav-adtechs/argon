package com.argon.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

// Line icons drawn as paths (the ones Material Symbols in assets/symbols doesn't have, and the ✕ ○ □ △ shapes),
// designed on a 24-unit square like the mockups' SVGs. `size` is the drawn size of that square.
final class Glyphs {
    private Glyphs() {}

    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG), FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R = new RectF();

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
    }

    static synchronized void draw(Canvas c, String name, float cx, float cy, float size, int color) {
        float u = size / 24f, x0 = cx - 12 * u, y0 = cy - 12 * u;
        STROKE.setColor(color);
        STROKE.setStrokeWidth(2.2f * u);
        FILL.setColor(color);
        c.save();
        c.translate(x0, y0);
        c.scale(u, u);
        STROKE.setStrokeWidth(2.2f);
        PATH.reset();
        switch (name) {
            case "cross":
                STROKE.setStrokeWidth(2.4f);
                c.drawLine(5.5f, 5.5f, 18.5f, 18.5f, STROKE);
                c.drawLine(18.5f, 5.5f, 5.5f, 18.5f, STROKE);
                break;
            case "circle":
                c.drawCircle(12, 12, 7.5f, STROKE);
                break;
            case "square":
                c.drawRoundRect(5, 5, 19, 19, 1.5f, 1.5f, STROKE);
                break;
            case "triangle":
                PATH.moveTo(12, 4.5f);
                PATH.lineTo(20.5f, 19);
                PATH.lineTo(3.5f, 19);
                PATH.close();
                c.drawPath(PATH, STROKE);
                break;
            case "play":
                PATH.moveTo(7, 4.5f);
                PATH.lineTo(19, 12);
                PATH.lineTo(7, 19.5f);
                PATH.close();
                c.drawPath(PATH, FILL);
                break;
            case "arrow":
                c.drawLine(4, 12, 18, 12, STROKE);
                PATH.moveTo(13, 7);
                PATH.lineTo(18, 12);
                PATH.lineTo(13, 17);
                c.drawPath(PATH, STROKE);
                break;
            case "plus":
                c.drawLine(12, 5, 12, 19, STROKE);
                c.drawLine(5, 12, 19, 12, STROKE);
                break;
            case "minus":
                c.drawLine(5, 12, 19, 12, STROKE);
                break;
            case "dots":
                for (int i = 0; i < 3; i++) c.drawCircle(5 + i * 7, 12, 2, FILL);
                break;
            case "grid":
                for (int i = 0; i < 9; i++) c.drawCircle(5 + (i % 3) * 7, 5 + (i / 3) * 7, 2, FILL);
                break;
            case "sparkle":
                PATH.moveTo(12, 2.5f);
                PATH.lineTo(14.1f, 8.9f);
                PATH.lineTo(20.5f, 11);
                PATH.lineTo(14.1f, 13.1f);
                PATH.lineTo(12, 19.5f);
                PATH.lineTo(9.9f, 13.1f);
                PATH.lineTo(3.5f, 11);
                PATH.lineTo(9.9f, 8.9f);
                PATH.close();
                c.drawPath(PATH, FILL);
                break;
            case "clock":
                STROKE.setStrokeWidth(2);
                c.drawCircle(12, 12, 9, STROKE);
                PATH.moveTo(12, 7);
                PATH.lineTo(12, 12);
                PATH.lineTo(15.2f, 14);
                c.drawPath(PATH, STROKE);
                break;
            case "film":
                STROKE.setStrokeWidth(2);
                c.drawRoundRect(3, 4, 21, 20, 2, 2, STROKE);
                c.drawLine(7, 4, 7, 20, STROKE);
                c.drawLine(17, 4, 17, 20, STROKE);
                c.drawLine(3, 9, 7, 9, STROKE);
                c.drawLine(17, 9, 21, 9, STROKE);
                c.drawLine(3, 15, 7, 15, STROKE);
                c.drawLine(17, 15, 21, 15, STROKE);
                break;
            case "tv":
                STROKE.setStrokeWidth(2);
                c.drawRoundRect(2.5f, 6.5f, 21.5f, 19.5f, 2, 2, STROKE);
                PATH.moveTo(16.5f, 2.5f);
                PATH.lineTo(12, 6.5f);
                PATH.lineTo(7.5f, 2.5f);
                c.drawPath(PATH, STROKE);
                break;
            case "hdmi":
                STROKE.setStrokeWidth(2);
                PATH.moveTo(3.5f, 8);
                PATH.lineTo(20.5f, 8);
                PATH.lineTo(20.5f, 13);
                PATH.lineTo(17.5f, 16.5f);
                PATH.lineTo(6.5f, 16.5f);
                PATH.lineTo(3.5f, 13);
                PATH.close();
                c.drawPath(PATH, STROKE);
                c.drawLine(8, 11.5f, 16, 11.5f, STROKE);
                break;
            case "monitor":
                STROKE.setStrokeWidth(2);
                c.drawRoundRect(3, 4.5f, 21, 16.5f, 2, 2, STROKE);
                c.drawLine(8, 20, 16, 20, STROKE);
                c.drawLine(12, 16.5f, 12, 20, STROKE);
                break;
            case "check":
                STROKE.setStrokeWidth(3);
                PATH.moveTo(5, 12.5f);
                PATH.lineTo(9.5f, 17);
                PATH.lineTo(19, 7.5f);
                c.drawPath(PATH, STROKE);
                break;
            case "bell":
                STROKE.setStrokeWidth(2);
                PATH.moveTo(6, 8);
                R.set(6, 2, 18, 14);
                PATH.arcTo(R, 180, 180);
                PATH.cubicTo(18, 15, 21, 17, 21, 17);
                PATH.lineTo(3, 17);
                PATH.cubicTo(3, 17, 6, 15, 6, 8);
                c.drawPath(PATH, STROKE);
                c.drawLine(10.3f, 21, 13.7f, 21, STROKE);
                break;
            case "move":
                PATH.moveTo(8, 7);
                PATH.lineTo(3, 12);
                PATH.lineTo(8, 17);
                PATH.moveTo(16, 7);
                PATH.lineTo(21, 12);
                PATH.lineTo(16, 17);
                c.drawPath(PATH, STROKE);
                break;
            // The room and its lights and plugs.
            case "house":
                STROKE.setStrokeWidth(1.8f);
                PATH.moveTo(3, 11);
                PATH.lineTo(12, 4);
                PATH.lineTo(21, 11);
                PATH.moveTo(5.5f, 9.5f);
                PATH.lineTo(5.5f, 20);
                PATH.lineTo(18.5f, 20);
                PATH.lineTo(18.5f, 9.5f);
                PATH.moveTo(10, 20);
                PATH.lineTo(10, 15);
                PATH.lineTo(14, 15);
                PATH.lineTo(14, 20);
                c.drawPath(PATH, STROKE);
                break;
            case "lightbar":
                STROKE.setStrokeWidth(1.8f);
                c.drawRoundRect(8.5f, 2.5f, 15.5f, 17, 1.6f, 1.6f, STROKE);
                c.drawLine(12, 17, 12, 20.5f, STROKE);
                c.drawLine(8, 21, 16, 21, STROKE);
                break;
            case "bulb":
                STROKE.setStrokeWidth(1.8f);
                R.set(6, 3, 18, 15);
                c.drawArc(R, 135, 270, false, STROKE);
                PATH.moveTo(7.8f, 13.2f);
                PATH.lineTo(9.5f, 15);
                PATH.lineTo(9.5f, 17.5f);
                PATH.lineTo(14.5f, 17.5f);
                PATH.lineTo(14.5f, 15);
                PATH.lineTo(16.2f, 13.2f);
                c.drawPath(PATH, STROKE);
                c.drawLine(10.5f, 20.5f, 13.5f, 20.5f, STROKE);
                break;
            case "lamp":
                STROKE.setStrokeWidth(1.8f);
                PATH.moveTo(8.5f, 3);
                PATH.lineTo(15.5f, 3);
                PATH.lineTo(19, 11);
                PATH.lineTo(5, 11);
                PATH.close();
                c.drawPath(PATH, STROKE);
                c.drawLine(12, 11, 12, 19.5f, STROKE);
                c.drawLine(8, 21, 16, 21, STROKE);
                break;
            case "leaf":
                STROKE.setStrokeWidth(1.8f);
                c.drawLine(12, 21, 12, 10, STROKE);
                PATH.moveTo(12, 10);
                PATH.cubicTo(12, 6.5f, 14.6f, 4, 18.5f, 3.5f);
                PATH.cubicTo(18, 7.4f, 15.6f, 10, 12, 10);
                PATH.moveTo(12, 14.5f);
                PATH.cubicTo(12, 11.5f, 9.7f, 9.3f, 6.2f, 8.9f);
                PATH.cubicTo(6.6f, 12.3f, 8.8f, 14.5f, 12, 14.5f);
                c.drawPath(PATH, STROKE);
                break;
            case "plug":
                STROKE.setStrokeWidth(1.8f);
                c.drawLine(9, 3, 9, 7, STROKE);
                c.drawLine(15, 3, 15, 7, STROKE);
                PATH.moveTo(6, 7);
                PATH.lineTo(18, 7);
                PATH.lineTo(18, 11);
                R.set(6, 5, 18, 17);
                PATH.arcTo(R, 0, 180);
                PATH.close();
                c.drawPath(PATH, STROKE);
                c.drawLine(12, 17, 12, 21, STROKE);
                break;
            case "power":
                c.drawLine(12, 3, 12, 11, STROKE);
                R.set(4, 4.5f, 20, 20.5f);
                c.drawArc(R, -45, 270, false, STROKE);
                break;
            case "sun":
                STROKE.setStrokeWidth(2);
                c.drawCircle(12, 12, 4, STROKE);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    c.drawLine(12 + 7.5f * ca, 12 + 7.5f * sa, 12 + 9.5f * ca, 12 + 9.5f * sa, STROKE);
                }
                break;
            case "timer":
                STROKE.setStrokeWidth(2);
                c.drawCircle(12, 13.5f, 7.5f, STROKE);
                PATH.moveTo(12, 10);
                PATH.lineTo(12, 13.5f);
                PATH.lineTo(14.4f, 15.1f);
                c.drawPath(PATH, STROKE);
                c.drawLine(9.5f, 2.5f, 14.5f, 2.5f, STROKE);
                break;
            default:
                break;
        }
        c.restore();
    }

    /** The Welcome tile's mark: ○ △ ✕ □ in a 2×2 grid, on an 84-unit square. */
    static synchronized void welcome(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 84f;
        c.save();
        c.translate(cx - 42 * u, cy - 42 * u);
        c.scale(u, u);
        STROKE.setColor(color);
        STROKE.setStrokeWidth(5);
        c.drawCircle(20, 20, 14, STROKE);
        PATH.reset();
        PATH.moveTo(64, 6);
        PATH.lineTo(79, 33);
        PATH.lineTo(49, 33);
        PATH.close();
        c.drawPath(PATH, STROKE);
        c.drawLine(8, 52, 32, 76, STROKE);
        c.drawLine(32, 52, 8, 76, STROKE);
        c.drawRoundRect(50, 50, 78, 78, 3, 3, STROKE);
        c.restore();
    }
}
