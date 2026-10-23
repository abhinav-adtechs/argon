package com.argon.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Full screen viewing stats, opened from the Your viewing row or the top bar's chart button.
// Left/Right switch between today and this week; Back returns home.
public class StatsActivity extends Activity {
    private static final long REFRESH_MS = 60_000;
    private static final String[] HOUR_LABELS = {"12 AM", "3 AM", "6 AM", "9 AM", "12 PM", "3 PM", "6 PM", "9 PM"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::load;
    private WatchStats stats;
    private boolean week;

    private ImageView wallpaper;
    private TextView date, rangeToday, rangeWeek, chartTitle, chartLegend, appsNote;
    private LinearLayout headline, chartLabels, appRows;
    private LinearLayout[] showCols;
    private BarsView chart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        wallpaper = findViewById(R.id.stats_wallpaper);
        date = findViewById(R.id.stats_date);
        rangeToday = findViewById(R.id.range_today);
        rangeWeek = findViewById(R.id.range_week);
        headline = findViewById(R.id.stats_headline);
        chartTitle = findViewById(R.id.chart_title);
        chartLegend = findViewById(R.id.chart_legend);
        chart = findViewById(R.id.chart_bars);
        chartLabels = findViewById(R.id.chart_labels);
        appRows = findViewById(R.id.apps_rows);
        appsNote = findViewById(R.id.apps_note);
        showCols = new LinearLayout[]{findViewById(R.id.shows_col_0), findViewById(R.id.shows_col_1)};
        ((TextView) findViewById(R.id.stats_heading)).setTypeface(Fonts.inter(this, 300));
        for (int id : new int[]{R.id.stats_date, R.id.range_today, R.id.range_week, R.id.chart_title, R.id.chart_legend,
                R.id.shows_title, R.id.apps_title, R.id.apps_note}) {
            ((TextView) findViewById(id)).setTypeface(Fonts.inter(this, 400));
        }
        LinearLayout head = findViewById(R.id.apps_head);
        for (int i = 0; i < head.getChildCount(); i++) ((TextView) head.getChildAt(i)).setTypeface(Fonts.inter(this, 400));
        loadBackdrop();
        styleRange();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(refresh);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getAction() == KeyEvent.ACTION_DOWN) setWeek(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void setWeek(boolean w) {
        if (week == w) return;
        week = w;
        styleRange();
        bind();
    }

    private void load() {
        main.removeCallbacks(refresh);
        io.execute(() -> {
            WatchStats s = WatchStats.load(this);
            main.post(() -> {
                stats = s;
                bind();
            });
        });
        main.postDelayed(refresh, REFRESH_MS);
    }

    // The app picture behind the home screen right now (blurred and darkened), or the Welcome hub's wallpaper.
    private void loadBackdrop() {
        Backdrop b = Backdrop.shown;
        if (b != null) {
            wallpaper.setImageBitmap(b.nightImage);
            return;
        }
        io.execute(() -> {
            android.graphics.Bitmap waves = Wallpaper.waves(this);
            main.post(() -> wallpaper.setImageBitmap(waves));
        });
    }

    private void styleRange() {
        for (TextView v : new TextView[]{rangeToday, rangeWeek}) {
            boolean on = (v == rangeWeek) == week;
            v.setBackgroundResource(on ? R.drawable.range_on_bg : R.drawable.pill_bg);
            v.setTextColor(on ? 0xFF121216 : 0xBFFFFFFF);
        }
    }

    private void bind() {
        WatchStats s = stats;
        if (s == null) return;
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()));
        bindHeadline(s);
        bindChart(s);
        bindShows(s);
        bindApps(s);
    }

    private void bindHeadline(WatchStats s) {
        List<String[]> items = new ArrayList<>();
        if (week) {
            int day = s.busiestDay();
            items.add(new String[]{WatchStats.duration(s.watchedWeek), "Watched this week"});
            items.add(new String[]{WatchStats.duration(s.screenWeek), "Screen on this week"});
            items.add(day < 0 ? new String[]{"—", "Busiest day"} : new String[]{
                    DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    "Busiest day · " + WatchStats.duration(s.watchedIn(s.dayApps, day))});
            items.add(new String[]{WatchStats.duration(s.watchedWeek / s.daysOn()), "Daily average"});
        } else {
            int hour = s.busiestHour();
            items.add(new String[]{WatchStats.duration(s.watchedToday), "Watched today"});
            items.add(new String[]{WatchStats.duration(s.screenToday), "Screen on today"});
            items.add(hour < 0 ? new String[]{"—", "Busiest hour"} : new String[]{WatchStats.hourName(hour),
                    "Busiest hour · " + WatchStats.duration(s.watchedIn(s.hourApps, hour))});
            items.add(new String[]{WatchStats.duration(s.watchedWeek), "This week, " + s.weekRange()});
        }
        headline.removeAllViews();
        for (String[] item : items) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(item[0], 32, 300, 0xF2FFFFFF));
            TextView label = text(item[1], 12, 400, 0x8CFFFFFF);
            label.setPadding(0, dp(3), dp(12), 0);
            col.addView(label);
            headline.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void bindChart(WatchStats s) {
        chartTitle.setText(week ? "This week by day" : "Today by hour");
        SpannableStringBuilder legend = new SpannableStringBuilder();
        for (WatchStats.App a : week ? s.apps.subList(0, Math.min(3, s.apps.size())) : s.todayApps(3)) {
            legend.append(WatchStats.dotted(a.name, a.color)).append("     ");
        }
        legend.append(WatchStats.dotted("Screen on", 0x40FFFFFF));
        chartLegend.setText(legend);
        chartLabels.removeAllViews();
        if (week) {
            long max = WatchStats.HOUR;
            for (long on : s.dayOn) max = Math.max(max, on);
            chart.setStyle(14f, 4f);
            chart.set(s.dayBars(), max);
            for (DayOfWeek d : DayOfWeek.values()) {
                TextView t = text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), 10, 400, 0x73FFFFFF);
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                chartLabels.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        } else {
            chart.setStyle(5f, 3f);
            chart.set(s.hourBars(), WatchStats.HOUR);
            for (String label : HOUR_LABELS) {
                chartLabels.addView(text(label, 10, 400, 0x73FFFFFF),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
            }
        }
    }

    private void bindShows(WatchStats s) {
        for (LinearLayout col : showCols) col.removeAllViews();
        if (s.shows.isEmpty()) {
            showCols[0].addView(text("Titles show up here as you watch", 12, 400, 0x80FFFFFF));
            return;
        }
        for (int i = 0; i < Math.min(6, s.shows.size()); i++) {
            WatchStats.Show show = s.shows.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View bar = new View(this);
            bar.setBackground(rounded(show.color, dp(1.5f)));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(22));
            barLp.setMarginEnd(dp(9));
            row.addView(bar, barLp);
            LinearLayout lines = new LinearLayout(this);
            lines.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(show.title, 13, 400, 0xF2FFFFFF);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            lines.addView(title);
            TextView meta = text(meta(show), 10.5f, 400, 0x8CFFFFFF);
            meta.setPadding(0, dp(2), 0, 0);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            lines.addView(meta);
            row.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(6);
            showCols[i % 2].addView(row, rowLp);
        }
    }

    // "YouTube · 12m · today, 8:26 PM" or "Netflix · 90% watched".
    private String meta(WatchStats.Show s) {
        List<String> parts = new ArrayList<>();
        parts.add(s.app);
        if (s.ms >= WatchStats.MIN) parts.add(WatchStats.duration(s.ms));
        if (s.progress >= 0) parts.add(Math.round(s.progress * 100) + "% watched");
        if (s.last > 0 && s.ms > 0) {
            Date when = new Date(s.last);
            String time = new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", Locale.getDefault()).format(when);
            parts.add(s.last >= stats.todayStart ? "today, " + time
                    : new SimpleDateFormat("EEE", Locale.getDefault()).format(when) + ", " + time);
        }
        return TextUtils.join(" · ", parts);
    }

    private void bindApps(WatchStats s) {
        appRows.removeAllViews();
        long top = 1;
        for (WatchStats.App a : s.apps) top = Math.max(top, a.week);
        int trackW = dp(292 - 36);
        for (int i = 0; i < Math.min(6, s.apps.size()); i++) {
            WatchStats.App a = s.apps.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(WatchStats.dotted(a.name, a.color), 13, 400, 0xF2FFFFFF);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(cell(a.today >= WatchStats.MIN ? WatchStats.duration(a.today) : "—", 52, 0xBFFFFFFF));
            row.addView(cell(WatchStats.duration(a.week), 60, 0xF2FFFFFF));
            row.addView(cell(String.valueOf(a.opens), 43, 0x99FFFFFF));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(11);
            appRows.addView(row, rowLp);

            FrameLayout track = new FrameLayout(this);
            track.setBackground(rounded(0x14FFFFFF, dp(1.5f)));
            View fill = new View(this);
            fill.setBackground(rounded(a.color, dp(1.5f)));
            track.addView(fill, new FrameLayout.LayoutParams(Math.max(dp(3), Math.round(trackW * a.week / (float) top)),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            trackLp.topMargin = dp(6);
            appRows.addView(track, trackLp);
        }
        appsNote.setText(s.apps.isEmpty() ? "Nothing watched yet this week" : "Bars show this week, " + s.weekRange());
    }

    private TextView cell(String value, int widthDp, int color) {
        TextView t = text(value, 13, 400, color);
        t.setGravity(Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView text(CharSequence s, float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Fonts.inter(this, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
