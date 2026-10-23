package com.argon.launcher;

import android.content.ComponentName;
import android.database.ContentObserver;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract.WatchNextPrograms;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// A notification listener only for the access it gives to media sessions: what is playing, in
// which app, from when to when. Enabled with `cmd notification allow_listener`
// (tools/build_install.sh); the stock Google TV home used the same access for its "now playing".
public class MediaListener extends NotificationListenerService {
    // Long plays are written every few minutes, so a crash or power cut loses at most that much.
    private static final long FLUSH_MS = 5 * 60_000;
    private static final Set<String> IGNORED = Set.of("com.android.bluetooth", "com.argon.launcher");
    private static volatile List<WatchLog.Play> playing = Collections.emptyList();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, Session> sessions = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::onSessions;
    private final Runnable flush = this::flush;
    private MediaSessionManager manager;
    // Apps rewrite their Continue watching rows in bursts; keep a snapshot once they settle.
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable snapshot = () -> io.execute(() -> WatchLog.get(this).snapshotWatchNext());
    private final ContentObserver watchNext = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(snapshot);
            main.postDelayed(snapshot, 3000);
        }
    };

    // Plays going on right now, each with `end` = when it was last checked.
    static List<WatchLog.Play> playing() {
        return playing;
    }

    @Override
    public void onListenerConnected() {
        manager = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaListener.class);
        manager.addOnActiveSessionsChangedListener(changed, me, main);
        onSessions(manager.getActiveSessions(me));
        main.postDelayed(flush, FLUSH_MS);
        getContentResolver().registerContentObserver(WatchNextPrograms.CONTENT_URI, true, watchNext);
        snapshot.run();
    }

    @Override
    public void onListenerDisconnected() {
        if (manager != null) manager.removeOnActiveSessionsChangedListener(changed);
        onSessions(Collections.emptyList());
        main.removeCallbacks(flush);
        getContentResolver().unregisterContentObserver(watchNext);
        main.removeCallbacks(snapshot);
    }

    private void onSessions(List<MediaController> controllers) {
        Set<MediaSession.Token> live = new HashSet<>();
        for (MediaController c : controllers) {
            if (IGNORED.contains(c.getPackageName())) continue;
            live.add(c.getSessionToken());
            if (sessions.containsKey(c.getSessionToken())) continue;
            Session s = new Session(c);
            sessions.put(c.getSessionToken(), s);
            c.registerCallback(s, main);
            s.update();
        }
        for (Iterator<Map.Entry<MediaSession.Token, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MediaSession.Token, Session> entry = it.next();
            if (live.contains(entry.getKey())) continue;
            entry.getValue().end(System.currentTimeMillis());
            entry.getValue().controller.unregisterCallback(entry.getValue());
            it.remove();
        }
        publish();
    }

    private void flush() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) s.split(now);
        publish();
        main.postDelayed(flush, FLUSH_MS);
    }

    private void publish() {
        long now = System.currentTimeMillis();
        List<WatchLog.Play> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (s.since >= 0) out.add(new WatchLog.Play(s.pkg, s.title, s.subtitle, s.since, now));
        }
        playing = Collections.unmodifiableList(out);
    }

    private final class Session extends MediaController.Callback {
        final MediaController controller;
        final String pkg;
        String title, subtitle;
        long since = -1;

        Session(MediaController controller) {
            this.controller = controller;
            pkg = controller.getPackageName();
        }

        // A play starts when the app starts playing and ends on pause, stop or a new title. Apps that
        // never say what's playing (Netflix) get untitled plays; WatchStats matches those to their
        // Continue watching rows. YouTube names the video a few seconds in, which starts a new play.
        void update() {
            PlaybackState st = controller.getPlaybackState();
            int state = st == null ? PlaybackState.STATE_NONE : st.getState();
            boolean on = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
            MediaMetadata md = controller.getMetadata();
            MediaDescription d = md == null ? null : md.getDescription();
            String t = d == null || TextUtils.isEmpty(d.getTitle()) ? null : d.getTitle().toString();
            String sub = d == null || TextUtils.isEmpty(d.getSubtitle()) ? null : d.getSubtitle().toString();
            long now = System.currentTimeMillis();
            if (since >= 0 && (!on || !Objects.equals(t, title))) end(now);
            if (on && since < 0) {
                title = t;
                subtitle = sub;
                since = now;
            } else if (since >= 0 && sub != null) {
                subtitle = sub;
            }
            publish();
        }

        void end(long now) {
            if (since >= 0) WatchLog.get(MediaListener.this).play(pkg, title, subtitle, since, now);
            since = -1;
        }

        void split(long now) {
            if (since < 0) return;
            WatchLog.get(MediaListener.this).play(pkg, title, subtitle, since, now);
            since = now;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            end(System.currentTimeMillis());
            publish();
        }
    }
}
