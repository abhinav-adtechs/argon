package com.argon.launcher.remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Looper;

// Run as the shell on the TV, from this APK (Server.bluetooth):
//   CLASSPATH=<apk> app_process / com.argon.launcher.remote.BluetoothTool connect|disconnect AA:BB:CC:DD:EE:FF
// BluetoothDevice.connect() and disconnect() (every profile the device has) are system APIs that need
// BLUETOOTH_PRIVILEGED; the shell holds it, this app can't. Prints "ok" or what went wrong.
public final class BluetoothTool {
    private BluetoothTool() {}

    public static void main(String[] args) {
        try {
            System.out.println(run(args));
        } catch (Throwable e) {
            // MIUI's crash handler fails in app_process and hides the real error, so it's printed here.
            System.out.println("error: " + e);
        }
        System.exit(0);
    }

    @SuppressWarnings("deprecation")
    private static String run(String[] args) throws ReflectiveOperationException {
        Looper.prepareMainLooper();
        // What ActivityThread does for an app: without it the Bluetooth service isn't found and there's no adapter.
        Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "error: no Bluetooth";
        BluetoothDevice d = adapter.getRemoteDevice(args[1]);
        int status = (int) BluetoothDevice.class.getMethod(args[0].equals("connect") ? "connect" : "disconnect").invoke(d);
        return status == 0 ? "ok" : "error: status " + status;
    }
}
