package com.argon.launcher;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.argon.launcher.remote.RemoteProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The speakers on the Inputs tab: the TV's own and the paired Bluetooth ones, whether each is connected and which one
 * the sound goes to. Read here as Bluetooth and the audio system announce changes, while the home screen shows (needs
 * BLUETOOTH_CONNECT, granted by tools/build_install.sh). Connecting and disconnecting go through the web remote's adb
 * link (RemoteProvider): only the shell may.
 */
final class Speakers {
    interface Listener {
        void onSpeakers();

        void onSpeakerError(String error);
    }

    static final class Speaker {
        String address, name;       // address null: the TV's own speakers
        int state;                  // BluetoothProfile.STATE_*
        boolean active, busy;

        boolean tv() {
            return address == null;
        }

        boolean connected() {
            return tv() || state == BluetoothProfile.STATE_CONNECTED;
        }
    }

    private static final Uri REMOTE = Uri.parse("content://" + RemoteProvider.AUTHORITY);
    // Hidden, but sent with BLUETOOTH_CONNECT like the public ones: the speaker the sound goes to changed.
    private static final String ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    List<Speaker> list = new ArrayList<>();
    boolean bluetoothOn;
    /** Where the sound goes when it's none of these (a soundbar on HDMI ARC, headphones), else empty. */
    String elsewhere = "";

    private final Context context;
    private final Listener listener;
    private final ContentResolver resolver;
    private final AudioManager audio;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> busy = new HashSet<>(), connecting = new HashSet<>();
    private final Runnable refresh = this::refresh;
    private BluetoothA2dp a2dp;
    private boolean watching;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(i.getAction())) failedConnect(i);
            refresh();
            // The audio system moves the sound a moment after Bluetooth says so.
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 800);
        }
    };

    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            refresh();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            refresh();
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            a2dp = (BluetoothA2dp) proxy;
            refresh();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            a2dp = null;
            refresh();
        }
    };

    Speakers(Context c, Listener listener) {
        context = c;
        this.listener = listener;
        resolver = c.getContentResolver();
        audio = c.getSystemService(AudioManager.class);
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter != null && allowed()) adapter.getProfileProxy(c, profileListener, BluetoothProfile.A2DP);
    }

    private boolean allowed() {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (watching) return;
        watching = true;
        audio.registerAudioDeviceCallback(audioCallback, handler);
        if (adapter != null && allowed()) {
            IntentFilter f = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(ACTIVE_DEVICE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
            // Exported: these come from the Bluetooth app, not the system, and a not-exported receiver drops them.
            // They're protected broadcasts, so no other app can send them.
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        }
        refresh();
    }

    void stop() {
        if (!watching) return;
        watching = false;
        audio.unregisterAudioDeviceCallback(audioCallback);
        if (adapter != null && allowed()) context.unregisterReceiver(receiver);
        handler.removeCallbacks(refresh);
    }

    /** The TV's speakers first, then the paired Bluetooth ones (audio and video devices) by name. */
    private void refresh() {
        List<Speaker> out = new ArrayList<>();
        Speaker tv = new Speaker();
        tv.name = "TV speakers";
        out.add(tv);
        try {
            bluetoothOn = adapter != null && allowed() && adapter.isEnabled();
            if (bluetoothOn) {
                List<Speaker> paired = new ArrayList<>();
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    BluetoothClass c = d.getBluetoothClass();
                    if (c == null || c.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                    Speaker s = new Speaker();
                    s.address = d.getAddress();
                    s.name = TextUtils.isEmpty(d.getAlias()) ? d.getName() : d.getAlias();
                    if (TextUtils.isEmpty(s.name)) s.name = "Bluetooth speaker";
                    s.state = a2dp == null ? BluetoothProfile.STATE_DISCONNECTED : a2dp.getConnectionState(d);
                    s.busy = busy.contains(s.address);
                    paired.add(s);
                }
                paired.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                out.addAll(paired);
            }
        } catch (SecurityException e) {
            Log.w("Home", "speakers: " + e);
        }
        elsewhere = "";
        AudioDeviceInfo bt = null, other = null;
        boolean builtIn = false;
        for (AudioDeviceInfo d : audio.getAudioDevicesForAttributes(MEDIA)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = d;
            else if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) builtIn = true;
            else if (other == null) other = d;
        }
        if (bt != null) {
            for (Speaker s : out) if (!s.tv() && s.address.equalsIgnoreCase(bt.getAddress())) s.active = true;
        } else if (builtIn || other == null) {
            tv.active = true;
        } else {
            elsewhere = outputName(other);
        }
        list = out;
        listener.onSpeakers();
    }

    private static String outputName(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return "the HDMI ARC device";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "headphones";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "the audio out";
            default:
                return TextUtils.isEmpty(d.getProductName()) ? "another output" : d.getProductName().toString();
        }
    }

    /** A connect we asked for that ended without connecting: the speaker is off or out of range. */
    private void failedConnect(Intent i) {
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (d == null) return;
        int state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        if (state == BluetoothProfile.STATE_CONNECTED) connecting.remove(d.getAddress());
        if (state != BluetoothProfile.STATE_DISCONNECTED || !connecting.remove(d.getAddress())) return;
        String name = TextUtils.isEmpty(d.getAlias()) ? "The speaker" : d.getAlias();
        listener.onSpeakerError(name + " didn't connect. Is it on and nearby?");
    }

    Speaker active() {
        for (Speaker s : list) if (s.active) return s;
        return null;
    }

    void connect(Speaker s) {
        command(s, "connect");
    }

    void disconnect(Speaker s) {
        command(s, "disconnect");
    }

    /** The TV's speakers take the sound back: every Bluetooth speaker is disconnected. */
    void useTv() {
        for (Speaker s : list) if (!s.tv() && s.state != BluetoothProfile.STATE_DISCONNECTED) disconnect(s);
    }

    private void command(Speaker s, String action) {
        if (s.tv() || busy.contains(s.address)) return;
        busy.add(s.address);
        if (action.equals("connect")) connecting.add(s.address);
        else connecting.remove(s.address);
        s.busy = true;
        listener.onSpeakers();
        String address = s.address;
        io.execute(() -> {
            String error;
            try {
                Bundle extras = new Bundle();
                extras.putString("address", address);
                Bundle b = resolver.call(REMOTE, "bluetooth", action, extras);
                error = b == null ? null : b.getString("error");
            } catch (RuntimeException e) {
                Log.w("Home", "speakers: " + e);
                error = "The web remote isn't running";
            }
            String e = error;
            handler.post(() -> {
                busy.remove(address);
                if (e != null) {
                    connecting.remove(address);
                    listener.onSpeakerError(e);
                }
                refresh();
            });
        });
    }
}
