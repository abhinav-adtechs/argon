package com.argon.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.widget.Toast;

import com.argon.launcher.remote.RemoteService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The home screen (HomeView draws all of it). This activity feeds it what the system and the web remote say: the
// shelf settings, the match, today's viewing, Continue Watching, and keeps the nature sounds in step.
public class HomeActivity extends Activity implements HomeView.Host {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private HomeView home;
    private Ambience ambience;
    private Football football;
    private boolean started, resumed, screenOn = true;
    // Strong reference: SharedPreferences only keeps listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (key != null) onSettingChanged(key);
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_TIME_TICK.equals(i.getAction())) Ui.init(HomeActivity.this);
            if (started) loadStats();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            updateAmbience();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            home.reload();
        }
    };

    private final Runnable reloadShows = this::loadShows;

    // Apps rewrite their rows in bursts; settle before re-reading.
    private final ContentObserver tvObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(reloadShows);
            main.postDelayed(reloadShows, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        home = new HomeView(this, this, prefs);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        ambience = new Ambience(this, Config.ambient(this, prefs));
        startFootball();
        screenOn = getSystemService(PowerManager.class).isInteractive();

        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time, RECEIVER_NOT_EXPORTED);
        IntentFilter pkgs = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs, RECEIVER_NOT_EXPORTED);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screen, RECEIVER_NOT_EXPORTED);
        startForegroundService(new Intent(this, RemoteService.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        ambience.start();
        if (football != null) football.start();
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, tvObserver);
        loadShows();
        loadStats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        home.resume();
        updateAmbience();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        home.pause();
        updateAmbience();
    }

    @Override
    protected void onStop() {
        started = false;
        ambience.stop();
        if (football != null) football.stop();
        getContentResolver().unregisterContentObserver(tvObserver);
        main.removeCallbacks(reloadShows);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateAmbience();
    }

    // Home pressed while the home screen is already showing: back to the first shelf app. From the web remote's
    // Show on TV (`--es device <id>`): that light's or plug's page on the Home tab.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String device = intent.getStringExtra("device");
        if (device != null) home.showDevice(device);
        else if (hasWindowFocus() && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0) home.reset();
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        home.destroy();
        if (football != null) football.shutdown();
        ambience.stop();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return home.onKey(e) || super.dispatchKeyEvent(e);
    }

    @Override
    public void launch(Intent intent, RectF from) {
        ambience.silence();
        try {
            if (from == null) {
                startActivity(intent);
            } else {
                startActivity(intent, ActivityOptions.makeScaleUpAnimation(home, Math.round(from.left), Math.round(from.top),
                        Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height()))).toBundle());
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
            updateAmbience();
        }
    }

    @Override
    public void openStats() {
        launch(new Intent(this, StatsActivity.class), null);
    }

    private void updateAmbience() {
        ambience.setVisible(resumed && screenOn && hasWindowFocus());
    }

    private void loadShows() {
        io.execute(() -> {
            List<Shows.Show> cont = new Shows(this).continueWatching();
            main.post(() -> home.setContinue(cont));
        });
    }

    private void loadStats() {
        io.execute(() -> {
            WatchStats s = WatchStats.load(this);
            main.post(() -> home.setStats(s));
        });
    }

    private void startFootball() {
        String team = Config.team(this, prefs);
        home.setFootball(!team.isEmpty(), null);
        if (team.isEmpty()) return;
        football = new Football(team, 128, m -> home.setFootball(true, m));
        if (started) football.start();
    }

    // ---------- Settings from the web remote ----------

    private void onSettingChanged(String key) {
        switch (key) {
            case Config.TEAM:
                if (football != null) football.shutdown();
                football = null;
                startFootball();
                break;
            case Config.AMBIENT:
                ambience.setLevel(Config.ambient(this, prefs));
                break;
            case Config.DOCK:
            case Config.WEATHER:
            case Config.WIDGETS:
            case Config.WALLPAPER:
                home.readSettings();
                break;
            default:
                break;
        }
    }
}
