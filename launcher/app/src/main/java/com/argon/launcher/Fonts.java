package com.argon.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

// Inter (the home screen's font) at any weight, for the view-based screens (stats, notices).
final class Fonts {
    private static final SparseArray<Typeface> cache = new SparseArray<>();

    static Typeface inter(Context c, int weight) {
        Typeface t = cache.get(weight);
        if (t == null) {
            t = new Typeface.Builder(c.getAssets(), "fonts/InterVariable.ttf")
                    .setFontVariationSettings("'opsz' " + (weight <= 300 ? 32 : 14) + ", 'wght' " + weight)
                    .build();
            cache.put(weight, t);
        }
        return t;
    }
}
