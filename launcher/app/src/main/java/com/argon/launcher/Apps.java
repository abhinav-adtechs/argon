package com.argon.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What the home screen shows and how to open each thing: the apps (the shelf and All Apps), the TV's inputs and the
// shortcuts. Shelf apps with a known brand get a tile in the brand's colors with their logo lifted from the app's TV
// banner (the way Free My TV draws them); every other app shows its banner as is.
final class Apps {
    private Apps() {}

    private static final String TAG = "Apps";
    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, STATS = 4, TV_SETTINGS = 5, INPUTS = 6;
    /** Banner size of each card (16:9). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId;
        String symbol;      // icon for inputs and shortcuts
        Bitmap tile;        // 16:9 card: the app's banner, or its icon on a dark card
        Bitmap logo;        // lifted logo on a brand-colored tile (shelf); null = the banner
        int[] style;        // brand colors (top, bottom) and logo color (0 = as is, 1 = white)
        float logoInk, logoScale = 1;
        boolean connected = true;
        Intent launch;
    }

    /**
     * Brand tiles (like the tvOS Dock): package → top and bottom color, logo mode (0 = as is, 1 = white), how to lift
     * the logo from the banner ("tint": a white logo on a colored banner; "play": the white triangle inside the red
     * button stays white; "" = the banner's colors), and a size tweak by eye.
     */
    private static final Map<String, Object[]> BRANDS = new HashMap<>();
    static {
        BRANDS.put("com.netflix.ninja", new Object[] {new int[] {0xFF0A0A0A, 0xFF000000, 0}, "", 1f});
        BRANDS.put("com.google.android.youtube.tv", new Object[] {new int[] {0xFF1C1C1E, 0xFF09090A, 0}, "play", 1f});
        BRANDS.put("com.amazon.amazonvideo.livingroom", new Object[] {new int[] {0xFF2AA8FF, 0xFF0A68D4, 1}, "tint", 0.96f});
        BRANDS.put("com.apple.atve.androidtv.appletv", new Object[] {new int[] {0xFF1C1C1E, 0xFF000000, 0}, "", 1.1f});
    }

    private static final Map<String, Bitmap> TILES = new HashMap<>();
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    /** Every TV app (not this one), by name. `logos`: also lift brand logos not yet on disk (slow: background only). */
    static List<Item> all(Context c, boolean logos) {
        PackageManager pm = c.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, Item> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || byPkg.containsKey(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.key = it.pkg = pkg;
            it.label = ri.loadLabel(pm).toString();
            it.launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            it.tile = tile(c, ri);
            Object[] brand = BRANDS.get(pkg);
            if (brand != null) {
                it.logo = logo(c, ri, (String) brand[1], logos);
                if (it.logo != null) {
                    it.style = (int[]) brand[0];
                    it.logoInk = ink(it.logo);
                    it.logoScale = (float) brand[2];
                }
            }
            byPkg.put(pkg, it);
        }
        List<Item> out = new ArrayList<>(byPkg.values());
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /**
     * Live TV and every input: HDMI ports (a name you gave the port wins, then the name the device reports over
     * HDMI-CEC; ports with nothing sending a signal are dimmed), then analog ones (AV).
     */
    static List<Item> inputs(Context c) {
        List<Item> out = new ArrayList<>();
        Intent live = c.getPackageManager().getLeanbackLaunchIntentForPackage(c.getString(R.string.live_tv_package));
        if (live != null) {
            Item it = shortcut(LIVE_TV, "live", "Live TV", "live_tv");
            it.launch = live.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(it);
        }
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        List<TvInputInfo> hdmi = new ArrayList<>(), analog = new ArrayList<>();
        Map<String, String> devices = new HashMap<>();   // a CEC device gets its own input, a child of the port's
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(c)) continue;
                if (info.getParentId() != null) {
                    if (info.getType() == TvInputInfo.TYPE_HDMI) devices.put(info.getParentId(), String.valueOf(info.loadLabel(c)).trim());
                    continue;
                }
                switch (info.getType()) {
                    case TvInputInfo.TYPE_HDMI:
                        hdmi.add(info);
                        break;
                    case TvInputInfo.TYPE_COMPOSITE:
                    case TvInputInfo.TYPE_COMPONENT:
                    case TvInputInfo.TYPE_SVIDEO:
                    case TvInputInfo.TYPE_SCART:
                    case TvInputInfo.TYPE_VGA:
                    case TvInputInfo.TYPE_DVI:
                        analog.add(info);
                        break;
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        Pattern digit = Pattern.compile("(\\d)");
        List<Item> ports = new ArrayList<>();
        for (int i = 0; i < hdmi.size(); i++) {
            TvInputInfo info = hdmi.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            boolean on = state == TvInputManager.INPUT_STATE_CONNECTED;
            CharSequence custom = info.loadCustomLabel(c);
            String reported = devices.getOrDefault(info.getId(), "");
            String label = custom != null && custom.length() > 0 ? custom.toString()
                    : !reported.isEmpty() && on ? reported : "HDMI " + number;
            Item it = shortcut(INPUT, "hdmi" + number, label, on || custom != null ? deviceSymbol(label) : "hdmi");
            it.inputId = info.getId();
            it.connected = on;
            it.launch = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ports.add(it);
        }
        ports.sort((a, b) -> a.key.compareTo(b.key));
        out.addAll(ports);
        for (TvInputInfo info : analog) {
            CharSequence custom = info.loadCustomLabel(c);
            String label = custom != null && custom.length() > 0 ? custom.toString() : String.valueOf(info.loadLabel(c));
            Item it = shortcut(INPUT, "analog" + info.getId(), label, "tv");
            it.inputId = info.getId();
            it.launch = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(it);
        }
        return out;
    }

    /** Shortcuts in the drawer, after the inputs. */
    static List<Item> shortcuts() {
        List<Item> out = new ArrayList<>();
        out.add(shortcut(ALL_APPS, "all", "All Apps", "apps"));
        out.add(shortcut(STATS, "stats", "Your Viewing", "stats"));
        Item settings = shortcut(TV_SETTINGS, "tv-settings", "TV Settings", "tv_settings");
        settings.launch = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(settings);
        return out;
    }

    /** The round buttons at the top right. */
    static List<Item> buttons() {
        List<Item> out = new ArrayList<>();
        out.add(shortcut(INPUTS, "inputs", "Inputs", "hdmi"));
        out.add(shortcut(STATS, "stats", "Your Viewing", "stats"));
        Item settings = shortcut(TV_SETTINGS, "settings", "Settings", "settings");
        settings.launch = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(settings);
        return out;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick", "streaming", "google tv", "android tv"},
        {"speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos", "jbl", "audio", "home theater"},
        {"tv", "cable", "set-top", "set top", "tata play", "airtel", "dish", "tuner"},
    };

    private static String deviceSymbol(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    // MARK: - Cards

    /** The app's banner (res/drawable-nodpi/banner_<package with dots as underscores> replaces it), or its icon on a dark card. */
    private static synchronized Bitmap tile(Context c, ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        if (TILES.containsKey(pkg)) return TILES.get(pkg);
        Drawable d = banner(c, ri);
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        if (d != null) {
            canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
            d.setBounds(0, 0, TILE_W, TILE_H);
            d.draw(canvas);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 0, TILE_H, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, TILE_W, TILE_H, bg);
            Drawable icon = ri.loadIcon(c.getPackageManager());
            int s = Math.round(TILE_H * 0.5f);
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2);
            icon.draw(canvas);
        }
        b.setHasMipMap(true);
        TILES.put(pkg, b);
        return b;
    }

    private static Drawable banner(Context c, ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        int custom = c.getResources().getIdentifier("banner_" + pkg.replace('.', '_'), "drawable", c.getPackageName());
        if (custom != 0) return c.getDrawable(custom);
        PackageManager pm = c.getPackageManager();
        try {
            ActivityInfo ai = ri.activityInfo;
            int res = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
            if (res != 0) {
                Drawable d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return ri.activityInfo.loadBanner(pm);
    }

    // MARK: - Lifted logos (from Free My TV's HomeApps, MIT)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 1;

    private static Bitmap logo(Context c, ResolveInfo ri, String mode, boolean extract) {
        String pkg = ri.activityInfo.packageName;
        synchronized (LOGOS) {
            if (LOGOS.containsKey(pkg)) return LOGOS.get(pkg);
        }
        File file = logoFile(c, pkg), none = new File(file.getPath() + ".none");
        Bitmap logo = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = lift(banner(c, ri), mode.equals("tint"), mode.equals("play"));
                save(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(pkg, logo);
        }
        return logo;
    }

    /** The logo's file changes when the app is updated (its banner may have changed). */
    private static File logoFile(Context c, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), pkg + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void save(Bitmap logo, File file, File none) {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "logo not saved: " + e.getMessage());
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private static float ink(Bitmap logo) {
        int w = logo.getWidth(), h = logo.getHeight(), hits = 0, total = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 48)) {
            for (int x = 0; x < w; x += Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) >>> 24) > 128) hits++;
                total++;
            }
        }
        return total == 0 ? 0.4f : Math.max(0.08f, (float) hits / total);
    }

    /**
     * The logo lifted from a TV banner: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the tile is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The edges
     * are "decontaminated" (no halo from the background). Null when the logo can't be told apart from the background.
     */
    private static Bitmap lift(Drawable d, boolean tint, boolean holes) {
        if (d == null) return null;
        final int w = 768, h = 432;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(src);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        int[] out = new int[w * h];
        float[] bg = new float[3], col = new float[3];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            float v = (float) y / (h - 1);
            for (int x = 0; x < w; x++) {
                float u = (float) x / (w - 1);
                for (int i = 0; i < 3; i++) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                            - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
                }
                int p = px[y * w + x];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float dist = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                        + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                float a = smoothstep(0.07f, 0.22f, dist);
                if (a <= 0.004f) continue;
                for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                float l = lum(col), s = sat(col);
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col);                                 // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;
        if (holes) fillHoles(out, w, h);
        Bitmap trimmed = trim(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888));
        int pad = Math.max(2, Math.round(Math.max(trimmed.getWidth(), trimmed.getHeight()) * 0.02f));
        Bitmap padded = Bitmap.createBitmap(trimmed.getWidth() + 2 * pad, trimmed.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(padded).drawBitmap(trimmed, pad, pad, null);
        return padded;
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark tile. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
