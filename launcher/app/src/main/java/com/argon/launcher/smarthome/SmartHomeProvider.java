package com.argon.launcher.smarthome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

// The lights and plugs for the home screen, which runs in the launcher's main process: call("state") returns the
// state as JSON, call("do", command) changes something, and every change notifies STATE. It lives in the web
// remote's process, where the connections are, and isn't exported.
public final class SmartHomeProvider extends ContentProvider {
    public static final String AUTHORITY = "com.argon.launcher.smarthome";
    public static final Uri STATE = Uri.parse("content://" + AUTHORITY + "/state");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        SmartHome home = SmartHome.get(getContext());
        Bundle out = new Bundle();
        if ("state".equals(method)) {
            out.putString("json", home.state().toString());
        } else if ("do".equals(method)) {
            try {
                out.putString("error", home.command(new JSONObject(arg == null ? "{}" : arg)));
            } catch (JSONException e) {
                out.putString("error", "Bad command");
            }
        }
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
