package com.argon.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

// The web remote's way in (remote/TvHome.java). Sent from the shell, which prints the reply:
//   am broadcast -a com.argon.launcher.CONTROL -p com.argon.launcher --es cmd state
//   am broadcast ... --es cmd set --es key dock --es value com.netflix.ninja,com.google.android.youtube.tv
//   am broadcast ... --es cmd banner --es pkg com.netflix.ninja
// Replies travel back as the broadcast's result data. HomeActivity applies a changed setting live
// through its prefs listener.
public class ControlReceiver extends BroadcastReceiver {
    private static final String TAG = "Control";
    private static final Set<String> STRINGS = Set.of(Config.DOCK, Config.TEAM, Config.WEATHER, Config.WIDGETS, Config.WALLPAPER);
    private static final Set<String> INTS = Set.of(Config.AMBIENT);
    private static final int BANNER_W = 320, BANNER_H = 180;

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        String cmd = intent.getStringExtra("cmd");
        try {
            if ("state".equals(cmd)) {
                setResultData(state(context, prefs).toString());
            } else if ("set".equals(cmd)) {
                setResultData(set(prefs, intent.getStringExtra("key"), intent.getStringExtra("value")));
            } else if ("banner".equals(cmd)) {
                setResultData(banner(context, intent.getStringExtra("pkg")));
            } else {
                setResultData("error: unknown cmd");
            }
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, cmd + ": " + e);
            setResultData("error: " + e.getMessage());
        }
    }

    private static JSONObject state(Context c, SharedPreferences p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("dock", new JSONArray(Config.dock(c, p)));

        JSONArray apps = new JSONArray();
        PackageManager pm = c.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            apps.put(new JSONObject().put("pkg", pkg).put("label", ri.loadLabel(pm).toString()));
        }
        o.put("apps", apps);

        o.put("team", Config.team(c, p));
        o.put("ambient", Config.ambient(c, p));
        o.put("weather", Config.weather(p));
        o.put("widgets", Config.widgets(p));
        o.put("wallpaper", Config.wallpaper(p));
        return o;
    }

    private static String set(SharedPreferences p, String key, String value) {
        if (key == null) return "error: no key";
        if (value == null) value = "";
        SharedPreferences.Editor e = p.edit();
        if (STRINGS.contains(key)) e.putString(key, value);
        else if (INTS.contains(key)) e.putInt(key, Integer.parseInt(value.trim()));
        else return "error: unknown key " + key;
        e.apply();
        return "ok";
    }

    // The app's banner (or icon on a dark card) as a base64 PNG, for the remote's dock editor.
    private static String banner(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        if (found.isEmpty()) return "error: no such app";
        ResolveInfo ri = found.get(0);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        int custom = c.getResources().getIdentifier("banner_" + pkg.replace('.', '_'), "drawable", c.getPackageName());
        if (custom != 0) banner = c.getDrawable(custom);
        Bitmap out = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (banner != null) {
            banner.setBounds(0, 0, BANNER_W, BANNER_H);
            banner.draw(canvas);
        } else {
            canvas.drawColor(Color.rgb(24, 26, 36));
            Drawable icon = ri.loadIcon(pm);
            int s = BANNER_H / 2;
            icon.setBounds((BANNER_W - s) / 2, (BANNER_H - s) / 2, (BANNER_W + s) / 2, (BANNER_H + s) / 2);
            icon.draw(canvas);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }
}
