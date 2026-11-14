package com.argon.launcher.remote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

// What the home screen (main process) asks of the web remote's server, which holds the adb link:
// call("bluetooth", "connect" | "disconnect", {address}) for a paired speaker. Returns {error} when it didn't work.
// Lives in the remote's process and isn't exported.
public final class RemoteProvider extends ContentProvider {
    public static final String AUTHORITY = "com.argon.launcher.remote";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"bluetooth".equals(method)) return out;
        Server s = RemoteService.server;
        String error = s == null ? "The web remote isn't running"
                : s.bluetooth(arg == null ? "" : arg, extras == null ? null : extras.getString("address"));
        if (error != null) out.putString("error", error);
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
