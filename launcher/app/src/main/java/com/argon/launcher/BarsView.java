package com.argon.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Collections;
import java.util.List;

// One column per hour or day: a faint bar for the time the screen was on, with the time spent in
// each app stacked in its colour from the bottom.
public final class BarsView extends View {
    static final class Bar {
        final long on;
        final List<Long> values;
        final List<Integer> colors;

        Bar(long on, List<Long> values, List<Integer> colors) {
            this.on = on;
            this.values = values;
            this.colors = colors;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Bar> bars = Collections.emptyList();
    private long max = 1;
    private float gap, radius, minSegment;

    public BarsView(Context c, AttributeSet a) {
        super(c, a);
        gap = dp(3);
        radius = dp(2);
        minSegment = dp(1.5f);
    }

    void setStyle(float gapDp, float radiusDp) {
        gap = dp(gapDp);
        radius = dp(radiusDp);
        invalidate();
    }

    // `max` is the value that fills the full height (an hour for hours).
    void set(List<Bar> bars, long max) {
        this.bars = bars;
        this.max = Math.max(1, max);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = bars.size();
        if (n == 0) return;
        float h = getHeight(), w = (getWidth() - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Bar bar = bars.get(i);
            float x = i * (w + gap);
            if (bar.on > 0) {
                float on = Math.max(minSegment, Math.min(1f, bar.on / (float) max) * h);
                paint.setColor(0x1AFFFFFF);
                c.drawRoundRect(x, h - on, x + w, h, radius, radius, paint);
            }
            float y = h;
            for (int s = 0; s < bar.values.size() && y > 0; s++) {
                float sh = Math.max(minSegment, bar.values.get(s) / (float) max * h);
                sh = Math.min(sh, y);
                paint.setColor(bar.colors.get(s));
                c.drawRoundRect(x, y - sh, x + w, y, radius, radius, paint);
                y -= sh + dp(1);
            }
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
