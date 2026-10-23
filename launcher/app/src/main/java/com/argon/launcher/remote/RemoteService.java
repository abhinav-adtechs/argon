package com.argon.launcher.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import com.argon.launcher.smarthome.SmartHome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

// Hosts the web remote on the TV, in its own process so it outlives the launcher's UI and stays
// small. HomeActivity starts it; it runs in the foreground so the system keeps it while apps play.
public class RemoteService extends Service {
    private static final String TAG = "Remote";
    private static final int PORT = 7777;
    private static boolean started;
    private WifiManager.MulticastLock multicast;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Web remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Web remote")
                .setContentText("http://" + Mdns.NAME + ":" + PORT)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (started) return;
        started = true;
        // Wi-Fi drops multicast without this, and with it the argon.local answers.
        multicast = getSystemService(WifiManager.class).createMulticastLock("remote");
        multicast.acquire();
        // Generating the key takes a second or two on the TV's CPU; keep it off the main thread.
        new Thread(this::start, "remote-start").start();
    }

    private void start() {
        File dir = new File(getFilesDir(), "remote");
        dir.mkdirs();
        // Connects to the lights and plugs now, so the home screen has them the moment it asks.
        SmartHome.get(this);
        try {
            Link link = new Link("127.0.0.1", 5555, key(new File(dir, "adbkey")), "argon@tv", s -> Log.i(TAG, s));
            new Server(this, link, dir, path -> getAssets().open("remote/" + path), PORT, s -> Log.i(TAG, s));
            new Mdns(s -> Log.w(TAG, s));
            Log.i(TAG, "web remote on http://" + Mdns.NAME + ":" + PORT);
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "can't start the web remote", e);
        }
    }

    // This app's adb key; the TV asks once to allow it.
    private static KeyPair key(File f) throws IOException, GeneralSecurityException {
        if (f.exists()) return Adb.key(Files.readAllBytes(f.toPath()));
        KeyPair k = Adb.newKey();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(k.getPrivate().getEncoded());
        }
        return k;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
