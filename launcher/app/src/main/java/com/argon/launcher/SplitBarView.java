package com.argon.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// A thin bar split between apps by time watched, each part in the app's colour.
public final class SplitBarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Long> values = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();

    public SplitBarView(Context c, AttributeSet a) {
        super(c, a);
    }

    void set(List<WatchStats.App> apps, boolean today) {
        values.clear();
        colors.clear();
        for (WatchStats.App app : apps) {
            long ms = today ? app.today : app.week;
            if (ms <= 0) continue;
            values.add(ms);
            colors.add(app.color);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), r = h / 2f;
        float gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
        if (values.isEmpty()) {
            paint.setColor(0x14FFFFFF);
            c.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            return;
        }
        long total = 0;
        for (long v : values) total += v;
        // Every part gets at least its own height in width, taken from the space shared out by time.
        float free = getWidth() - gap * (values.size() - 1) - h * values.size();
        float x = 0;
        for (int i = 0; i < values.size(); i++) {
            float w = h + Math.max(0, free) * values.get(i) / total;
            paint.setColor(colors.get(i));
            c.drawRoundRect(x, 0, x + w, h, r, r, paint);
            x += w + gap;
        }
    }
}
