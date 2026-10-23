package com.argon.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.TextView;

// A card in the top-right corner, drawn over whatever is on screen, posted from the shell:
//   am broadcast -a com.argon.launcher.NOTICE -p com.argon.launcher --es text "Build done" [--es title Claude] [--ei seconds 8]
// It never takes focus or keys, so the app underneath keeps the remote. A new notice replaces the
// one on screen and restarts its timer.
public class NoticeReceiver extends BroadcastReceiver {
    private static final String TAG = "Notice";
    private static final int DEFAULT_SECONDS = 8, MAX_SECONDS = 600;
    private static final int WIDTH_DP = 320, MARGIN_X_DP = 36, MARGIN_Y_DP = 32, SLIDE_DP = 24;
    private static final int IN_MS = 350, OUT_MS = 250;
    private static final Interpolator DECEL = new DecelerateInterpolator(2f);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable hide = NoticeReceiver::hide;
    private static View card;

    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra("text");
        if (TextUtils.isEmpty(text)) return;
        Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "not allowed to draw over apps: appops set com.argon.launcher SYSTEM_ALERT_WINDOW allow");
            return;
        }
        int seconds = Math.max(1, Math.min(MAX_SECONDS, intent.getIntExtra("seconds", DEFAULT_SECONDS)));
        // am can't pass a real newline, so a literal \n in the text starts a new line.
        show(app, intent.getStringExtra("title"), text.replace("\\n", "\n"), seconds);
    }

    private static void show(Context c, String title, String text, int seconds) {
        if (card == null) {
            card = LayoutInflater.from(c).inflate(R.layout.notice, null);
            ((TextView) card.findViewById(R.id.notice_title)).setTypeface(Fonts.inter(c, 600));
            ((TextView) card.findViewById(R.id.notice_text)).setTypeface(Fonts.inter(c, 500));
            ((ImageView) card.findViewById(R.id.notice_icon)).setImageBitmap(Symbols.get(c, "bell"));
            // No FLAG_NOT_TOUCHABLE: the system caps such overlays at 80% opacity, and the TV has no touch
            // screen anyway. NOT_FOCUSABLE is what keeps the remote's keys going to the app underneath.
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(c, WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(c, MARGIN_X_DP);
            lp.y = dp(c, MARGIN_Y_DP);
            lp.setTitle("Notice");
            card.setAlpha(0f);
            card.setTranslationX(dp(c, SLIDE_DP));
            c.getSystemService(WindowManager.class).addView(card, lp);
        }
        TextView titleView = card.findViewById(R.id.notice_title);
        titleView.setText(title);
        titleView.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        ((TextView) card.findViewById(R.id.notice_text)).setText(text);
        // Cancelling also drops a running hide's end action, so the window stays.
        card.animate().cancel();
        card.animate().alpha(1f).translationX(0f).setDuration(IN_MS).setInterpolator(DECEL).start();
        main.removeCallbacks(hide);
        main.postDelayed(hide, seconds * 1000L);
    }

    private static void hide() {
        View v = card;
        if (v == null) return;
        v.animate().alpha(0f).translationX(dp(v.getContext(), SLIDE_DP)).setDuration(OUT_MS).setInterpolator(DECEL)
                .withEndAction(() -> {
                    v.getContext().getSystemService(WindowManager.class).removeView(v);
                    if (card == v) card = null;
                })
                .start();
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
