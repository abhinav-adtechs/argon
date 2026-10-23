package com.argon.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;

// Quiet nature loop for the home screen. Plays only while home is visible, nothing else is
// playing and we hold audio focus; any doubt resolves to silence.
final class Ambience implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "Ambience";
    private static final int SETTLE_MS = 1500, FADE_IN_MS = 4000, FADE_OUT_MS = 400, FADE_STEP_MS = 30;
    private static final int[] TRACKS = {
            R.raw.ambient_morning, R.raw.ambient_afternoon, R.raw.ambient_evening, R.raw.ambient_night};

    // CONTENT_TYPE_SONIFICATION + no capture is our signature in the (anonymised) list of active
    // players, so we never count our own loop as "something else is playing".
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build();

    private final Context context;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioFocusRequest focusRequest;
    private float level;

    private boolean active, visible, playing, holdingFocus, focusPaused, focusLost;
    private int others;
    private MediaPlayer player, next;
    private int track = -1;
    private float volume, fadeFrom, fadeTarget;
    private long fadeStart;
    private int fadeMs;

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            int before = others;
            others = countOthers(configs);
            // Someone else took focus and has now finished playing: fair to try again.
            if (before > 0 && others == 0) focusLost = false;
            if (before != others) Log.i(TAG, "other players: " + others);
            update();
        }
    };

    private final Runnable begin = this::begin;

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) fadeMs);
            volume = fadeFrom + (fadeTarget - fadeFrom) * t;
            applyVolume();
            if (t < 1f) handler.postDelayed(this, FADE_STEP_MS);
            else if (fadeTarget == 0f) pause();
        }
    };

    Ambience(Context context, int levelPercent) {
        this.context = context;
        audio = context.getSystemService(AudioManager.class);
        level = Math.max(0, Math.min(100, levelPercent)) / 100f;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener(this, handler)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(false)
                .build();
    }

    // Activity onStart.
    void start() {
        if (active) return;
        active = true;
        audio.registerAudioPlaybackCallback(playbackCallback, handler);
        others = countOthers(audio.getActivePlaybackConfigurations());
        update();
    }

    // Activity onStop: free the decoders so apps get the memory.
    void stop() {
        if (!active) return;
        active = false;
        visible = false;
        audio.unregisterAudioPlaybackCallback(playbackCallback);
        release();
    }

    // From the web remote; 0 turns the sounds off.
    void setLevel(int levelPercent) {
        level = Math.max(0, Math.min(100, levelPercent)) / 100f;
        applyVolume();
        update();
    }

    void setVisible(boolean v) {
        if (visible == v) return;
        visible = v;
        if (!v) focusLost = false;
        update();
    }

    // Right before launching something: get out of the way immediately.
    void silence() {
        visible = false;
        handler.removeCallbacks(begin);
        if (playing) fadeTo(0f, 150);
    }

    @Override
    public void onAudioFocusChange(int change) {
        Log.i(TAG, "focus change " + change);
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                focusPaused = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusPaused = true;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                holdingFocus = false;
                focusPaused = false;
                focusLost = true;
                break;
        }
        update();
    }

    private boolean wanted() {
        return active && visible && level > 0 && others == 0 && !focusPaused && !focusLost;
    }

    private void update() {
        handler.removeCallbacks(begin);
        if (!wanted()) {
            if (playing && fadeTarget != 0f) fadeTo(0f, FADE_OUT_MS);
            else if (!playing && holdingFocus && !focusPaused) abandonFocus();
            return;
        }
        if (playing) {
            if (fadeTarget != 1f) fadeTo(1f, FADE_IN_MS);
        } else {
            handler.postDelayed(begin, SETTLE_MS);
        }
    }

    private void begin() {
        if (!wanted() || playing) return;
        if (!holdingFocus) {
            if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "focus denied");
                return;
            }
            holdingFocus = true;
        }
        int t = period();
        if (t != track || player == null) {
            releasePlayers();
            track = t;
            player = create();
            next = create();
            if (player == null || next == null) {
                release();
                return;
            }
            player.setNextMediaPlayer(next);
        }
        volume = 0f;
        applyVolume();
        player.start();
        playing = true;
        Log.i(TAG, "playing track " + track);
        fadeTo(1f, FADE_IN_MS);
    }

    // Morning 5–12, afternoon 12–17, evening 17–22, night: an index into TRACKS.
    private static int period() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? 0
                : hour >= 12 && hour < 17 ? 1
                : hour >= 17 && hour < 22 ? 2
                : 3;
    }

    private void pause() {
        if (playing && player != null) player.pause();
        playing = false;
        Log.i(TAG, "paused");
        // Keep focus through a transient loss so we hear about the matching GAIN.
        if (!focusPaused) abandonFocus();
    }

    private void fadeTo(float target, int ms) {
        handler.removeCallbacks(step);
        fadeFrom = volume;
        fadeTarget = target;
        fadeStart = SystemClock.uptimeMillis();
        fadeMs = ms;
        handler.post(step);
    }

    private void applyVolume() {
        float v = level * volume * volume;
        if (player != null) player.setVolume(v, v);
        if (next != null) next.setVolume(v, v);
    }

    private MediaPlayer create() {
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor fd = context.getResources().openRawResourceFd(TRACKS[track])) {
            mp.setAudioAttributes(ATTRS);
            mp.setDataSource(fd);
            mp.prepare();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't prepare track " + track, e);
            mp.release();
            return null;
        }
        float v = level * volume * volume;
        mp.setVolume(v, v);
        mp.setOnCompletionListener(this::onLoop);
        mp.setOnErrorListener((m, what, extra) -> {
            Log.w(TAG, "player error " + what + "/" + extra);
            release();
            return true;
        });
        return mp;
    }

    // The finished player has already handed over to `next` without a gap; queue a fresh one.
    private void onLoop(MediaPlayer done) {
        if (done != player) return;
        done.release();
        player = next;
        next = player == null ? null : create();
        if (next == null) {
            release();
            return;
        }
        player.setNextMediaPlayer(next);
    }

    private void releasePlayers() {
        if (player != null) player.release();
        if (next != null) next.release();
        player = next = null;
        playing = false;
    }

    private void release() {
        handler.removeCallbacks(begin);
        handler.removeCallbacks(step);
        releasePlayers();
        track = -1;
        volume = 0f;
        fadeTarget = 0f;
        focusPaused = false;
        abandonFocus();
    }

    private void abandonFocus() {
        if (!holdingFocus) return;
        audio.abandonAudioFocusRequest(focusRequest);
        holdingFocus = false;
    }

    private static int countOthers(List<AudioPlaybackConfiguration> configs) {
        int n = 0;
        for (AudioPlaybackConfiguration c : configs) {
            AudioAttributes a = c.getAudioAttributes();
            boolean ours = a.getUsage() == AudioAttributes.USAGE_MEDIA
                    && a.getContentType() == AudioAttributes.CONTENT_TYPE_SONIFICATION
                    && a.getAllowedCapturePolicy() != AudioAttributes.ALLOW_CAPTURE_BY_ALL;
            if (ours) continue;
            switch (a.getUsage()) {
                case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                case AudioAttributes.USAGE_NOTIFICATION:
                case AudioAttributes.USAGE_NOTIFICATION_EVENT:
                case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                    continue;
            }
            n++;
        }
        return n;
    }
}
