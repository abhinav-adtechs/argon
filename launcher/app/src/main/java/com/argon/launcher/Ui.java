package com.argon.launcher;

// From Free My TV (MIT, github.com/simbasjungles/free-my-tv); see launcher/NOTICE.

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fonts and text for the home screen, the screensaver and the stats screen. Font: Inter (variable, assets/fonts,
 * SIL OFL), with the "display" optical size for large text and the "text" size for small text.
 */
final class Ui {
    private Ui() {}

    private static final String FONT = "fonts/InterVariable.ttf";
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    static boolean h24;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();

    static synchronized void init(Context c) {
        h24 = DateFormat.is24HourFormat(c);
        if (loaded) return;
        loaded = true;
        thin = font(c, 32, 200);
        light = font(c, 32, 300);
        displayMedium = font(c, 32, 500);
        displaySemibold = font(c, 32, 600);
        regular = font(c, 14, 400);
        medium = font(c, 14, 500);
        semibold = font(c, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight. */
    private static Typeface font(Context c, int opticalSize, int weight) {
        Typeface t = new Typeface.Builder(c.getAssets(), FONT)
                .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
        return t != null ? t : Typeface.create(Typeface.DEFAULT, weight, false);
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the scores don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Smoothstep: eases in and out of 0…1. */
    static float ease(float t) {
        t = clamp(t);
        return t * t * (3 - 2 * t);
    }

    /** Cubic ease-out: fast start, gentle landing (the PS5's way into place). */
    static float out(float t) {
        t = 1 - clamp(t);
        return 1 - t * t * t;
    }

    /** "9:47" (or "21:47" with the TV on 24-hour time). */
    static String clock(long now) {
        return new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date(now));
    }

    /** "9:47 PM" (or "21:47"). */
    static String time(long at) {
        return new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(new Date(at));
    }

    /** "Sunday, 18 October". */
    static String date(long now) {
        return new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date(now));
    }
}
