package com.argon.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.argon.launcher.smarthome.Palette;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, in the PS5's Welcome hub style:
 *  - top left, three tabs: Apps, Inputs and Home; top right, Your Viewing and Settings, the weather and the time;
 *  - a row of square tiles (the focused one larger, white-ringed, its name beside it): on Apps, Welcome, the Home
 *    apps and All Apps; on Inputs, Live TV and the HDMI and AV inputs; on Home, the room and its lights and plugs;
 *  - below the row, the focused tile's hub: Welcome shows the widgets (Continue Watching, For You, the match,
 *    today's viewing, the weather, the inputs, shows and movies) on the wallpaper; an app shows its own hub (the
 *    show you're partway through or its top pick as the background, Resume / Open / Options, more from the app);
 *    an input shows its name and state with a Switch button; the room shows its scenes, a light its brightness and
 *    colours (on a glow of its colour), a plug its power;
 *  - All Apps is a library grid; hold OK for Options (Move or Remove a Home app, Add to Home from the library).
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers (background, top, hub,
 * library, menu, toast) so the TV's weak GPU only redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, Recs.Listener, Weather.Listener, Devices.Listener {
    interface Host {
        /** Opens it with the system zoom growing out of `from` (screen coordinates). */
        void launch(Intent intent, RectF from);

        void openStats();
    }

    private static final float M = 120, RIGHT = 1800, ROW_TOP = 128, SMALL = 104, BIG = 168, TILE_GAP = 12,
            LABEL_BASE = 282;
    private static final float HUB_TOP = 532, COL_W = 504, COL_GAP = 26, UNIT = 150, UNIT_GAP = 26, HUB_LAYER_TOP = 440;
    private static final int WHITE = 0xFFFFFFFF, TEXT2 = 0xBDFFFFFF, TEXT3 = 0x8FFFFFFF, CARD = 0xE011131A,
            GLASS = 0x29FFFFFF, GREEN = 0xFF43D17A, LIVE_RED = 0xFFFF453A, INK = 0xFF000000;
    // The PS5's motion: quick, no overshoot. Response is the spring's period in seconds (Spring.java).
    private static final float TILE_RESPONSE = 0.24f;
    private static final int P_TILE = 0, P_RING = 1, P_SHADOW = 2, P_LABEL = 3;
    private static final float RING_PAD = 12, SHADOW_PAD = 48, LABEL_W = 720, LABEL_H = 64, LABEL_ASCENT = 46;
    // A new row (another tab, or back home) slides in from the right, tile after tile; the old one slides away.
    private static final long ENTER_MS = 420, ENTER_STAGGER = 32, LEAVE_MS = 180;
    private static final float ENTER_DX = 110, LEAVE_DX = 50;
    // How much the focused thing grows (PS5: cards and buttons lift toward you), and how far OK presses it in.
    private static final float FACE_PAD = 28;
    private static final float CARD_LIFT = 0.05f, BUTTON_LIFT = 0.04f, LIB_LIFT = 0.06f, PRESS_DIP = 0.05f;
    // The picture follows shortly after the hub, once the row has stopped; moving on cancels a change not yet started,
    // so a full-screen fade never starts in the middle of a slide.
    private static final long TOAST_MS = 6000, SETTLE_MS = 150;
    private static final float FADE_MS = 340, BG_SETTLE_MS = 1300, BG_ZOOM = 0.035f;
    private static final long HUB_SETTLE_MS = 150, HUB_WAIT_MS = 300, HUB_OUT_MS = 80, HUB_IN_MS = 320, TAB_HUB_MS = 260;
    private static final float HUB_RISE = 22, HUB_SINK = 8;
    private static final int TAB_APPS = 0, TAB_INPUTS = 1, TAB_HOME = 2, TOP_STATS = 3, TOP_SETTINGS = 4;
    private static final String[] TABS = {"Apps", "Inputs", "Home"};
    private static final int Z_TOP = 0, Z_ROW = 1, Z_HUB = 2;
    private static final int T_WELCOME = 0, T_APP = 1, T_ALL = 2, T_INPUT = 3, T_ROOM = 4, T_DEVICE = 5;
    private static final int BG = 0, TOP = 1, HUB = 2, SHEET = 3, MENU = 4, TOAST = 5;
    private static final int LIB_COLUMNS = 8;
    private static final float LIB_TILE = 182, LIB_GAP = 28, LIB_ROW = 262, LIB_TOP = 168;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    /** Draws a target's focused look (see FaceView). */
    private interface Face {
        void draw(Canvas c);
    }

    /** A tile in the row. */
    private static final class Tile {
        int kind;
        String label;
        Apps.Item item;
        Devices.Device device;
    }

    /** Something the focus can land on in the hub; `r` is in hub coordinates (before scrolling). */
    private static final class Target {
        final RectF r = new RectF();
        final Spring focus = new Spring(0.2f, 1f, 0);
        Face face;          // its focused look, drawn into a FaceView (null: the hub redraws as the focus moves)
        float lift;         // how much that look grows
        float radius;
        Runnable action;
        boolean slider;            // Left and Right change it (a light's brightness) instead of moving on
    }

    /** A Welcome hub widget and where the packing put it. */
    private static final class Widget {
        String id;
        boolean large;
        final RectF r = new RectF();
        int first = -1, count;     // its targets
    }

    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final Recs recs;
    private final Weather weather;
    private final Artwork artwork;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TvInputManager tvInputs;
    private final Layer bgLayer, bgLayer2, topLayer, hubLayer, hubLayer2, hubWide, sheetLayer, menuLayer, toastLayer;

    // Focus
    private int zone = Z_ROW, tab = TAB_APPS, topIndex;
    private final Spring[] topFocus = new Spring[5];
    private final RectF[] topRects = new RectF[5];

    // The row
    private List<Tile> row = new ArrayList<>();
    private final int[] rowIndexOf = new int[3];
    private int rowIndex;
    private Spring[] tileFocus = new Spring[0];
    private final List<Pieces> pieces = new ArrayList<>(), leaving = new ArrayList<>();
    private RowGroup rowGroup;
    private long enterAt, leaveAt;
    private boolean rowStale, tabChanging;
    // The row's place is in pixels at 1080p: still once it's within a quarter of one (an invisible tail of sub-pixel
    // moves kept the whole row changing for half a second after every press).
    private final Spring rowStart = new Spring(0.3f, 1f, M, 0.25f), rowRing = new Spring(0.18f, 1f, 1);
    // By package and the banner and logo they're made from (a reload gives new items, not new pictures).
    private final Map<String, Bitmap> tileArt = new HashMap<>();

    // Apps and inputs
    private List<Apps.Item> all = new ArrayList<>(), shelf = new ArrayList<>(), inputs = new ArrayList<>();
    private boolean moving;

    // The hub of the focused tile
    private final List<Target> targets = new ArrayList<>();
    private final List<Widget> widgets = new ArrayList<>();
    private int targetIndex, hubKind = -1;
    private Apps.Item hubItem;
    private Devices.Device hubDevice;
    private String hubDeviceId, pendingDevice, rowLook = "";
    private final Spring hubScroll = new Spring(0.26f, 1f, 0);
    // Two hub views: the one in front shows the focused tile's hub, the other the last hub fading away. A new hub is
    // drawn only once the row stops (a redraw mid-slide would stall it); meanwhile the old one fades out.
    private Layer hubFront, hubBack;
    private boolean hubPending;
    private long hubPendingAt, hubOutAt, hubInAt;
    private boolean hubSwapped, drawingFace, facesStale;
    private int hubWidth;
    private final FaceView[] faces = new FaceView[2];
    private Shows.Show hubShow;          // app hub: the show partway through, or
    private Recs.Pick hubPick;           // its top pick
    private final List<Object> hubCards = new ArrayList<>();
    private int hubButtons;

    // Background: the wallpaper (Welcome, inputs) or an app's picture, cross-faded when the focus settles
    // Two background views, each drawn once with its picture; they cross-fade by opacity (nothing is redrawn while
    // they fade) and the new picture settles from a slight zoom, as on the PS5.
    private Bitmap waves, photo, bgTo, bgNext;
    private Layer bgIn;
    private long bgFadeAt;
    private String wallpaperSetting = "", photoLoaded = "";
    private final Runnable settleBackdrop = () -> showBackdrop(bgNext);
    private final Map<Bitmap, Bitmap> heroBgs = new IdentityHashMap<>();
    private final Map<String, Bitmap> glows = new HashMap<>();

    // Data from HomeActivity
    private boolean footballOn;
    private Football.Match match;
    private WatchStats stats;
    private List<Shows.Show> continueShows = new ArrayList<>();
    private final Devices devices;

    // All Apps (the library)
    private boolean sheetOpen;
    private int gridIndex;
    private Spring[] gridFocus = new Spring[0];
    private final Spring sheet = new Spring(0.26f, 0.9f, 0), sheetScroll = new Spring(0.22f, 1f, 0);
    private final RectF focusedCell = new RectF();

    // Options (hold OK)
    private final Spring menu = new Spring(0.16f, 1f, 0);
    // OK held down: the focused thing dips in until it's released.
    private final Spring press = new Spring(0.12f, 1f, 0);
    private final List<String> menuLabels = new ArrayList<>(), menuGlyphs = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private final RectF menuRect = new RectF();
    private boolean menuOpen;
    private int menuIndex;

    // Toasts, and the hint while moving a Home app
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private String toast, hint;
    private long toastAt;

    // Frames
    private boolean running, topDirty, hubDirty, sheetDirty, menuDirty, bgDirty, motionDirty = true, okDown, longDone, backDown;
    private long lastNanos, clockMinute = -1;
    private int w, h;
    private float k = 1;
    private String clock = "";

    private Paint tabOn, tabOff, topText, rowLabel, smallLabel, widgetTitle, widgetRight, body, bodyDim, small, big, huge,
            heroTitle, heroMeta, button, buttonDark, sectionTitle, cardTitle, cardMeta, badge, menuText, sheetTitle,
            sheetCount, hintText, keyCap, toastTitle, toastText, number, ringLabel;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG),
            scrim = new Paint(Paint.DITHER_FLAG), picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<Bitmap, BitmapShader> shaders = new IdentityHashMap<>();
    private final Map<String, String> fitted = new HashMap<>();
    private android.graphics.PathEffect dash;
    private final Matrix matrix = new Matrix();
    private final Shadows shadows = new Shadows();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final Path path = new Path();
    private final float[] radii = new float[8];

    /** One layer of the screen. It draws in full-screen coordinates. */
    private final class Layer extends View {
        private final int kind;
        Bitmap pic;     // a background view's picture

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override public boolean hasOverlappingRendering() {
            return kind != BG && kind != TOP;
        }

        @Override protected void onDraw(Canvas c) {
            if (tabOn == null) return;
            c.translate(-getLeft(), -getTop());
            switch (kind) {
                case BG: drawBackground(c, pic); break;
                case TOP: drawTop(c); break;
                case HUB:
                    drawHub(c);
                    // Its targets' faces exist now: the next frame gives the focused one its FaceView.
                    facesStale = true;
                    break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                default: drawToast(c); break;
            }
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            recs.refreshIfStale();
            weather.refreshIfStale();
            dataChanged();      // new picks every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a, Host host, SharedPreferences prefs) {
        super(a);
        activity = a;
        this.host = host;
        this.prefs = prefs;
        recs = Recs.get(a);
        weather = Weather.get(a);
        artwork = new Artwork(a, this::dataChanged);
        click = new Click(a);
        devices = new Devices(a, this);
        tvInputs = a.getSystemService(TvInputManager.class);
        setFocusable(true);
        bgLayer = new Layer(a, BG);
        bgLayer2 = new Layer(a, BG);
        bgLayer2.setAlpha(0);
        bgIn = bgLayer;
        topLayer = new Layer(a, TOP);
        hubLayer = new Layer(a, HUB);
        hubLayer2 = new Layer(a, HUB);
        hubLayer2.setAlpha(0);
        hubWide = new Layer(a, HUB);
        hubWide.setAlpha(0);
        hubFront = hubLayer;
        hubBack = hubLayer2;
        sheetLayer = new Layer(a, SHEET);
        menuLayer = new Layer(a, MENU);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers where there's a lot drawn that fades or moves as one (the hubs, the library, the menu): moving
        // or fading a layer is one cheap pass, while redrawing what's in it every frame is what drops frames here. Not
        // the background (one picture already) or the top bar (mostly empty: a layer is composited over all of it).
        for (View v : new View[] {hubLayer, hubLayer2, hubWide, sheetLayer, menuLayer, toastLayer}) v.setLayerType(LAYER_TYPE_HARDWARE, null);
        rowGroup = new RowGroup(a);
        faces[0] = new FaceView(a);
        faces[1] = new FaceView(a);
        for (View v : new View[] {bgLayer, bgLayer2, topLayer, hubLayer2, hubLayer, hubWide, faces[0], faces[1], rowGroup, sheetLayer, menuLayer, toastLayer}) addView(v);
        sheetLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.18f, 0.9f, 0);
            topRects[i] = new RectF();
        }
        weather.setPlace(Config.weather(prefs));
        loadWallpaper();
        // First time: right away, without lifting logos (a cold start opens with the apps already there).
        setApps(Apps.all(a, false));
        setInputs(Apps.inputs(a));
    }

    // MARK: - Lifecycle and data (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        Ui.init(activity);
        lastNanos = 0;
        recs.addListener(this);
        weather.addListener(this);
        devices.start();
        recs.refreshIfStale();
        weather.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        reload();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        clockMinute = -1;
        motionDirty = true;
        enterAt = SystemClock.uptimeMillis();
        rowStale = true;
        invalidateAll();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void pause() {
        if (!running) return;
        running = false;
        if (moving) endMove();
        if (menuOpen) {
            closeMenu();
            menu.snap(0);
            motionDirty = true;
            updateMotion(SystemClock.uptimeMillis());
        }
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        recs.removeListener(this);
        weather.removeListener(this);
        devices.stop();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
        artwork.shutdown();
    }

    /** Home pressed while the home screen shows: Welcome focused, everything closed. */
    void reset() {
        if (moving) endMove();
        if (menuOpen) closeMenu();
        if (sheetOpen) closeSheet();
        zone = Z_ROW;
        rowIndexOf[TAB_APPS] = rowIndexOf[TAB_INPUTS] = rowIndexOf[TAB_HOME] = 0;
        selectTab(TAB_APPS);
        retarget(false);
        motionDirty = true;
        enterAt = SystemClock.uptimeMillis();
        rowStale = true;
    }

    void setFootball(boolean on, Football.Match m) {
        footballOn = on;
        match = m;
        dataChanged();
    }

    void setStats(WatchStats s) {
        stats = s;
        dataChanged();
    }

    void setContinue(List<Shows.Show> list) {
        continueShows = list;
        dataChanged();
    }

    /** The place, the widgets, the wallpaper and the Home apps, from prefs (the web remote changes them). */
    void readSettings() {
        weather.setPlace(Config.weather(prefs));
        loadWallpaper();
        if (!all.isEmpty()) applyDock();
        dataChanged();
    }

    @Override public void onRecs() {
        dataChanged();
    }

    @Override public void onWeather() {
        hubDirty = topDirty = true;
    }

    /** A light or plug changed (here, on the remote, in Google Home or at the switch): its tile, hub and glow follow. */
    /**
     * Only redraws what shows them: the Home tab's tiles when one of them looks different, its hub, the Living room
     * widget. Anywhere else the update costs nothing (this GPU drops frames redrawing a whole hub).
     */
    @Override public void onDevices() {
        if (tabOn == null) return;
        applyPendingDevice();
        if (tab == TAB_HOME) {
            String tiles = tileLook();
            if (!tiles.equals(rowLook)) {
                rowLook = tiles;
                buildRow();
            }
        }
        // While the row moves the new hub waits for it to stop (applyHub), and is laid out then from this state.
        if (!hubPending && (hubKind == T_ROOM || hubKind == T_DEVICE || (hubKind == T_WELCOME && livingShown()))) {
            layoutHub(false);
            if (hubKind == T_DEVICE) updateBackdrop();
        }
    }

    // What the Home tab's tiles show: names, icons, on, reachable and colour.
    private String tileLook() {
        StringBuilder sb = new StringBuilder(devices.room);
        for (Devices.Device d : devices.list) sb.append('|').append(d.id).append(d.name).append(d.icon).append(d.on).append(d.online).append(d.rgb);
        return sb.toString();
    }

    private boolean livingShown() {
        for (Widget wd : widgets) if (wd.id.equals("living")) return true;
        return false;
    }

    /** A light's or plug's page on the Home tab (from the web remote), as soon as the devices are known. */
    void showDevice(String id) {
        pendingDevice = id;
        if (tabOn != null) onDevices();
    }

    private void applyPendingDevice() {
        if (pendingDevice == null) return;
        for (int i = 0; i < devices.list.size(); i++) {
            if (!devices.list.get(i).id.equals(pendingDevice)) continue;
            pendingDevice = null;
            if (menuOpen) closeMenu();
            if (sheetOpen) closeSheet();
            zone = Z_ROW;
            rowIndexOf[TAB_HOME] = i + 1;
            if (tab != TAB_HOME) selectTab(TAB_HOME);
            else focusRow(i + 1);
            retarget(false);
            return;
        }
    }

    @Override public void onDeviceError(String error) {
        showToast(error + ".");
    }

    private void invalidateAll() {
        for (View v : new View[] {bgLayer, bgLayer2, topLayer, hubFront, toastLayer}) v.invalidate();
    }

    /** Something the hub or the background shows changed: lay the hub out again, keeping the focus where it was. */
    private void dataChanged() {
        if (tabOn == null) return;
        layoutHub(false);
        updateBackdrop();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(bgLayer, width, height);
        measure(bgLayer2, width, height);
        measure(topLayer, width, Math.round(330 * kk));
        // Only as tall as the hubs draw (from the app hub's title down): every layer is composited on every frame.
        measure(hubLayer, width, height - Math.round(HUB_LAYER_TOP * kk));
        measure(hubLayer2, width, height - Math.round(HUB_LAYER_TOP * kk));
        measure(hubWide, Math.max(width, hubWidth), height - Math.round(HUB_LAYER_TOP * kk));
        measure(sheetLayer, width, height);
        measure(menuLayer, width, height);
        measure(toastLayer, Math.round(720 * kk), Math.round(220 * kk));
        measure(rowGroup, width, Math.round(420 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        bgLayer.layout(0, 0, width, height);
        bgLayer2.layout(0, 0, width, height);
        for (View v : new View[] {bgLayer, bgLayer2}) {
            v.setPivotX(width / 2f);
            v.setPivotY(height / 3f);
        }
        place(topLayer, 0, 0);
        place(hubLayer, 0, Math.round(HUB_LAYER_TOP * kk));
        place(hubLayer2, 0, Math.round(HUB_LAYER_TOP * kk));
        place(hubWide, 0, Math.round(HUB_LAYER_TOP * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        menuLayer.layout(0, 0, width, height);
        place(rowGroup, 0, 0);
        place(toastLayer, width - toastLayer.getMeasuredWidth() - Math.round(36 * kk), Math.round(36 * kk));
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        makePaints();
        tileArt.clear();
        fitted.clear();
        dash = null;
        layoutTop();
        buildRow();
        layoutHub(true);
        updateBackdrop();
        motionDirty = true;
    }

    private void makePaints() {
        tabOn = Ui.text(Ui.semibold, 34 * k, WHITE);
        tabOff = Ui.text(Ui.regular, 34 * k, 0x9EFFFFFF);
        topText = Ui.numbers(Ui.regular, 32 * k, WHITE);
        rowLabel = Ui.text(Ui.regular, 32 * k, WHITE);
        smallLabel = Ui.text(Ui.medium, 16 * k, 0xCCFFFFFF);
        smallLabel.setTextAlign(Paint.Align.CENTER);
        widgetTitle = Ui.text(Ui.semibold, 24 * k, WHITE);
        widgetRight = Ui.numbers(Ui.medium, 22 * k, TEXT2);
        widgetRight.setTextAlign(Paint.Align.RIGHT);
        body = Ui.text(Ui.medium, 22 * k, WHITE);
        bodyDim = Ui.numbers(Ui.regular, 19 * k, 0xA8FFFFFF);
        small = Ui.numbers(Ui.medium, 18 * k, 0xA8FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        big = Ui.numbers(Ui.light, 56 * k, WHITE);
        huge = Ui.text(Ui.light, 96 * k, WHITE);
        huge.setLetterSpacing(-0.02f);
        heroTitle = Ui.text(Ui.displaySemibold, 76 * k, WHITE);
        heroTitle.setLetterSpacing(-0.01f);
        heroMeta = Ui.text(Ui.regular, 24 * k, 0xDBFFFFFF);
        button = Ui.text(Ui.semibold, 28 * k, INK);
        buttonDark = Ui.text(Ui.semibold, 26 * k, WHITE);
        sectionTitle = Ui.text(Ui.semibold, 26 * k, WHITE);
        cardTitle = Ui.text(Ui.semibold, 24 * k, WHITE);
        cardMeta = Ui.text(Ui.regular, 18 * k, 0xBDFFFFFF);
        badge = Ui.text(Ui.semibold, 15 * k, INK);
        menuText = Ui.text(Ui.semibold, 26 * k, INK);
        sheetTitle = Ui.text(Ui.semibold, 40 * k, WHITE);
        sheetCount = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
        hintText = Ui.text(Ui.regular, 22 * k, WHITE);
        keyCap = Ui.text(Ui.semibold, 17 * k, INK);
        toastTitle = Ui.text(Ui.semibold, 20 * k, 0xB3FFFFFF);
        toastText = Ui.text(Ui.medium, 26 * k, WHITE);
        number = Ui.numbers(Ui.semibold, 26 * k, WHITE);
        number.setTextAlign(Paint.Align.CENTER);
        ringLabel = Ui.text(Ui.medium, 18 * k, 0xD1FFFFFF);
        ringLabel.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean topMoving = step(topFocus, dt);
        boolean rowMoving = step(tileFocus, dt) | rowStart.step(dt) | rowRing.step(dt) | rowAnimating(up);
        boolean pressing = press.step(dt);
        rowMoving |= pressing && zone == Z_ROW && !sheetOpen;
        boolean scrolling = hubScroll.step(dt), hubMoving = false, facesMoving = scrolling;
        if (scrolling || motionDirty) hubFront.setTranslationX(-Math.round(hubScroll.value * k));
        if (pressing && zone == Z_HUB && !sheetOpen) {
            Target t = targetIndex < targets.size() ? targets.get(targetIndex) : null;
            if (t != null && t.face != null) facesMoving = true;
            else hubMoving = true;
        }
        for (Target t : targets) {
            if (!t.focus.step(dt)) continue;
            if (t.face == null) hubMoving = true;
            else facesMoving = true;
        }
        // The frame that draws a new hub is a heavy one: it lands once the row has stopped moving, where it can't show.
        if (hubPending && up >= hubPendingAt && (rowStill() || up >= hubPendingAt + HUB_WAIT_MS)) {
            // The rest of the glide is a couple of pixels: end it, so the row and the rising hub never change in the
            // same frame (together they back the GPU up by a couple of frames).
            for (Spring sp : tileFocus) sp.snap(sp.target);
            rowStart.snap(rowStart.target);
            rowStale = true;
            applyHub();
        }
        boolean fadeMoving = hubAnimating();
        boolean sheetMoving = step(gridFocus, dt) | sheetScroll.step(dt) | (pressing && sheetOpen);
        boolean sheetFading = sheet.step(dt), menuMoving = menu.step(dt);
        tickToast(up);
        boolean bgFading = tickBackgroundFade(up);

        if (fadeMoving || sheetFading || menuMoving || motionDirty) updateMotion(up);
        if (topMoving || topDirty) topLayer.invalidate();
        if (rowMoving || rowStale) placeRow(up);
        if (hubMoving || hubDirty) hubFront.invalidate();
        if (facesMoving || motionDirty || facesStale) {
            facesStale = false;
            placeFaces();
        }
        if (sheetMoving || sheetDirty) sheetLayer.invalidate();
        if (menuDirty) menuLayer.invalidate();
        topDirty = hubDirty = sheetDirty = menuDirty = bgDirty = motionDirty = false;
        long minute = System.currentTimeMillis() / 60_000;
        if (minute != clockMinute) {
            clockMinute = minute;
            clock = Ui.time(System.currentTimeMillis());
            topLayer.invalidate();
            if (sheetOpen) sheetLayer.invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** The library, the menu and the hubs coming and going: the layers' properties (their content isn't redrawn). */
    private void updateMotion(long up) {
        float s = Ui.clamp(sheet.value), home = 1 - s;
        topLayer.setAlpha(home);
        rowGroup.setAlpha(home);
        placeHub(up, home);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.03f - 0.03f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
        float m = Ui.clamp(menu.value);
        menuLayer.setAlpha(m);
        menuLayer.setPivotX(menuRect.left);
        menuLayer.setPivotY(menuRect.top);
        menuLayer.setScaleX(0.94f + 0.06f * m);
        menuLayer.setScaleY(0.94f + 0.06f * m);
        menuLayer.setVisibility(m > 0.002f || menuOpen ? VISIBLE : INVISIBLE);
    }

    // MARK: - Background

    private void loadWallpaper() {
        String setting = Config.wallpaper(prefs);
        boolean wantPhoto = setting.startsWith("photo");
        wallpaperSetting = setting;
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            Bitmap wv = waves == null ? Wallpaper.waves(c) : null;
            Bitmap ph = wantPhoto && !setting.equals(photoLoaded) ? Wallpaper.photo(c) : null;
            handler.post(() -> {
                if (wv != null) waves = wv;
                if (ph != null) {
                    photo = ph;
                    photoLoaded = setting;
                }
                updateBackdrop();
            });
        });
    }

    /** What the wallpaper setting shows right now (null: the drawn gradient until "waves" is ready). */
    private Bitmap wallpaper() {
        if (wallpaperSetting.startsWith("photo") && photo != null) return photo;
        if (wallpaperSetting.equals("pick")) {
            List<Recs.Pick> top = recs.now(null);
            Backdrop b = top.isEmpty() ? null : recs.backdrop(top.get(0));
            if (b != null) return heroBg(b.hero);
        }
        return waves;
    }

    /** The background for the focused tile: an app's picture, or the wallpaper; swapped once the focus settles. */
    private void updateBackdrop() {
        Bitmap want;
        Backdrop shown = null;
        if (hubKind == T_APP && hubItem != null) {
            shown = hubShow != null ? artwork.get(hubShow.image) : hubPick != null ? recs.backdrop(hubPick) : null;
            want = shown != null ? heroBg(shown.hero) : wallpaper();
        } else if (hubKind == T_DEVICE && hubDevice != null && hubDevice.on && hubDevice.online) {
            want = glow(hubDevice);
        } else {
            want = wallpaper();
        }
        Backdrop.shown = shown;
        if (want == bgTo) {
            handler.removeCallbacks(settleBackdrop);
            return;
        }
        bgNext = want;
        handler.removeCallbacks(settleBackdrop);
        if (bgTo == null && bgFadeAt == 0) showBackdrop(want);
        else handler.postDelayed(settleBackdrop, SETTLE_MS);
        motionDirty = true;
    }

    private void showBackdrop(Bitmap b) {
        if (b == bgTo) return;
        // A fade still running ends where it was going.
        if (bgFadeAt != 0) {
            bgFadeAt = 1;
            tickBackgroundFade(Long.MAX_VALUE);
        }
        boolean first = bgTo == null;
        bgTo = b;
        bgIn = bgIn == bgLayer ? bgLayer2 : bgLayer;
        bgIn.pic = b;
        bgIn.invalidate();
        if (first) {
            bgIn.setAlpha(1);
            (bgIn == bgLayer ? bgLayer2 : bgLayer).setAlpha(0);
            return;
        }
        bgFadeAt = SystemClock.uptimeMillis();
    }

    /**
     * The new picture fades in over the old one and settles from a slight zoom; the old one stays opaque underneath
     * and is hidden once covered, so only one picture ever blends.
     */
    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        Layer out = bgIn == bgLayer ? bgLayer2 : bgLayer;
        float t = up - bgFadeAt;
        float i = Ui.ease(t / FADE_MS), z = Ui.out(t / BG_SETTLE_MS), scale = 1 + BG_ZOOM * (1 - z);
        // The incoming view must be above the outgoing one: the views keep their order, so the top one leaves by
        // fading out over the new picture (already opaque underneath).
        if (bgIn == bgLayer2) {
            bgIn.setAlpha(i);
            out.setAlpha(i < 1 ? 1 : 0);
        } else {
            bgIn.setAlpha(1);
            out.setAlpha(1 - i);
        }
        bgIn.setScaleX(scale);
        bgIn.setScaleY(scale);
        if (z < 1) return true;
        bgFadeAt = 0;
        return false;
    }

    /**
     * One background view: its picture, or the drawn gradient until there's one. The scrims are baked into the
     * pictures (see heroBg and Wallpaper): a full-screen gradient costs this GPU as much as a picture.
     */
    private void drawBackground(Canvas c, Bitmap pic) {
        if (pic == null) {
            fill.setShader(new RadialGradient(1380 * k, 240 * k, 1500 * k, new int[] {0xFF1D56C4, 0xFF0D2C74, 0xFF061331, 0xFF02040C},
                    new float[] {0, 0.26f, 0.58f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(screen, fill);
            fill.setShader(null);
            return;
        }
        drawFull(c, pic, 1);
    }

    /**
     * An app's picture with the PS5's dark scrims for the text over it (left and bottom, and the top for the row),
     * rendered once on the GPU.
     */
    private Bitmap heroBg(Bitmap hero) {
        Bitmap b = heroBgs.get(hero);
        if (b != null) return b;
        int bw = hero.getWidth(), bh = hero.getHeight();
        Picture p = new Picture();
        Canvas c = p.beginRecording(bw, bh);
        c.drawBitmap(hero, 0, 0, null);
        Paint s = new Paint(Paint.DITHER_FLAG);
        s.setShader(new LinearGradient(0, 0, bw, 0, new int[] {0xF2030408, 0xD9030408, 0x73030408, 0x26030408},
                new float[] {0, 0.3f, 0.58f, 0.8f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, s);
        s.setShader(new LinearGradient(0, bh, 0, 0, new int[] {0xFA030408, 0xE6030408, 0x8C030408, 0x00030408},
                new float[] {0, 0.3f, 0.5f, 0.7f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, s);
        s.setShader(new LinearGradient(0, 0, 0, bh / 3f, 0xA603040A, 0x0003040A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh / 3f, s);
        p.endRecording();
        b = Bitmap.createBitmap(p);
        if (heroBgs.size() >= 4) heroBgs.clear();
        heroBgs.put(hero, b);
        return b;
    }

    private void drawFull(Canvas c, Bitmap b, float alpha) {
        if (alpha <= 0.004f) return;
        picture.setAlpha(Math.round(255 * Math.min(1, alpha)));
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
        tmp.set((w - bw) / 2, (h - bh) / 3, (w + bw) / 2, (h - bh) / 3 + bh);
        c.drawBitmap(b, null, tmp, picture);
        picture.setAlpha(255);
    }

    // MARK: - Top: tabs, buttons, weather, time, the live match

    private void layoutTop() {
        float x = M * k, cy = 74 * k, gap = 56 * k;
        for (int i = 0; i < TABS.length; i++) {
            float tw = Math.max(tabOn.measureText(TABS[i]), tabOff.measureText(TABS[i]));
            topRects[i].set(x - 20 * k, cy - 30 * k, x + tw + 20 * k, cy + 30 * k);
            x += tw + gap;
        }
        // Right to left: the time, the weather, then Settings and Your Viewing (focusable).
        float right = RIGHT * k - topText.measureText("12:00 AM") - 42 * k - topText.measureText("00°") - 48 * k - 48 * k;
        float b = 64 * k;
        topRects[TOP_SETTINGS].set(right - b, cy - b / 2, right, cy + b / 2);
        topRects[TOP_STATS].set(right - b - 34 * k - b, cy - b / 2, right - b - 34 * k, cy + b / 2);
    }

    private void drawTop(Canvas c) {
        float cy = 74 * k;
        for (int i = 0; i < TABS.length; i++) {
            RectF r = topRects[i];
            float f = Ui.clamp(topFocus[i].value);
            Paint p = i == tab ? tabOn : tabOff;
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(WHITE, f));
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            }
            int color = p.getColor();
            p.setColor(blend(color, INK, f));
            c.drawText(TABS[i], r.left + 20 * k, cy + Ui.halfCap(p), p);
            p.setColor(color);
        }
        String[] symbols = {"stats", "settings"}, labels = {"Your Viewing", "Settings"};
        for (int i = 0; i < 2; i++) {
            RectF r = topRects[TOP_STATS + i];
            float f = Ui.clamp(topFocus[TOP_STATS + i].value);
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(WHITE, f));
                c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
                smallLabel.setAlpha(Math.round(255 * f));
                smallLabel.setTextSize(20 * k);
                c.drawText(labels[i], r.centerX(), r.bottom + 30 * k, smallLabel);
                smallLabel.setTextSize(16 * k);
                smallLabel.setAlpha(0xCC);
            }
            Symbols.draw(getContext(), c, symbols[i], r.centerX(), r.centerY(), 30 * k, blend(WHITE, INK, f));
        }
        // The weather and the time.
        float x = RIGHT * k;
        float base = cy + Ui.halfCap(topText);
        float tw = topText.measureText(clock);
        c.drawText(clock, x - tw, base, topText);
        x -= tw + 42 * k;
        Weather.Data wx = weather.data;
        if (!Double.isNaN(wx.temp)) {
            String t = Weather.degrees(wx.temp);
            float dw = topText.measureText(t);
            c.drawText(t, x - dw, base, topText);
            x -= dw + 10 * k;
            String sym = Symbols.weather(wx.code, wx.isDay);
            float sw = Symbols.width(getContext(), sym, 30 * k);
            Symbols.draw(getContext(), c, sym, x - sw / 2, cy, 30 * k, WHITE);
        }
        drawLiveChip(c, Math.min(topRects[TOP_STATS].left, x) - 40 * k, cy);
    }

    /** While the club plays: a red LIVE pill with the score and minute, left of the buttons. */
    private void drawLiveChip(Canvas c, float right, float cy) {
        Football.Match m = match;
        if (!footballOn || m == null || m.state != Football.LIVE) return;
        String text = abbr(m.home) + "  " + nz(m.home.score) + "–" + nz(m.away.score) + "  " + abbr(m.away) + "   " + nz(m.detail);
        Paint p = widgetTitle;
        float tw = p.measureText(text), hh = 46 * k, pad = 18 * k, dot = 10 * k;
        tmp.set(right - tw - 2 * pad - dot - 12 * k, cy - hh / 2, right, cy + hh / 2);
        fill.setColor(0x38FF453A);
        c.drawRoundRect(tmp, hh / 2, hh / 2, fill);
        fill.setColor(LIVE_RED);
        c.drawCircle(tmp.left + pad + dot / 2, cy, dot / 2, fill);
        c.drawText(text, tmp.left + pad + dot + 12 * k, cy + Ui.halfCap(p), p);
    }

    // MARK: - The row

    /** The row for the current tab: Welcome, the Home apps and All Apps; or Live TV and the inputs. */
    private void buildRow() {
        List<Tile> list = new ArrayList<>();
        if (tab == TAB_APPS) {
            Tile welcome = new Tile();
            welcome.kind = T_WELCOME;
            welcome.label = "Welcome";
            list.add(welcome);
            for (Apps.Item it : shelf) {
                Tile t = new Tile();
                t.kind = T_APP;
                t.item = it;
                t.label = it.label;
                list.add(t);
            }
            Tile lib = new Tile();
            lib.kind = T_ALL;
            lib.label = "All Apps";
            list.add(lib);
        } else if (tab == TAB_INPUTS) {
            for (Apps.Item it : inputs) {
                Tile t = new Tile();
                t.kind = T_INPUT;
                t.item = it;
                t.label = it.label;
                list.add(t);
            }
        } else {
            Tile room = new Tile();
            room.kind = T_ROOM;
            room.label = devices.room;
            list.add(room);
            for (Devices.Device d : devices.list) {
                Tile t = new Tile();
                t.kind = T_DEVICE;
                t.device = d;
                t.label = d.name;
                list.add(t);
            }
        }
        boolean resize = list.size() != tileFocus.length;
        row = list;
        if (resize) {
            tileFocus = new Spring[list.size()];
            for (int i = 0; i < tileFocus.length; i++) tileFocus[i] = new Spring(TILE_RESPONSE, 1f, 0, 0.004f);
        }
        rowIndex = Math.max(0, Math.min(rowIndexOf[tab], list.size() - 1));
        rowIndexOf[tab] = rowIndex;
        boolean animate = tabChanging;
        buildPieces(animate);
        tabChanging = false;
        retarget(resize);
        if (animate) {
            // A new tab's row slides in already in place (not growing from the last tab's focus).
            for (Spring sp : tileFocus) sp.snap(sp.target);
            rowStart.snap(rowStart.target);
        }
        // Placed now, so the new pieces never show a frame at their starting opacity.
        placeRow(SystemClock.uptimeMillis());
        topDirty = true;
    }

    private float rowStartFor(int index) {
        return index == 0 ? M : M - (index - 1) * (SMALL + TILE_GAP);
    }

    /** Where tile i is drawn right now (its size follows its focus; the row slides to keep the focus in place). */
    private void tileRect(int i, RectF out) {
        float x = rowStart.value * k, top = ROW_TOP * k;
        for (int j = 0; j < i; j++) x += tileSize(j) + TILE_GAP * k;
        float s = tileSize(i);
        out.set(x, top, x + s, top + s);
    }

    private float tileSize(int i) {
        return (SMALL + (BIG - SMALL) * Ui.clamp(tileFocus[i].value)) * k;
    }

    // MARK: - The row's pieces

    // Each tile, its ring, its shadow and its name are views of their own, drawn once at the focused size; the row
    // slides, grows and fades them through their properties. Redrawing the row on every frame of a move is what dropped
    // frames on this TV; moving what's already drawn doesn't.

    /** A tile's views: the tile, its focus ring, its shadow and its name. */
    private final class Pieces {
        final Piece shadow = new Piece(P_SHADOW), tile = new Piece(P_TILE), ring = new Piece(P_RING), label = new Piece(P_LABEL);

        Pieces(Tile t) {
            for (Piece p : all()) p.tile = t;
        }

        Piece[] all() {
            return new Piece[] {shadow, tile, ring, label};
        }
    }

    private final class Piece extends View {
        final int kind;
        Tile tile;

        Piece(int kind) {
            super(HomeView.this.getContext());
            this.kind = kind;
            setPivotX(0);
            setPivotY(0);
            setAlpha(0);
            float big = BIG * k;
            int size = Math.round(kind == P_TILE ? big : kind == P_RING ? big + 2 * RING_PAD * k
                    : kind == P_SHADOW ? big + 2 * SHADOW_PAD * k : 0);
            if (kind == P_LABEL) layout(0, 0, Math.round(LABEL_W * k), Math.round(LABEL_H * k));
            else layout(0, 0, size, size);
        }

        // Fading draws each part with the alpha (no offscreen pass per piece).
        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            if (tile == null || tabOn == null) return;
            float big = BIG * k;
            switch (kind) {
                case P_TILE:
                    cell.set(0, 0, big, big);
                    drawTileBody(c, tile, cell);
                    if (moving && row.indexOf(tile) == rowIndex) {
                        fill.setColor(0xD9000000);
                        c.drawCircle(big / 2, big - 30 * k, 22 * k, fill);
                        Glyphs.draw(c, "move", big / 2, big - 30 * k, 30 * k, WHITE);
                    }
                    break;
                case P_RING: {
                    float pad = RING_PAD * k;
                    cell.set(pad, pad, pad + big, pad + big);
                    ring(c, cell, 18 * k, 1);
                    break;
                }
                case P_SHADOW: {
                    float pad = SHADOW_PAD * k;
                    shadows.draw(c, pad + big / 2, pad + big / 2 + 16 * k, big, big, 18 * k, 26 * k, 0x8C000000, 1);
                    break;
                }
                default:
                    c.drawText(fit(tile.label, rowLabel, 700 * k), 0, LABEL_ASCENT * k, rowLabel);
                    break;
            }
        }
    }

    /** Holds the pieces (shadows under tiles under rings, names on top); laid out by hand. */
    private final class RowGroup extends ViewGroup {
        RowGroup(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /** New pieces for the row (the old ones leave with an animation when the tab changed, else go at once). */
    private void buildPieces(boolean animate) {
        long up = SystemClock.uptimeMillis();
        for (Pieces p : pieces) {
            if (animate) leaving.add(p);
            else for (Piece v : p.all()) rowGroup.removeView(v);
        }
        if (animate) leaveAt = up;
        pieces.clear();
        for (Tile t : row) pieces.add(new Pieces(t));
        // In layers: every shadow, then every tile, ring and name, so a growing tile never covers its neighbour's ring.
        for (int layer = 0; layer < 4; layer++) {
            for (Pieces p : pieces) rowGroup.addView(p.all()[layer]);
        }
        if (animate) enterAt = up;
        rowStale = true;
    }

    /** Puts every piece where the springs say, this frame. */
    private void placeRow(long up) {
        float limit = (M - SMALL - TILE_GAP) * k, big = BIG * k;
        int first = -1;
        for (int i = 0; i < pieces.size(); i++) {
            Pieces p = pieces.get(i);
            tileRect(i, cell);
            float f = Ui.clamp(tileFocus[i].value);
            float edge = cell.left > w ? 0 : Ui.clamp((cell.left - limit) / ((SMALL + TILE_GAP) * k));
            if (edge > 0 && first < 0) first = i;
            // Coming in (a new tab, or back home): from the right, one tile after another.
            float e = enterAt == 0 ? 1 : Ui.clamp((up - enterAt - ENTER_STAGGER * Math.max(0, i - Math.max(first, 0))) / (float) ENTER_MS);
            float dx = (1 - Ui.out(e)) * ENTER_DX * k, alpha = edge * Ui.ease(e / 0.6f);
            float dip = i == rowIndex && zone == Z_ROW && !sheetOpen ? 1 - PRESS_DIP * Ui.clamp(press.value) : 1;
            float size = cell.width() * dip, x = cell.left + (cell.width() - size) / 2 + dx, y = cell.top + (cell.height() - size) / 2;
            float sc = size / big;
            place(p.tile, x, y, sc, alpha);
            place(p.ring, x - RING_PAD * k * sc, y - RING_PAD * k * sc, sc, alpha * f * Ui.clamp(rowRing.value));
            place(p.shadow, x - SHADOW_PAD * k * sc, y - SHADOW_PAD * k * sc, sc, alpha * f);
            // The name: the old one leaves in the first part of a move and the new one arrives in the last, sliding
            // out from its tile, so two never overlap.
            float a = Ui.ease(Ui.clamp((f - 0.55f) / 0.45f));
            place(p.label, cell.right + dx + TILE_GAP * k - (1 - a) * 14 * k, (LABEL_BASE - LABEL_ASCENT) * k, 1, alpha * a);
        }
        float l = leaveAt == 0 ? 1 : Ui.clamp((up - leaveAt) / (float) LEAVE_MS);
        for (Pieces p : leaving) {
            for (Piece v : p.all()) {
                if (l >= 1) rowGroup.removeView(v);
                else {
                    if (v.getTag() == null) v.setTag(new float[] {v.getTranslationX(), v.getAlpha()});
                    float[] from = (float[]) v.getTag();
                    v.setTranslationX(from[0] - Ui.ease(l) * LEAVE_DX * k);
                    v.setAlpha(from[1] * (1 - Ui.ease(l)));
                }
            }
        }
        if (l >= 1) {
            leaving.clear();
            leaveAt = 0;
        }
        if (enterAt != 0 && up - enterAt > ENTER_MS + ENTER_STAGGER * pieces.size()) enterAt = 0;
        rowStale = false;
    }

    private static void place(View v, float x, float y, float scale, float alpha) {
        v.setTranslationX(x);
        v.setTranslationY(y);
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setAlpha(Math.min(1, alpha));
    }

    private boolean rowAnimating(long up) {
        return enterAt != 0 || leaveAt != 0;
    }

    private void redrawPieces() {
        for (Pieces p : pieces) for (Piece v : p.all()) v.invalidate();
    }

    /** A tile drawn at the focused size (the row scales it down). */
    private void drawTileBody(Canvas c, Tile t, RectF r) {
        float radius = 18 * k;
        switch (t.kind) {
            case T_WELCOME:
                fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF343A48, 0xFF171A22, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, radius, radius, fill);
                fill.setShader(null);
                Glyphs.welcome(c, r.centerX(), r.centerY(), r.width() * 0.5f, WHITE);
                break;
            case T_ALL:
                fill.setColor(0xE01E212A);
                c.drawRoundRect(r, radius, radius, fill);
                Glyphs.draw(c, "grid", r.centerX(), r.centerY(), r.width() * 0.38f, WHITE);
                break;
            case T_INPUT: {
                boolean off = t.item.kind == Apps.INPUT && !t.item.connected;
                fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, off ? 0xB31C1F28 : 0xFF2C313D,
                        off ? 0xB3121419 : 0xFF171A22, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, radius, radius, fill);
                fill.setShader(null);
                drawInputSymbol(c, t.item, r.centerX(), r.top + r.height() * 0.42f, r.width() * 0.36f, off ? 0x80FFFFFF : WHITE);
                // Big enough to read once the row scales the tile down.
                smallLabel.setTextSize(24 * k);
                smallLabel.setColor(off ? 0x80FFFFFF : 0xE6FFFFFF);
                String label = fit(t.item.label, smallLabel, r.width() - 14 * k);
                c.drawText(label, r.centerX(), r.top + r.height() * 0.8f, smallLabel);
                smallLabel.setTextSize(16 * k);
                smallLabel.setColor(0xCCFFFFFF);
                break;
            }
            case T_ROOM:
            case T_DEVICE:
                drawHomeTile(c, t, r, radius);
                break;
            default:
                c.drawBitmap(art(t.item), null, r, image);
                break;
        }
        fill.setAlpha(255);
    }

    /**
     * Registers the target's focused look and returns the focus to draw it with: 1 inside its FaceView, 0 in the hub
     * (which so never redraws for a focus move; the FaceView over it fades and grows instead).
     */
    private float face(Target t, float lift, Face face) {
        if (drawingFace) return 1;
        t.face = face;
        t.lift = lift;
        return 0;
    }

    /**
     * One target's focused look over the hub, drawn once when the focus lands on it; it fades and grows by its
     * properties as the target's focus spring moves (two of them: the one arriving and the one leaving).
     */
    private final class FaceView extends View {
        Target target;

        FaceView(Context c) {
            super(c);
            setAlpha(0);
        }

        // Mostly a ring around a big widget: drawn directly, fading each part (a layer would cost its whole area).
        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        void show(Target t) {
            target = t;
            float pad = FACE_PAD * k;
            layout(Math.round(t.r.left - pad), Math.round(t.r.top - pad), Math.round(t.r.right + pad), Math.round(t.r.bottom + pad));
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (target == null || target.face == null) return;
            c.translate(-getLeft(), -getTop());
            drawingFace = true;
            try {
                target.face.draw(c);
            } finally {
                drawingFace = false;
            }
        }
    }

    /** The focused target gets a FaceView (its own if it still has one, else the one free longest). */
    private void showFace() {
        Target t = zone == Z_HUB && !sheetOpen && targetIndex < targets.size() ? targets.get(targetIndex) : null;
        if (t == null || t.face == null) return;
        for (FaceView v : faces) if (v.target == t) return;
        FaceView pick = faces[0];
        for (FaceView v : faces) {
            float f = v.target == null ? -1 : v.target.focus.value;
            if (f < (pick.target == null ? -1 : pick.target.focus.value)) pick = v;
        }
        pick.show(t);
    }

    /** Puts the faces where their targets are (the Welcome hub scrolls) at their focus. */
    private void placeFaces() {
        showFace();     // faces are known once the hub has been drawn
        float home = 1 - Ui.clamp(sheet.value);
        for (FaceView v : faces) {
            Target t = v.target;
            boolean gone = t != null && !targets.contains(t);    // the hub was laid out again
            float f = t == null || gone ? 0 : Ui.clamp(t.focus.value);
            if (f <= 0.002f) {
                v.setAlpha(0);
                if (gone || (t != null && t.focus.target == 0)) v.target = null;
                continue;
            }
            boolean held = zone == Z_HUB && targetIndex < targets.size() && targets.get(targetIndex) == t;
            float sc = 1 + t.lift * f - (held ? PRESS_DIP * Ui.clamp(press.value) : 0);
            v.setAlpha(home * f);
            v.setScaleX(sc);
            v.setScaleY(sc);
            v.setTranslationX(-Math.round(hubScroll.value * k));
            v.setTranslationY(hubFront.getTranslationY());
        }
    }

    /** The PS5 focus: a white ring around it, and a faint wider one outside that. */
    private void ring(Canvas c, RectF r, float radius, float f) {
        stroke.setShader(null);
        stroke.setColor(Ui.alpha(WHITE, f));
        stroke.setStrokeWidth(4 * k);
        tmp.set(r.left - 2 * k, r.top - 2 * k, r.right + 2 * k, r.bottom + 2 * k);
        c.drawRoundRect(tmp, radius + 2 * k, radius + 2 * k, stroke);
        stroke.setColor(Ui.alpha(0x24FFFFFF, f));
        stroke.setStrokeWidth(7 * k);
        tmp.set(r.left - 7.5f * k, r.top - 7.5f * k, r.right + 7.5f * k, r.bottom + 7.5f * k);
        c.drawRoundRect(tmp, radius + 7.5f * k, radius + 7.5f * k, stroke);
    }

    private Bitmap art(Apps.Item it) {
        String key = it.pkg + "@" + System.identityHashCode(it.tile) + "@" + (it.logo == null ? 0 : System.identityHashCode(it.logo));
        Bitmap b = tileArt.get(key);
        if (b == null) {
            if (tileArt.size() > 60) tileArt.clear();
            b = Tiles.app(it, Math.round(BIG * k * 1.1f), 18 * k * 1.1f);
            tileArt.put(key, b);
        }
        return b;
    }

    private static boolean isHdmi(Apps.Item it) {
        return it.kind == Apps.INPUT && it.key != null && it.key.startsWith("hdmi");
    }

    private void drawInputSymbol(Canvas c, Apps.Item it, float cx, float cy, float size, int color) {
        if (it.kind == Apps.LIVE_TV) Glyphs.draw(c, "tv", cx, cy, size, color);
        else if ("hdmi".equals(it.symbol)) Glyphs.draw(c, "hdmi", cx, cy, size, color);
        else if ("tv".equals(it.symbol)) Glyphs.draw(c, "monitor", cx, cy, size, color);
        else Symbols.draw(getContext(), c, it.symbol, cx, cy, size * 0.8f, color);
    }

    // MARK: - Apps

    private String appLabel(String pkg) {
        for (Apps.Item it : all) if (it.pkg.equals(pkg)) return it.label;
        return "";
    }

    private Apps.Item appItem(String pkg) {
        for (Apps.Item it : all) if (it.pkg.equals(pkg)) return it;
        return null;
    }

    private Intent appIntent(String pkg) {
        Apps.Item it = appItem(pkg);
        if (it != null) return it.launch;
        Intent i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private void setApps(List<Apps.Item> list) {
        all = list;
        applyDock();
        if (sheetOpen) sheetDirty = true;
        setLibraryFocus();
    }

    private void setInputs(List<Apps.Item> list) {
        inputs = list;
        if (tab == TAB_INPUTS && tabOn != null) buildRow();
        dataChanged();
    }

    /** The Home apps in the saved order (apps no longer installed are skipped). */
    private void applyDock() {
        Map<String, Apps.Item> byPkg = new HashMap<>();
        for (Apps.Item it : all) byPkg.put(it.pkg, it);
        List<Apps.Item> s = new ArrayList<>();
        Set<String> docked = new HashSet<>();
        for (String pkg : Config.dock(activity, prefs)) {
            Apps.Item it = byPkg.get(pkg);
            if (it != null && docked.add(pkg)) s.add(it);
        }
        shelf = s;
        if (tab == TAB_APPS && tabOn != null) {
            buildRow();
            layoutHub(false);
        }
    }

    private void saveDock() {
        List<String> pkgs = new ArrayList<>();
        for (Apps.Item it : shelf) pkgs.add(it.pkg);
        Config.saveDock(prefs, pkgs);
    }

    private boolean onHome(Apps.Item it) {
        for (Apps.Item s : shelf) if (s.pkg.equals(it.pkg)) return true;
        return false;
    }

    // MARK: - Moving and removing Home apps (Options)

    private void startMove() {
        if (shelf.size() < 2 || row.get(rowIndex).kind != T_APP) return;
        moving = true;
        hint = "Move with ◀ ▶, then OK";
        redrawPieces();
        toastLayer.invalidate();
    }

    private void shiftShelf(int dir) {
        int at = rowIndex - 1, to = at + dir;
        if (at < 0 || to < 0 || to >= shelf.size()) return;
        List<Apps.Item> list = new ArrayList<>(shelf);
        Collections.swap(list, at, to);
        shelf = list;
        rowIndex = to + 1;
        rowIndexOf[TAB_APPS] = rowIndex;
        buildRow();
        click.move();
    }

    private void endMove() {
        moving = false;
        hint = null;
        saveDock();
        redrawPieces();
        toastLayer.invalidate();
    }

    private void removeFromHome(Apps.Item it) {
        List<Apps.Item> list = new ArrayList<>(shelf);
        list.removeIf(s -> s.pkg.equals(it.pkg));
        shelf = list;
        saveDock();
        applyDock();
        showToast(it.label + " removed from Home.");
    }

    private void addToHome(Apps.Item it) {
        List<Apps.Item> list = new ArrayList<>(shelf);
        list.add(it);
        shelf = list;
        saveDock();
        applyDock();
        showToast(it.label + " added to Home.");
    }

    // MARK: - Hubs

    /** The hub under the row for the focused tile. `snap`: no fade (first layout). */
    private void layoutHub(boolean snap) {
        if (tabOn == null || row.isEmpty()) return;
        hubPending = false;
        Tile t = row.get(rowIndex);
        boolean changed = t.kind != hubKind || (t.item == null) != (hubItem == null)
                || (t.item != null && !t.item.key.equals(hubItem.key))
                || (t.device != null && !t.device.id.equals(hubDeviceId));
        int keep = changed ? 0 : targetIndex;
        hubKind = t.kind;
        hubItem = t.item;
        hubDevice = t.device;
        hubDeviceId = t.device == null ? null : t.device.id;
        List<Float> was = new ArrayList<>();
        for (Target tg : targets) was.add(tg.focus.value);
        targets.clear();
        widgets.clear();
        hubCards.clear();
        hubShow = null;
        hubPick = null;
        hubButtons = 0;
        switch (t.kind) {
            case T_WELCOME: layoutWelcome(); break;
            case T_APP: layoutAppHub(t.item); break;
            case T_ALL: layoutButtonHub(this::openSheet); break;
            case T_ROOM: layoutRoomHub(); break;
            case T_DEVICE: layoutDeviceHub(t.device); break;
            default: layoutButtonHub(() -> launch(t.item.launch, targets.isEmpty() ? null : hubRect(targets.get(0)))); break;
        }
        targetIndex = Math.max(0, Math.min(keep, targets.size() - 1));
        if (!changed) {
            for (int i = 0; i < Math.min(was.size(), targets.size()); i++) targets.get(i).focus.snap(was.get(i));
        } else {
            hubScroll.snap(0);
            if (!snap) swapHub();
            else if ((hubKind == T_WELCOME) != (hubFront == hubWide)) {
                // A first layout shows at once, in the view that fits it.
                hubFront.setAlpha(0);
                hubFront = incomingHub(hubFront);
                hubFront.setAlpha(1);
            }
        }
        if (zone == Z_HUB && targets.isEmpty()) zone = Z_ROW;
        // Only a first layout snaps: a new tile's hub must not stop the row and its tiles mid-move.
        retarget(snap);
        hubDirty = true;
        if (changed) updateBackdrop();
        // The hub views hold the whole hub (the Welcome hub runs past the screen); they only ever grow.
        float right = 0;
        for (Widget wd : widgets) right = Math.max(right, wd.r.right);
        for (Target tg : targets) right = Math.max(right, tg.r.right);
        int need = Math.round(right + M * k);
        if (need > Math.max(w, hubWidth)) {
            hubWidth = need;
            requestLayout();
        }
    }

    private Target addTarget(float l, float t, float r, float b, float radius, Runnable action) {
        Target tg = new Target();
        tg.r.set(l * k, t * k, r * k, b * k);
        tg.radius = radius * k;
        tg.action = action;
        targets.add(tg);
        return tg;
    }

    /** A target's rect on screen (the hub scrolls sideways). */
    private RectF hubRect(Target t) {
        RectF r = new RectF(t.r);
        r.offset(-hubScroll.value * k, 0);
        return r;
    }

    /** All Apps and the inputs: a title, a line about it and one button. */
    private void layoutButtonHub(Runnable action) {
        String label = hubKind == T_ALL ? "Open All Apps" : "Switch to " + hubItem.label;
        float bw = button.measureText(label) / k + 36 + 44 + 40;
        addTarget(M, 720, M + bw, 796, 14, action);
    }

    private void drawButtonHub(Canvas c) {
        String title = hubKind == T_ALL ? "All Apps" : hubItem.label;
        String sub = hubKind == T_ALL ? all.size() + (all.size() == 1 ? " app" : " apps")
                : hubItem.kind == Apps.LIVE_TV ? "Channels from the TV's tuner"
                : !isHdmi(hubItem) ? "Analog input" : hubItem.connected ? "Connected" : "Nothing plugged in";
        String title1 = fit(title, huge, 1500 * k);
        c.drawText(title1, M * k - Ui.leftBearing(huge, title1), 620 * k, huge);
        float x = M * k, base = 680 * k;
        if (hubKind == T_INPUT && isHdmi(hubItem)) {
            fill.setColor(hubItem.connected ? GREEN : 0x80FFFFFF);
            c.drawCircle(x + 7 * k, base - Ui.halfCap(heroMeta), 7 * k, fill);
            x += 26 * k;
        }
        c.drawText(sub, x, base, heroMeta);
        Target t = targets.get(0);
        drawButton(c, t, hubKind == T_ALL ? "grid" : "arrow", hubKind == T_ALL ? "Open All Apps" : "Switch to " + hubItem.label, true);
    }

    // MARK: - App hub

    /** An app's hub: the show you're partway through (or its top pick) as the backdrop, the buttons, more from it. */
    private void layoutAppHub(Apps.Item it) {
        List<Shows.Show> mine = new ArrayList<>();
        for (Shows.Show s : continueShows) if (s.pkg.equals(it.pkg)) mine.add(s);
        List<Recs.Pick> picks = recs.forApp(it.pkg, 6);
        if (!mine.isEmpty()) hubShow = mine.get(0);
        else if (!picks.isEmpty()) hubPick = picks.get(0);
        float x = M;
        if (hubShow != null) {
            Shows.Show s = hubShow;
            x = buttonTarget(x, "Resume", true, () -> launch(s.intent, null));
        }
        x = buttonTarget(x, "Open " + it.label, hubShow == null, () -> launch(it.launch, null));
        addTarget(x, 608, x + 76, 684, 14, () -> appOptions(it, targets.get(targets.size() - 1)));
        hubButtons = targets.size();
        for (int i = 1; i < mine.size() && hubCards.size() < 4; i++) hubCards.add(mine.get(i));
        for (int i = hubPick != null ? 1 : 0; i < picks.size() && hubCards.size() < 4; i++) hubCards.add(picks.get(i));
        for (int i = 0; i < hubCards.size(); i++) {
            float l = M + i * (360 + 24);
            Object o = hubCards.get(i);
            addTarget(l, 770, l + 360, 973, 14, () -> {
                if (o instanceof Shows.Show) launch(((Shows.Show) o).intent, null);
                else launch(appIntent(((Recs.Pick) o).pkg), null);
            });
        }
    }

    private float buttonTarget(float x, String label, boolean primary, Runnable action) {
        float bw = (primary ? button : buttonDark).measureText(label) / k + (primary ? 36 + 26 + 14 + 44 : 72);
        addTarget(x, 608, x + bw, 684, 14, action);
        return x + bw + 16;
    }

    private void drawAppHub(Canvas c) {
        String title, meta;
        float progress = -1;
        if (hubShow != null) {
            title = hubShow.title;
            meta = TextUtils.isEmpty(hubShow.subtitle) ? hubItem.label : hubShow.subtitle;
            progress = hubShow.progress;
        } else if (hubPick != null) {
            title = hubPick.title;
            meta = hubPick.meta;
        } else {
            title = hubItem.label;
            meta = "";
        }
        String t1 = fit(title, heroTitle, 1100 * k);
        c.drawText(t1, M * k - Ui.leftBearing(heroTitle, t1), 516 * k, heroTitle);
        float mx = M * k, base = 570 * k;
        if (!TextUtils.isEmpty(meta)) {
            String m1 = fit(meta, heroMeta, 900 * k);
            c.drawText(m1, mx, base, heroMeta);
            mx += heroMeta.measureText(m1) + 18 * k;
        }
        if (progress >= 0) bar(c, mx, base - Ui.halfCap(heroMeta) - 3 * k, 220 * k, 6 * k, progress, 0x42FFFFFF);
        int i = 0;
        if (hubShow != null) drawButton(c, targets.get(i++), "play", "Resume", true);
        drawButton(c, targets.get(i), hubShow == null ? "play" : null, "Open " + hubItem.label, hubShow == null);
        i++;
        drawIconButton(c, targets.get(i), "dots");
        if (!hubCards.isEmpty()) {
            c.drawText(hubShow != null && hubCards.get(0) instanceof Shows.Show ? "Continue on " + hubItem.label
                    : "Top on " + hubItem.label + " this week", M * k, 746 * k, sectionTitle);
        }
        for (int n = 0; n < hubCards.size(); n++) {
            Target tg = targets.get(hubButtons + n);
            Object o = hubCards.get(n);
            if (o instanceof Shows.Show) {
                Shows.Show s = (Shows.Show) o;
                drawCard(c, tg, artwork.get(s.image), s.title, s.subtitle, s.progress);
            } else {
                Recs.Pick p = (Recs.Pick) o;
                drawCard(c, tg, recs.backdrop(p), p.title, meta(p), -1);
            }
        }
    }

    private void drawButton(Canvas c, Target t, String glyph, String label, boolean primary) {
        RectF r = t.r;
        float f = face(t, BUTTON_LIFT, cc -> drawButton(cc, t, glyph, label, primary));
        int save = c.save();
        fill.setColor(primary ? WHITE : blend(GLASS, WHITE, f));
        c.drawRoundRect(r, t.radius, t.radius, fill);
        Paint p = primary ? button : buttonDark;
        int color = primary ? INK : blend(WHITE, INK, f);
        p.setColor(color);
        float x = r.left + (glyph == null ? 36 : 36) * k;
        if (glyph != null) {
            Glyphs.draw(c, glyph, x + 13 * k, r.centerY(), 28 * k, color);
            x += 26 * k + 14 * k;
        }
        c.drawText(label, x, r.centerY() + Ui.halfCap(p), p);
        p.setColor(primary ? INK : WHITE);
        if (f > 0.01f) ring(c, r, t.radius, f);
        c.restoreToCount(save);
    }

    private void drawIconButton(Canvas c, Target t, String glyph) {
        float f = face(t, BUTTON_LIFT, cc -> drawIconButton(cc, t, glyph));
        int save = c.save();
        fill.setColor(blend(GLASS, WHITE, f));
        c.drawRoundRect(t.r, t.radius, t.radius, fill);
        Glyphs.draw(c, glyph, t.r.centerX(), t.r.centerY(), 34 * k, blend(WHITE, INK, f));
        if (f > 0.01f) ring(c, t.r, t.radius, f);
        c.restoreToCount(save);
    }

    /**
     * A 16:9 card: the picture (apps often print the title on it, so ours goes below it, as on the PS5), how far in
     * you are along its bottom edge, then the title and details.
     */
    private void drawCard(Canvas c, Target t, Backdrop b, String title, String meta, float progress) {
        RectF r = t.r;
        float f = face(t, CARD_LIFT, cc -> drawCard(cc, t, b, title, meta, progress));
        int save = c.save();
        if (b != null) cover(c, b.card, r, t.radius, 0.4f, true);
        else {
            fill.setColor(0xFF15171D);
            c.drawRoundRect(r, t.radius, t.radius, fill);
        }
        float pad = 16 * k;
        if (progress >= 0) {
            fill.setShader(new LinearGradient(0, r.bottom - 44 * k, 0, r.bottom, 0x0008090C, 0xB308090C, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, t.radius, t.radius, fill);
            fill.setShader(null);
            bar(c, r.left + pad, r.bottom - pad - 4 * k, r.width() - 2 * pad, 4 * k, progress, 0x4DFFFFFF);
        }
        if (f > 0.01f) ring(c, r, t.radius, f);
        // The text below stays put (the face is the picture and its ring).
        if (drawingFace) {
            c.restoreToCount(save);
            return;
        }
        float room = r.width();
        c.drawText(fit(title, cardTitle, room), r.left, r.bottom + 32 * k, cardTitle);
        if (!TextUtils.isEmpty(meta)) {
            c.drawText(fit(meta, cardMeta, room), r.left, r.bottom + 57 * k, cardMeta);
        }
        c.restoreToCount(save);
    }

    private void appOptions(Apps.Item it, Target anchor) {
        menuLabels.clear();
        menuGlyphs.clear();
        menuActions.clear();
        if (onHome(it)) {
            if (shelf.size() > 1) {
                menuLabels.add("Move");
                menuGlyphs.add("move");
                menuActions.add(() -> {
                    zone = Z_ROW;
                    for (int i = 0; i < row.size(); i++) if (row.get(i).item == it) rowIndex = rowIndexOf[TAB_APPS] = i;
                    retarget(false);
                    startMove();
                });
            }
            menuLabels.add("Remove from Home");
            menuGlyphs.add("minus");
            menuActions.add(() -> removeFromHome(it));
        } else {
            menuLabels.add("Add to Home");
            menuGlyphs.add("plus");
            menuActions.add(() -> addToHome(it));
        }
        openMenu(anchor == null ? new RectF(focusedCell) : hubRect(anchor));
    }

    // MARK: - Welcome hub

    /** The widgets from the setting, packed into columns three units tall, filling from the left (as on the PS5). */
    private void layoutWelcome() {
        List<Widget> list = new ArrayList<>();
        for (String part : Config.widgets(prefs).split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 0 || kv[0].isEmpty()) continue;
            Widget wd = new Widget();
            wd.id = kv[0];
            wd.large = kv.length < 2 || !"S".equalsIgnoreCase(kv[1]);
            if (available(wd.id)) list.add(wd);
        }
        boolean[][] used = new boolean[16][3];
        for (Widget wd : list) {
            int cols = wd.id.equals("continue") && wd.large ? 2 : 1, rows = wd.large ? 2 : 1;
            boolean placed = false;
            for (int col = 0; col < 16 - cols + 1 && !placed; col++) {
                for (int rr = 0; rr <= 3 - rows && !placed; rr++) {
                    boolean free = true;
                    for (int dc = 0; dc < cols; dc++) for (int dr = 0; dr < rows; dr++) free &= !used[col + dc][rr + dr];
                    if (!free) continue;
                    for (int dc = 0; dc < cols; dc++) for (int dr = 0; dr < rows; dr++) used[col + dc][rr + dr] = true;
                    float l = M + col * (COL_W + COL_GAP), t = HUB_TOP + rr * (UNIT + UNIT_GAP);
                    wd.r.set(l * k, t * k, (l + cols * COL_W + (cols - 1) * COL_GAP) * k, (t + rows * UNIT + (rows - 1) * UNIT_GAP) * k);
                    placed = true;
                }
            }
            if (!placed) continue;
            widgets.add(wd);
            wd.first = targets.size();
            addWidgetTargets(wd);
            wd.count = targets.size() - wd.first;
        }
    }

    private boolean available(String id) {
        switch (id) {
            case "continue": return !continueShows.isEmpty();
            case "match": return footballOn;
            case "watch": return stats != null && stats.tracking;
            case "inputs": return !inputs.isEmpty();
            case "foryou": return !recs.now(null).isEmpty();
            case "shows": return !recs.now("tv").isEmpty();
            case "movies": return !recs.now("movie").isEmpty();
            case "weather": return true;
            case "living": return devices.configured;
            default: return false;
        }
    }

    private void addWidgetTargets(Widget wd) {
        float l = wd.r.left / k, t = wd.r.top / k, r = wd.r.right / k, b = wd.r.bottom / k;
        switch (wd.id) {
            case "continue": {
                if (wd.large) {
                    for (int i = 0; i < Math.min(3, continueShows.size()); i++) {
                        float cl = l + 28 + i * (314 + 18);
                        Shows.Show s = continueShows.get(i);
                        addTarget(cl, t + 70, cl + 314, t + 70 + 177, 12, () -> launch(s.intent, null));
                    }
                } else {
                    Shows.Show s = continueShows.get(0);
                    addTarget(l, t, r, b, 18, () -> launch(s.intent, null));
                }
                break;
            }
            case "inputs": {
                int n = Math.min(5, inputs.size());
                float slot = (r - l - 40) / n;
                for (int i = 0; i < n; i++) {
                    Apps.Item it = inputs.get(i);
                    float cx = l + 20 + slot * (i + 0.5f), cy = wd.large ? t + 160 : t + 60, rad = wd.large ? 48 : 38;
                    Target tg = addTarget(cx - rad, cy - rad, cx + rad, cy + rad, rad, () -> launch(it.launch, null));
                    tg.radius = rad * k;
                }
                break;
            }
            case "living": {
                int n = Math.min(5, devices.list.size());
                float slot = (r - l - 40) / n;
                for (int i = 0; i < n; i++) {
                    Devices.Device d = devices.list.get(i);
                    float cx = l + 20 + slot * (i + 0.5f), cy = wd.large ? t + 160 : t + 60, rad = wd.large ? 48 : 38;
                    Target tg = addTarget(cx - rad, cy - rad, cx + rad, cy + rad, rad, () -> devices.toggle(d));
                    tg.radius = rad * k;
                }
                break;
            }
            case "watch":
                addTarget(l, t, r, b, 18, host::openStats);
                break;
            case "foryou":
            case "shows":
            case "movies": {
                Recs.Pick p = pickFor(wd.id);
                addTarget(l, t, r, b, 18, () -> launch(appIntent(p.pkg), null));
                break;
            }
            default:
                addTarget(l, t, r, b, 18, null);
                break;
        }
    }

    private Recs.Pick pickFor(String id) {
        List<Recs.Pick> list = recs.now(id.equals("shows") ? "tv" : id.equals("movies") ? "movie" : null);
        if (list.isEmpty()) return null;
        if (id.equals("foryou")) return list.get(0);
        // Not the same title the For You widget already shows.
        List<Recs.Pick> top = recs.now(null);
        for (Recs.Pick p : list) if (top.isEmpty() || p != top.get(0)) return p;
        return list.get(0);
    }

    /** All of it, past the screen too: it scrolls by moving its view (see placeHub), not by being redrawn. */
    private void drawWelcome(Canvas c) {
        for (Widget wd : widgets) drawWidget(c, wd);
    }

    private void drawWidget(Canvas c, Widget wd) {
        RectF r = wd.r;
        float rad = 18 * k;
        boolean whole = wd.count == 1 && Math.abs(targets.get(wd.first).r.width() - r.width()) < 2;
        float f = whole ? face(targets.get(wd.first), 0, cc -> ring(cc, r, rad, 1)) : 0;
        int save = c.save();
        fill.setColor(CARD);
        c.drawRoundRect(r, rad, rad, fill);
        switch (wd.id) {
            case "continue": drawContinue(c, wd); break;
            case "weather": drawWeather(c, wd); break;
            case "match": drawMatch(c, wd); break;
            case "watch": drawWatched(c, wd); break;
            case "inputs": drawInputs(c, wd); break;
            case "living": drawLiving(c, wd); break;
            default: drawPick(c, wd); break;
        }
        if (f > 0.01f) ring(c, r, rad, f);
        c.restoreToCount(save);
    }

    /** The widget's top line: an icon, the title, and something small on the right. */
    private void header(Canvas c, RectF r, String glyph, String symbol, String title, String right) {
        float cy = r.top + 38 * k, x = r.left + 28 * k;
        if (glyph != null) Glyphs.draw(c, glyph, x + 12 * k, cy, 26 * k, WHITE);
        else Symbols.draw(getContext(), c, symbol, x + 12 * k, cy, 22 * k, WHITE);
        c.drawText(title, x + 38 * k, cy + Ui.halfCap(widgetTitle), widgetTitle);
        if (right != null) c.drawText(right, r.right - 28 * k, cy + Ui.halfCap(widgetRight), widgetRight);
    }

    private void drawContinue(Canvas c, Widget wd) {
        RectF r = wd.r;
        header(c, r, "play", null, "Continue Watching", String.valueOf(continueShows.size()));
        if (wd.large) {
            for (int i = 0; i < wd.count; i++) {
                Target t = targets.get(wd.first + i);
                Shows.Show s = continueShows.get(i);
                String sub = TextUtils.isEmpty(s.subtitle) ? appLabel(s.pkg) : s.subtitle + " · " + appLabel(s.pkg);
                drawCard(c, t, artwork.get(s.image), s.title, sub, s.progress);
            }
            return;
        }
        Shows.Show s = continueShows.get(0);
        Backdrop b = artwork.get(s.image);
        tmp.set(r.left, r.top + 74 * k, r.right, r.bottom);
        bottomImage(c, b, tmp, 18 * k);
        float x = r.left + 28 * k;
        c.drawText(fit(s.title, cardTitle, r.width() - 56 * k), x, r.bottom - 50 * k, cardTitle);
        String sub = TextUtils.isEmpty(s.subtitle) ? appLabel(s.pkg) : s.subtitle + " · " + appLabel(s.pkg);
        c.drawText(fit(sub, cardMeta, r.width() - 56 * k), x, r.bottom - 24 * k, cardMeta);
        if (s.progress >= 0) bar(c, x, r.bottom - 12 * k, r.width() - 56 * k, 4 * k, s.progress, 0x3DFFFFFF);
    }

    private void drawPick(Canvas c, Widget wd) {
        RectF r = wd.r;
        Recs.Pick p = pickFor(wd.id);
        if (p == null) return;
        String title = wd.id.equals("foryou") ? "For You" : wd.id.equals("shows") ? "Shows" : "Movies";
        header(c, r, wd.id.equals("foryou") ? "sparkle" : wd.id.equals("shows") ? "tv" : "film", null, title, null);
        Backdrop b = recs.backdrop(p);
        tmp.set(r.left, r.top + 74 * k, r.right, r.bottom);
        bottomImage(c, b, tmp, 18 * k);
        float x = r.left + 28 * k, room = r.width() - 56 * k;
        if (wd.large || r.height() > 200 * k) {
            c.drawText(fit(p.title, cardTitle, room), x, r.bottom - 54 * k, cardTitle);
            String meta = meta(p);
            float bw = p.app == null ? 0 : badge.measureText(p.app) + 14 * k;
            c.drawText(fit(meta, cardMeta, room - bw - 16 * k), x, r.bottom - 26 * k, cardMeta);
            if (p.app != null) {
                tmp.set(r.right - 28 * k - bw, r.bottom - 46 * k, r.right - 28 * k, r.bottom - 20 * k);
                fill.setColor(WHITE);
                c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
                c.drawText(p.app, tmp.left + 7 * k, tmp.centerY() + Ui.halfCap(badge), badge);
            }
        } else {
            c.drawText(fit(p.title, cardTitle, room), x, r.bottom - 20 * k, cardTitle);
        }
    }

    /** "Crime · 4 seasons" without the app (it's on the badge) and without "Series". */
    private static String meta(Recs.Pick p) {
        List<String> parts = new ArrayList<>();
        for (String s : (p.meta == null ? "" : p.meta).split(" · ")) {
            if (s.isEmpty() || s.equals("Series") || s.equals(p.app)) continue;
            parts.add(s);
        }
        return TextUtils.join(" · ", parts);
    }

    private void drawWeather(Canvas c, Widget wd) {
        RectF r = wd.r;
        Weather.Data wx = weather.data;
        String place = wx.place.isEmpty() ? "Weather" : wx.place;
        header(c, r, null, "cloud", place, Double.isNaN(wx.max) ? null : "H " + Weather.degrees(wx.max) + " · L " + Weather.degrees(wx.min));
        float x = r.left + 28 * k;
        if (Double.isNaN(wx.temp)) {
            c.drawText("No weather right now", x, r.bottom - 34 * k, bodyDim);
            return;
        }
        String now = Weather.degrees(wx.temp);
        float base = wd.large ? r.top + 160 * k : r.bottom - 22 * k;
        big.setTextSize((wd.large ? 72 : 56) * k);
        c.drawText(now, x - Ui.leftBearing(big, now), base, big);
        float tx = x + big.measureText(now) + 20 * k;
        c.drawText(Weather.condition(wx.code, wx.isDay), tx, base - 26 * k, body);
        if (wx.sunset != 0) c.drawText("Sunset " + Ui.time(wx.sunset), tx, base, bodyDim);
        // The next hours, as many as fit on the right.
        int n = Math.min(wd.large ? 4 : 3, wx.hours.size());
        float hw = 66 * k, right = r.right - 28 * k;
        for (int i = 0; i < n; i++) {
            float cx = wd.large ? x + hw / 2 + i * (r.width() - 56 * k) / n + ((r.width() - 56 * k) / n - hw) / 2 : right - (n - 1 - i) * hw - hw / 2;
            float hb = wd.large ? r.bottom - 70 * k : base - 26 * k;
            small.setColor(0xB8FFFFFF);
            c.drawText(Weather.hour(wx.hours.get(i)), cx, hb, small);
            if (wd.large) {
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, hb + 28 * k, 22 * k, WHITE);
            }
            small.setColor(WHITE);
            c.drawText(Weather.degrees(wx.hourTemp.get(i)), cx, wd.large ? r.bottom - 14 * k : base, small);
        }
        small.setColor(0xA8FFFFFF);
    }

    private void drawMatch(Canvas c, Widget wd) {
        RectF r = wd.r;
        Football.Match m = match;
        if (m == null) {
            header(c, r, null, "football", "Football", null);
            c.drawText("No match info right now", r.left + 28 * k, r.bottom - 34 * k, bodyDim);
            return;
        }
        boolean live = m.state == Football.LIVE, scored = m.state != Football.NEXT && m.home.score != null && m.away.score != null;
        String state = live ? "● Live " + nz(m.detail) : m.state == Football.FULL_TIME ? "Full time" : day(m.kickoff);
        header(c, r, null, "football", fit(m.competition, widgetTitle, r.width() * 0.55f), state);
        if (live) {
            widgetRight.setColor(LIVE_RED);
            c.drawText(state, r.right - 28 * k, r.top + 38 * k + Ui.halfCap(widgetRight), widgetRight);
            widgetRight.setColor(TEXT2);
        }
        float cy = wd.large ? r.top + 170 * k : r.bottom - 46 * k, crest = (wd.large ? 92 : 50) * k;
        float lx = r.left + 28 * k, rx = r.right - 28 * k;
        String main = scored ? m.home.score + " – " + m.away.score : Ui.time(m.kickoff);
        big.setTextSize((wd.large ? 56 : 34) * k);
        float mw = big.measureText(main);
        c.drawText(main, r.centerX() - mw / 2, cy + Ui.halfCap(big), big);
        if (wd.large) {
            float hx = r.left + r.width() * 0.2f, ax = r.right - r.width() * 0.2f;
            drawCrest(c, m.home.logo, hx, cy, crest);
            drawCrest(c, m.away.logo, ax, cy, crest);
            ringLabel.setTextSize(22 * k);
            c.drawText(fit(m.home.name, ringLabel, r.width() * 0.34f), hx, r.bottom - 34 * k, ringLabel);
            c.drawText(fit(m.away.name, ringLabel, r.width() * 0.34f), ax, r.bottom - 34 * k, ringLabel);
            ringLabel.setTextSize(18 * k);
        } else {
            drawCrest(c, m.home.logo, lx + crest / 2, cy, crest);
            drawCrest(c, m.away.logo, rx - crest / 2, cy, crest);
            Paint p = number;
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(abbr(m.home), lx + crest + 14 * k, cy + Ui.halfCap(p), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(abbr(m.away), rx - crest - 14 * k, cy + Ui.halfCap(p), p);
            p.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawCrest(Canvas c, Bitmap logo, float cx, float cy, float size) {
        if (logo == null) return;
        tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        c.drawBitmap(logo, null, tmp, image);
    }

    /** Today's viewing, like the PS5's trophy widget: the top apps with their times, then screen time with a bar. */
    private void drawWatched(Canvas c, Widget wd) {
        RectF r = wd.r;
        WatchStats s = stats;
        header(c, r, "clock", null, "Watched today", "Total: " + WatchStats.duration(s.watchedToday));
        List<WatchStats.App> apps = s.todayApps(3);
        float x = r.left + 28 * k, right = r.right - 28 * k;
        if (wd.large) {
            if (apps.isEmpty()) {
                c.drawText("Nothing watched yet today", x, r.top + 150 * k, bodyDim);
            } else {
                float slot = (right - x) / apps.size();
                for (int i = 0; i < apps.size(); i++) {
                    WatchStats.App a = apps.get(i);
                    float cx = x + slot * (i + 0.5f), top = r.top + 92 * k, size = 64 * k;
                    Apps.Item it = appItem(a.pkg);
                    tmp.set(cx - size / 2, top, cx + size / 2, top + size);
                    if (it != null) c.drawBitmap(art(it), null, tmp, image);
                    else {
                        fill.setColor(a.color);
                        c.drawRoundRect(tmp, 12 * k, 12 * k, fill);
                    }
                    c.drawText(WatchStats.duration(a.today), cx, top + size + 34 * k, number);
                    ringLabel.setColor(0xA8FFFFFF);
                    c.drawText(fit(a.name, ringLabel, slot - 8 * k), cx, top + size + 60 * k, ringLabel);
                    ringLabel.setColor(0xD1FFFFFF);
                }
            }
        }
        float pct = s.screenToday > 0 ? Math.min(1f, s.watchedToday / (float) s.screenToday) : 0;
        float base = r.bottom - 46 * k;
        c.drawText("Screen on " + WatchStats.duration(s.screenToday), x, base, bodyDim);
        String ps = Math.round(pct * 100) + "%";
        c.drawText(ps, right - bodyDim.measureText(ps), base, bodyDim);
        if (wd.large) {
            bar(c, x, r.bottom - 30 * k, right - x, 6 * k, pct, 0x33FFFFFF);
        } else {
            // The split by app instead of the trophies.
            float sy = r.top + 78 * k, sh = 8 * k;
            long sum = 0;
            for (WatchStats.App a : apps) sum += a.today;
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(x, sy, right, sy + sh, sh / 2, sh / 2, fill);
            float ax = x;
            for (WatchStats.App a : apps) {
                float aw = sum == 0 ? 0 : (right - x) * pct * a.today / sum;
                fill.setColor(a.color);
                c.drawRoundRect(ax, sy, ax + aw, sy + sh, sh / 2, sh / 2, fill);
                ax += aw;
            }
        }
    }

    /** The inputs as rings, like the PS5's controller batteries: green when something's plugged in. */
    private void drawInputs(Canvas c, Widget wd) {
        RectF r = wd.r;
        if (wd.large) header(c, r, "hdmi", null, "Inputs", null);
        for (int i = 0; i < wd.count; i++) {
            Target t = targets.get(wd.first + i);
            Apps.Item it = inputs.get(i);
            float cx = t.r.centerX(), cy = t.r.centerY(), rad = t.r.width() / 2 - 4 * k;
            float f = face(t, BUTTON_LIFT, cc -> drawInputFace(cc, t, it));
            boolean off = it.kind == Apps.INPUT && !it.connected;
            fill.setColor(f > 0.01f ? blend(0x0FFFFFFF, WHITE, f) : off ? 0 : 0x0FFFFFFF);
            c.drawCircle(cx, cy, rad, fill);
            stroke.setStrokeWidth(4 * k);
            if (off) {
                stroke.setColor(0x4DFFFFFF);
                if (dash == null) dash = new android.graphics.DashPathEffect(new float[] {4 * k, 7 * k}, 0);
                stroke.setPathEffect(dash);
            } else {
                // Only HDMI ports know whether something is plugged in.
                stroke.setColor(isHdmi(it) ? GREEN : 0x38FFFFFF);
            }
            c.drawCircle(cx, cy, rad, stroke);
            stroke.setPathEffect(null);
            int ink = blend(off ? 0x80FFFFFF : WHITE, INK, f);
            drawInputSymbol(c, it, cx, cy, rad * 0.8f, ink);
            ringLabel.setColor(off ? 0x80FFFFFF : 0xD1FFFFFF);
            String label = fit(it.label, ringLabel, t.r.width() + 24 * k);
            c.drawText(label, cx, t.r.bottom + 26 * k, ringLabel);
            ringLabel.setColor(0xD1FFFFFF);
            if (f > 0.01f) {
                stroke.setColor(Ui.alpha(WHITE, f));
                stroke.setStrokeWidth(4 * k);
                c.drawCircle(cx, cy, rad + 6 * k, stroke);
            }
        }
    }

    /** An input's circle, focused: white, the symbol in ink, the ring (its name below stays in the hub). */
    private void drawInputFace(Canvas c, Target t, Apps.Item it) {
        float cx = t.r.centerX(), cy = t.r.centerY(), rad = t.r.width() / 2 - 4 * k;
        fill.setColor(WHITE);
        c.drawCircle(cx, cy, rad, fill);
        drawInputSymbol(c, it, cx, cy, rad * 0.8f, INK);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(4 * k);
        c.drawCircle(cx, cy, rad + 6 * k, stroke);
    }

    private void drawHub(Canvas c) {
        if (targets.isEmpty() && hubKind != T_WELCOME && hubKind != T_ROOM) return;
        switch (hubKind) {
            case T_WELCOME: drawWelcome(c); break;
            case T_APP: drawAppHub(c); break;
            case T_ROOM: drawRoomHub(c); break;
            case T_DEVICE: drawDeviceHub(c); break;
            default: drawButtonHub(c); break;
        }
    }

    // MARK: - Home: the room, its lights and plugs

    /** The room's page: everything off (or on), the lights dimmed, and the scenes. */
    private void layoutRoomHub() {
        if (!devices.configured) return;
        boolean any = devices.on > 0;
        float x = homeButton(M, any ? "Turn all off" : "Turn all on", true, () -> devices.allOff(!any));
        homeButton(x, "Dim all", false, devices::dim);
        for (int i = 0; i < Math.min(4, devices.scenes.size()); i++) {
            Devices.Scene sc = devices.scenes.get(i);
            float l = M + i * (400 + COL_GAP);
            addTarget(l, 806, l + 400, 1031, 14, () -> devices.scene(sc.id));
        }
    }

    /** A light's page (on or off, brightness, colours) or a plug's (on or off, a timer, its power). */
    private void layoutDeviceHub(Devices.Device d) {
        float x = homeButton(M, d.on ? "Turn off" : "Turn on", true, () -> devices.toggle(d));
        if (d.light()) {
            Target s = addTarget(x, 608, x + 446, 684, 14, null);
            s.slider = true;
            int i = 0;
            for (Palette.Preset p : Palette.PRESETS) {
                if (p.white() ? !(d.whites || d.colors) : !d.colors) continue;
                float l = M + i++ * 176;
                addTarget(l + 15, 806, l + 135, 926, 60, () -> devices.color(d, p.id, p.rgb));
            }
        } else if (d.on) {
            homeButton(x, timerLabel(d), false, () -> devices.timer(d, nextTimer(d.timer)));
        }
    }

    /** A button in the 1920 space with a glyph before its label; returns where the next one starts. */
    private float homeButton(float x, String label, boolean primary, Runnable action) {
        float bw = (primary ? button : buttonDark).measureText(label) / k + 36 + 26 + 14 + (primary ? 44 : 36);
        addTarget(x, 608, x + bw, 684, 14, action);
        return x + bw + 16;
    }

    private static String timerLabel(Devices.Device d) {
        if (d.timer <= 0) return "Timer";
        int min = Math.max(1, Math.round(d.timer / 60f));
        return "Off in " + (min >= 60 ? min / 60 + " h" + (min % 60 > 0 ? " " + min % 60 + " min" : "") : min + " min");
    }

    // OK on Timer: 30 min, 1 h, 2 h, then off again.
    private static int nextTimer(int s) {
        return s <= 0 ? 1800 : s <= 1800 ? 3600 : s <= 3600 ? 7200 : 0;
    }

    /** Left and Right on the brightness bar: 10% a press. */
    private void nudgeBrightness(int dx) {
        Devices.Device d = hubDevice;
        if (d == null || !d.light()) return;
        int b = d.on ? Math.round(d.bri / 10f) * 10 : 0;
        devices.brightness(d, Math.max(1, Math.min(100, b + dx * 10)));
        click.move();
    }

    private void drawRoomHub(Canvas c) {
        String t1 = fit(devices.room, huge, 1500 * k);
        c.drawText(t1, M * k - Ui.leftBearing(huge, t1), 520 * k, huge);
        String status = !devices.configured ? "Add lights and plugs from the web remote: Home screen › Smart home"
                : devices.on + " of " + devices.list.size() + " on" + (devices.watts > 0 ? " · " + number(devices.watts) + " W" : "");
        statusLine(c, status, devices.on > 0 ? 0xFFF2B04A : 0);
        if (!devices.configured) return;
        drawButton(c, targets.get(0), "power", devices.on > 0 ? "Turn all off" : "Turn all on", true);
        drawButton(c, targets.get(1), "sun", "Dim all", false);
        if (!devices.scenes.isEmpty()) c.drawText("Scenes", M * k, 776 * k, sectionTitle);
        for (int i = 2; i < targets.size(); i++) drawScene(c, targets.get(i), devices.scenes.get(i - 2));
    }

    private void statusLine(Canvas c, String text, int dot) {
        float x = M * k, base = 576 * k;
        if (dot != 0) {
            fill.setColor(dot);
            c.drawCircle(x + 7 * k, base - Ui.halfCap(heroMeta), 7 * k, fill);
            x += 26 * k;
        }
        c.drawText(fit(text, heroMeta, 1600 * k), x, base, heroMeta);
    }

    /** A scene: its lights as soft glows on a dark card, with a dot per light (filled when it's on in the scene). */
    private void drawScene(Canvas c, Target t, Devices.Scene sc) {
        RectF r = t.r;
        float f = face(t, CARD_LIFT, cc -> drawScene(cc, t, sc));
        fill.setColor(0xFF0D0F16);
        c.drawRoundRect(r, t.radius, t.radius, fill);
        int n = sc.colors.length;
        c.save();
        path.reset();
        path.addRoundRect(r, t.radius, t.radius, Path.Direction.CW);
        c.clipPath(path);
        for (int j = 0; j < n; j++) {
            if (sc.colors[j] == 0) continue;
            float cx = r.left + r.width() * (j + 0.5f) / n, cy = r.top + r.height() * 0.55f, rad = r.height() * 0.62f;
            fill.setShader(new RadialGradient(cx, cy, rad, Ui.alpha(sc.colors[j], 0.78f), Ui.alpha(sc.colors[j], 0), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad, fill);
        }
        fill.setShader(new LinearGradient(0, r.top + r.height() * 0.35f, 0, r.bottom, 0x0008090C, 0xE008090C, Shader.TileMode.CLAMP));
        c.drawRect(r, fill);
        fill.setShader(null);
        c.restore();
        float dot = 13 * k, x = r.right - 22 * k - dot;
        for (int j = n - 1; j >= 0; j--) {
            fill.setColor(sc.colors[j] != 0 ? sc.colors[j] : 0x26FFFFFF);
            c.drawCircle(x, r.top + 26 * k, dot, fill);
            x -= 2 * dot + 8 * k;
        }
        c.drawText(fit(sc.name, cardTitle, r.width() - 44 * k), r.left + 22 * k, r.bottom - 48 * k, cardTitle);
        c.drawText(fit(sc.sub, cardMeta, r.width() - 44 * k), r.left + 22 * k, r.bottom - 20 * k, cardMeta);
        if (f > 0.01f) ring(c, r, t.radius, f);
    }

    private void drawDeviceHub(Canvas c) {
        Devices.Device d = hubDevice;
        if (d == null || targets.isEmpty()) return;
        String t1 = fit(d.name, huge, 1500 * k);
        c.drawText(t1, M * k - Ui.leftBearing(huge, t1), 520 * k, huge);
        String status;
        if (!d.online) status = "Not reachable";
        else if (!d.on) status = "Off";
        else if (d.light()) status = "On · " + d.bri + "% · " + d.colorName;
        else status = "On · " + number(d.watts) + " W" + (d.since > 0 ? " · since " + Ui.time(d.since) : "");
        statusLine(c, status, !d.online ? 0x80FFFFFF : d.on ? d.rgb : 0);
        drawButton(c, targets.get(0), "power", d.on ? "Turn off" : "Turn on", true);
        if (d.light()) {
            drawBrightness(c, targets.get(1), d);
            if (targets.size() > 2) c.drawText("Colour", M * k, 776 * k, sectionTitle);
            int i = 2;
            for (Palette.Preset p : Palette.PRESETS) {
                if (p.white() ? !(d.whites || d.colors) : !d.colors) continue;
                drawSwatch(c, targets.get(i++), p, p.id.equals(d.color) && d.on);
            }
        } else {
            if (targets.size() > 1) drawButton(c, targets.get(1), "timer", timerLabel(d), false);
            c.drawText("Power", M * k, 776 * k, sectionTitle);
            String month = new SimpleDateFormat("MMMM", Locale.getDefault()).format(new Date());
            String volts = d.volts > 0 ? number(d.volts) + " V · " + String.format(Locale.US, "%.2f", d.amps) + " A" : "";
            stat(c, 0, "Now", number(d.watts), "W", volts, null);
            stat(c, 1, "Today", kwh(d.today), "kWh", d.on && d.since > 0 ? "On since " + Ui.time(d.since) : d.on ? "On" : "Off now", null);
            stat(c, 2, "This week", kwh(d.week), "kWh", "Since Monday", d.days);
            stat(c, 3, "This month", kwh(d.month), "kWh", month, null);
        }
    }

    /** The brightness bar: a glass button; focused, it turns white, with ◀ ▶ at its ends: Left / Right change it. */
    private void drawBrightness(Canvas c, Target t, Devices.Device d) {
        RectF r = t.r;
        float f = face(t, BUTTON_LIFT, cc -> drawBrightness(cc, t, d));
        fill.setColor(blend(GLASS, WHITE, f));
        c.drawRoundRect(r, t.radius, t.radius, fill);
        int ink = blend(WHITE, INK, f);
        Glyphs.draw(c, "sun", r.left + 49 * k, r.centerY(), 30 * k, ink);
        float bx = r.left + 90 * k, bw = 228 * k, bh = 10 * k, by = r.centerY() - bh / 2;
        fill.setColor(blend(0x42FFFFFF, 0x26000000, f));
        c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, fill);
        fill.setColor(ink);
        float v = d.on ? d.bri / 100f : 0;
        c.drawRoundRect(bx, by, bx + Math.max(bh, bw * v), by + bh, bh / 2, bh / 2, fill);
        buttonDark.setColor(ink);
        String pct = (d.on ? d.bri : 0) + "%";
        c.drawText(pct, r.right - 32 * k - buttonDark.measureText(pct), r.centerY() + Ui.halfCap(buttonDark), buttonDark);
        buttonDark.setColor(WHITE);
        if (f > 0.01f) {
            ring(c, r, t.radius, f);
            fill.setColor(Ui.alpha(0x8C000000, f));
            for (int dir = -1; dir <= 1; dir += 2) {
                float tip = dir < 0 ? r.left + 11 * k : r.right - 11 * k, base = tip - dir * 8 * k, cy = r.centerY();
                path.reset();
                path.moveTo(tip, cy);
                path.lineTo(base, cy - 7 * k);
                path.lineTo(base, cy + 7 * k);
                path.close();
                c.drawPath(path, fill);
            }
        }
    }

    private void drawSwatch(Canvas c, Target t, Palette.Preset p, boolean current) {
        float f = face(t, BUTTON_LIFT, cc -> drawSwatch(cc, t, p, current));
        float cx = t.r.centerX(), cy = t.r.centerY(), rad = t.r.width() / 2;
        fill.setColor(p.rgb);
        c.drawCircle(cx, cy, rad, fill);
        fill.setShader(new RadialGradient(cx - rad * 0.32f, cy - rad * 0.44f, rad * 0.8f, 0x6BFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, fill);
        fill.setShader(null);
        if (current || f > 0.01f) {
            stroke.setColor(Ui.alpha(WHITE, current ? 1 : f));
            stroke.setStrokeWidth(4 * k);
            c.drawCircle(cx, cy, rad + 8 * k, stroke);
        }
        if (f > 0.01f) {
            stroke.setColor(Ui.alpha(0x24FFFFFF, f));
            stroke.setStrokeWidth(7 * k);
            c.drawCircle(cx, cy, rad + 15 * k, stroke);
        }
        if (drawingFace) return;
        Paint lp = current ? cardTitle : ringLabel;
        float size = lp.getTextSize();
        lp.setTextSize(22 * k);
        lp.setTextAlign(Paint.Align.CENTER);
        c.drawText(p.name, cx, cy + rad + 46 * k, lp);
        lp.setTextAlign(Paint.Align.LEFT);
        lp.setTextSize(size);
        if (lp == ringLabel) ringLabel.setTextAlign(Paint.Align.CENTER);
    }

    /** One of a plug's four power cards; `bars`, the last days' kWh, today last. */
    private void stat(Canvas c, int i, String label, String value, String unit, String foot, double[] bars) {
        float l = (M + i * (400 + COL_GAP)) * k, t = 806 * k;
        tmp.set(l, t, l + 400 * k, t + 225 * k);
        fill.setColor(CARD);
        c.drawRoundRect(tmp, 14 * k, 14 * k, fill);
        float x = l + 24 * k;
        widgetRight.setTextAlign(Paint.Align.LEFT);
        c.drawText(label, x, t + 42 * k, widgetRight);
        widgetRight.setTextAlign(Paint.Align.RIGHT);
        big.setTextSize(56 * k);
        c.drawText(value, x - Ui.leftBearing(big, value), t + 152 * k, big);
        c.drawText(unit, x + big.measureText(value) + 8 * k, t + 152 * k, heroMeta);
        if (foot != null && !foot.isEmpty()) c.drawText(fit(foot, cardMeta, 360 * k), x, t + 192 * k, cardMeta);
        if (bars == null || bars.length == 0) return;
        double max = 0;
        for (double b : bars) max = Math.max(max, b);
        float bw = 150 * k / bars.length, bh = 70 * k, right = l + 376 * k, bottom = t + 196 * k;
        for (int j = 0; j < bars.length; j++) {
            float hgt = max <= 0 ? 2 * k : Math.max(2 * k, (float) (bars[j] / max) * bh);
            float bl = right - 150 * k + j * bw;
            fill.setColor(j == bars.length - 1 ? 0xFFF2B04A : 0x73F2B04A);
            c.drawRoundRect(bl, bottom - hgt, bl + bw - 4 * k, bottom, 2 * k, 2 * k, fill);
        }
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    private static String kwh(double v) {
        return String.format(Locale.US, v < 0.1 ? "%.3f" : "%.2f", v);
    }

    /** The tile of the room or of a light or plug: lit in its colour while it's on, dimmed while it can't be reached. */
    private void drawHomeTile(Canvas c, Tile t, RectF r, float radius) {
        Devices.Device d = t.device;
        boolean room = t.kind == T_ROOM, lit = d != null && d.on && d.online, gone = d != null && !d.online;
        int top = room ? 0xFF343A48 : lit ? d.rgb : gone ? 0xB31C1F28 : 0xFF2C313D;
        int bottom = room ? 0xFF171A22 : lit ? deep(d.rgb, 0.45f) : gone ? 0xB3121419 : 0xFF171A22;
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, fill);
        fill.setShader(null);
        int ink = lit && pale(d.rgb) ? 0xFF1A1206 : gone ? 0x80FFFFFF : WHITE;
        Glyphs.draw(c, room ? "house" : d.icon, r.centerX(), r.top + r.height() * 0.42f, r.width() * 0.36f, ink);
        smallLabel.setTextSize(22 * k);
        smallLabel.setColor(ink);
        c.drawText(fit(t.label, smallLabel, r.width() - 14 * k), r.centerX(), r.top + r.height() * 0.8f, smallLabel);
        smallLabel.setTextSize(16 * k);
        smallLabel.setColor(0xCCFFFFFF);
    }

    /** The Living room widget: a ring per light and plug, filled with its colour while it's on (OK switches it). */
    private void drawLiving(Canvas c, Widget wd) {
        RectF r = wd.r;
        if (wd.large) header(c, r, "house", null, devices.room, devices.on + " on");
        for (int i = 0; i < wd.count && i < devices.list.size(); i++) {
            Target t = targets.get(wd.first + i);
            Devices.Device d = devices.list.get(i);
            face(t, BUTTON_LIFT, cc -> drawLivingFace(cc, t, d));
            float cx = t.r.centerX(), cy = t.r.centerY(), rad = t.r.width() / 2 - 4 * k;
            boolean lit = d.on && d.online;
            if (lit) {
                fill.setColor(d.rgb);
                c.drawCircle(cx, cy, rad + 2 * k, fill);
                fill.setShader(new RadialGradient(cx - rad * 0.32f, cy - rad * 0.44f, rad * 0.9f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad + 2 * k, fill);
                fill.setShader(null);
            } else {
                fill.setColor(0x0FFFFFFF);
                c.drawCircle(cx, cy, rad, fill);
                stroke.setStrokeWidth(4 * k);
                stroke.setColor(d.online ? 0x38FFFFFF : 0x4DFFFFFF);
                if (!d.online) {
                    if (dash == null) dash = new android.graphics.DashPathEffect(new float[] {4 * k, 7 * k}, 0);
                    stroke.setPathEffect(dash);
                }
                c.drawCircle(cx, cy, rad, stroke);
                stroke.setPathEffect(null);
            }
            int ink = lit ? (pale(d.rgb) ? 0xFF1A1206 : WHITE) : d.online ? WHITE : 0x80FFFFFF;
            Glyphs.draw(c, d.icon, cx, cy, rad * 0.95f, ink);
            ringLabel.setColor(lit ? WHITE : d.online ? 0xD1FFFFFF : 0x80FFFFFF);
            c.drawText(fit(d.name, ringLabel, t.r.width() + 30 * k), cx, t.r.bottom + 26 * k, ringLabel);
            ringLabel.setColor(0xD1FFFFFF);
        }
    }

    /** A light's or plug's circle, focused: its colour while it's on (white while off), the icon, the ring. */
    private void drawLivingFace(Canvas c, Target t, Devices.Device d) {
        float cx = t.r.centerX(), cy = t.r.centerY(), rad = t.r.width() / 2 - 4 * k;
        boolean lit = d.on && d.online;
        fill.setColor(lit ? d.rgb : WHITE);
        c.drawCircle(cx, cy, rad + 2 * k, fill);
        if (lit) {
            fill.setShader(new RadialGradient(cx - rad * 0.32f, cy - rad * 0.44f, rad * 0.9f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, rad + 2 * k, fill);
            fill.setShader(null);
        }
        Glyphs.draw(c, d.icon, cx, cy, rad * 0.95f, lit && !pale(d.rgb) ? WHITE : INK);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(4 * k);
        c.drawCircle(cx, cy, rad + 8 * k, stroke);
    }

    /** Which light or plug of the Living room widget hub target `i` is, or -1. */
    private int livingIndex(int i) {
        for (Widget wd : widgets) if (wd.id.equals("living") && i >= wd.first && i < wd.first + wd.count) return i - wd.first;
        return -1;
    }

    /**
     * The background of a light's or plug's page while it's on: a glow of its colour from the right, with a faint
     * outline of it, and the PS5's scrims for the text baked in. Drawn once per colour, at half size.
     */
    private Bitmap glow(Devices.Device d) {
        String key = d.rgb + d.icon;
        Bitmap b = glows.get(key);
        if (b != null) return b;
        int bw = 960, bh = 540, rgb = d.rgb;
        b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(720, 260, 650, new int[] {deep(rgb, 0.62f), deep(rgb, 0.3f), deep(rgb, 0.1f), 0xFF02040C},
                new float[] {0, 0.32f, 0.64f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        p.setShader(new RadialGradient(750, 270, 260, Ui.alpha(rgb, 0.5f), Ui.alpha(rgb, 0), Shader.TileMode.CLAMP));
        c.drawCircle(750, 270, 260, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(Ui.alpha(blend(rgb, WHITE, 0.6f), 0.22f));
        Path outline = new Path();
        if ("lightbar".equals(d.icon)) {
            c.drawRoundRect(665, 150, 720, 360, 9, 9, p);
            c.drawLine(692, 360, 692, 400, p);
            c.drawLine(660, 400, 725, 400, p);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[] {3, 7}, 0));
            c.drawRoundRect(780, 150, 835, 360, 9, 9, p);
            c.drawLine(807, 360, 807, 400, p);
            c.drawLine(775, 400, 840, 400, p);
            p.setPathEffect(null);
        } else if ("lamp".equals(d.icon)) {
            outline.moveTo(700, 150);
            outline.lineTo(800, 150);
            outline.lineTo(845, 250);
            outline.lineTo(655, 250);
            outline.close();
            c.drawPath(outline, p);
            c.drawLine(750, 250, 750, 410, p);
            c.drawLine(700, 415, 800, 415, p);
        } else if ("bulb".equals(d.icon)) {
            c.drawCircle(750, 230, 80, p);
            c.drawLine(725, 320, 775, 320, p);
            c.drawLine(732, 340, 768, 340, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 550, 0, 0x8C02040C, 0x0002040C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 550, bh, p);
        p.setShader(new LinearGradient(0, 0, 0, 170, 0x9E02040C, 0x0002040C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, 170, p);
        if (glows.size() >= 6) glows.clear();
        glows.put(key, b);
        return b;
    }

    private static int deep(int rgb, float f) {
        return 0xFF000000 | (Math.round(((rgb >> 16) & 255) * f) << 16) | (Math.round(((rgb >> 8) & 255) * f) << 8) | Math.round((rgb & 255) * f);
    }

    /** Light enough that dark ink reads better on it than white (whites, warm white, amber). */
    private static boolean pale(int rgb) {
        return (0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255)) / 255 > 0.66;
    }

    // MARK: - Drawing helpers

    /** The picture filling `dst` (cropped, `alignY` 0 = top … 1 = bottom), with rounded corners, through a shader. */
    private void cover(Canvas c, Bitmap b, RectF dst, float radius, float alignY, boolean all) {
        BitmapShader s = shaders.get(b);
        if (s == null) {
            if (shaders.size() > 40) shaders.clear();
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shaders.put(b, s);
        }
        float scale = Math.max(dst.width() / b.getWidth(), dst.height() / b.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate(dst.left + (dst.width() - b.getWidth() * scale) / 2, dst.top + (dst.height() - b.getHeight() * scale) * alignY);
        s.setLocalMatrix(matrix);
        image.setShader(s);
        if (all) {
            c.drawRoundRect(dst, radius, radius, image);
        } else {
            path.reset();
            radii[0] = radii[1] = radii[2] = radii[3] = 0;
            radii[4] = radii[5] = radii[6] = radii[7] = radius;
            path.addRoundRect(dst, radii, Path.Direction.CW);
            c.drawPath(path, image);
        }
        image.setShader(null);
    }

    /** A widget's picture: edge to edge below the header, darkened toward the bottom for the text over it. */
    private void bottomImage(Canvas c, Backdrop b, RectF dst, float radius) {
        if (b != null) cover(c, b.card, dst, radius, 0.3f, false);
        fill.setShader(new LinearGradient(0, dst.top, 0, dst.bottom, new int[] {0x1A0A0B0F, 0x9E0A0B0F, 0xF70A0B0F},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        path.reset();
        radii[0] = radii[1] = radii[2] = radii[3] = 0;
        radii[4] = radii[5] = radii[6] = radii[7] = radius;
        path.addRoundRect(dst, radii, Path.Direction.CW);
        c.drawPath(path, fill);
        fill.setShader(null);
    }

    private void bar(Canvas c, float x, float y, float width, float height, float value, int track) {
        fill.setShader(null);
        fill.setColor(track);
        c.drawRoundRect(x, y, x + width, y + height, height / 2, height / 2, fill);
        fill.setColor(WHITE);
        c.drawRoundRect(x, y, x + Math.max(height, width * Ui.clamp(value)), y + height, height / 2, height / 2, fill);
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        boolean top = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        boolean tiles = aim(tileFocus, !sheetOpen ? rowIndex : -1, snap);
        float ringTarget = zone == Z_ROW ? 1 : 0;
        tiles |= rowRing.target != ringTarget;
        rowRing.target = ringTarget;
        if (snap) rowRing.snap(ringTarget);
        float start = rowStartFor(rowIndex);
        if (rowStart.target != start) {
            rowStart.target = start;
            tiles = true;
        }
        if (snap) rowStart.snap(start);
        boolean hub = false;
        for (int i = 0; i < targets.size(); i++) {
            Target tg = targets.get(i);
            float t = !sheetOpen && zone == Z_HUB && i == targetIndex ? 1 : 0;
            if (tg.focus.target != t && tg.face == null) hub = true;
            tg.focus.target = t;
            if (snap) tg.focus.snap(t);
        }
        showFace();
        motionDirty = true;
        if (zone == Z_HUB && hubKind == T_WELCOME && targetIndex < targets.size()) {
            RectF r = targets.get(targetIndex).r;
            float s = hubScroll.target;
            float right = r.right / k, left = r.left / k;
            if (right - s > RIGHT + 40) s = right - RIGHT;
            if (left - s < M) s = left - M;
            hubScroll.target = Math.max(0, s);
        } else if (zone != Z_HUB) {
            hubScroll.target = 0;
        }
        topDirty |= snap || top;
        rowStale |= snap || tiles;
        hubDirty |= snap || hub;
    }

    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    /** The next hub target in a direction (by edges, then how far off the line it is), or -1. */
    private int nextTarget(int from, int dx, int dy) {
        RectF a = targets.get(from).r;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            if (i == from) continue;
            RectF b = targets.get(i).r;
            float gap, off;
            if (dx > 0) {
                if (b.centerX() <= a.centerX() + 1 || b.left < a.left + 1) continue;
                gap = Math.max(0, b.left - a.right);
                off = overlap(a.top, a.bottom, b.top, b.bottom) ? 0 : Math.abs(b.centerY() - a.centerY());
            } else if (dx < 0) {
                if (b.centerX() >= a.centerX() - 1 || b.right > a.right - 1) continue;
                gap = Math.max(0, a.left - b.right);
                off = overlap(a.top, a.bottom, b.top, b.bottom) ? 0 : Math.abs(b.centerY() - a.centerY());
            } else if (dy > 0) {
                if (b.centerY() <= a.centerY() + 1 || b.top < a.top + 1) continue;
                gap = Math.max(0, b.top - a.bottom);
                off = overlap(a.left, a.right, b.left, b.right) ? Math.abs(b.left - a.left) * 0.1f : Math.abs(b.centerX() - a.centerX());
            } else {
                if (b.centerY() >= a.centerY() - 1 || b.bottom > a.bottom - 1) continue;
                gap = Math.max(0, a.top - b.bottom);
                off = overlap(a.left, a.right, b.left, b.right) ? Math.abs(b.left - a.left) * 0.1f : Math.abs(b.centerX() - a.centerX());
            }
            float score = gap + 2 * off;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean overlap(float a0, float a1, float b0, float b1) {
        return Math.min(a1, b1) - Math.max(a0, b0) > 4;
    }

    /** Entering the hub from the row: the target nearest the top left (the first button, the first widget). */
    private int firstTarget() {
        int best = 0;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            RectF r = targets.get(i).r;
            float score = r.top * 3 + r.left;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private void selectTab(int t) {
        if (t == tab) return;
        tab = t;
        tabChanging = true;
        buildRow();
        long up = SystemClock.uptimeMillis();
        if (!hubPending) {
            hubPending = true;
            hubOutAt = up - (long) ((1 - hubFront.getAlpha()) * HUB_OUT_MS);
            hubInAt = 0;
        }
        hubPendingAt = up + TAB_HUB_MS;
        handler.removeCallbacks(settleBackdrop);
        motionDirty = true;
    }

    private void focusRow(int index) {
        if (index == rowIndex) return;
        rowIndex = index;
        rowIndexOf[tab] = index;
        retarget(false);
        // The new hub is drawn once the row stops; until then the current one fades out (from where it is).
        long up = SystemClock.uptimeMillis();
        if (!hubPending) {
            hubPending = true;
            hubOutAt = up - (long) ((1 - hubFront.getAlpha()) * HUB_OUT_MS);
            hubInAt = 0;
        }
        hubPendingAt = up + HUB_SETTLE_MS;
        handler.removeCallbacks(settleBackdrop);
        motionDirty = true;
    }

    /** Draws the hub the row stopped on (or one the keys need now). */
    private void applyHub() {
        if (!hubPending) return;
        hubPending = false;
        hubSwapped = false;
        layoutHub(false);
        if (!hubSwapped) {
            // Back on the tile it started from: the same hub comes back.
            hubOutAt = 0;
            hubInAt = SystemClock.uptimeMillis();
        }
        motionDirty = true;
    }

    /** A new hub goes into the back view, which comes to the front; the old one fades out behind it. */
    private void swapHub() {
        long up = SystemClock.uptimeMillis();
        Layer old = hubFront;
        hubFront = incomingHub(old);
        hubBack = old;
        hubFront.setTranslationX(-Math.round(hubScroll.value * k));     // a new hub starts unscrolled; the old keeps its place
        if (hubOutAt == 0) hubOutAt = up;
        hubInAt = up;
        hubSwapped = true;
        motionDirty = true;
    }

    /** The row looks still: every tile within a few pixels of its size, the row within a pixel of its place. */
    private boolean rowStill() {
        for (Spring sp : tileFocus) if (Math.abs(sp.value - sp.target) > 0.04f) return false;
        return Math.abs(rowStart.value - rowStart.target) < 1.5f && enterAt == 0;
    }

    /**
     * The view for a new hub: the wide one for the Welcome hub (it scrolls by moving, so it holds all of it), else
     * whichever screen-wide one isn't showing. Screen-wide layers for the rest: a wider layer costs every frame.
     */
    private Layer incomingHub(Layer showing) {
        if (hubKind == T_WELCOME) return hubWide;
        if (showing == hubLayer) return hubLayer2;
        if (showing == hubLayer2) return hubLayer;
        return hubLayer.getAlpha() <= hubLayer2.getAlpha() ? hubLayer : hubLayer2;
    }

    private boolean hubAnimating() {
        return hubPending || hubInAt != 0 || hubOutAt != 0;
    }

    /**
     * The hubs' opacity and place: the old one sinking a little as it fades, the new one rising in. Whole pixels: a
     * layer moved by a fraction of a pixel is resampled, about five times the GPU work (and two frames' more delay).
     */
    private void placeHub(long up, float home) {
        float out = hubOutAt == 0 ? 1 : Ui.ease((up - hubOutAt) / (float) HUB_OUT_MS);
        float in = hubInAt == 0 ? 1 : Ui.out((up - hubInAt) / (float) HUB_IN_MS);
        Layer old = hubPending ? hubFront : hubBack;
        for (Layer v : new Layer[] {hubLayer, hubLayer2, hubWide}) if (v != hubFront && v != hubBack) v.setAlpha(0);
        old.setAlpha(home * (1 - out));
        old.setTranslationY(Math.round(out * HUB_SINK * k));
        if (hubPending) {
            hubBack.setAlpha(0);
            return;
        }
        hubFront.setAlpha(home * in);
        hubFront.setTranslationY(Math.round((1 - in) * HUB_RISE * k));
        if (in >= 1 && out >= 1) hubInAt = hubOutAt = 0;
    }

    // MARK: - All Apps (the library)

    private void setLibraryFocus() {
        if (gridFocus.length != all.size()) {
            gridFocus = new Spring[all.size()];
            for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.18f, 0.9f, 0);
        }
        gridIndex = Math.max(0, Math.min(gridIndex, all.size() - 1));
        retargetGrid(true);
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        sheetScroll.snap(0);
        setLibraryFocus();
        retarget(false);
        sheet.target = 1;
        motionDirty = sheetDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        motionDirty = true;
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (all.size() + LIB_COLUMNS - 1) / LIB_COLUMNS), visible = 3, r = gridIndex / LIB_COLUMNS;
        float target = sheetScroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        sheetScroll.target = Math.max(0, Math.min(Math.max(0, rows - visible), target));
        if (snap) sheetScroll.snap(sheetScroll.target);
        sheetDirty = true;
    }

    private void moveGrid(int dx, int dy) {
        int n = all.size();
        if (n == 0) return;
        int r = gridIndex / LIB_COLUMNS, col = gridIndex % LIB_COLUMNS, rows = (n + LIB_COLUMNS - 1) / LIB_COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / LIB_COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * LIB_COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
    }

    private void drawSheet(Canvas c) {
        fill.setShader(new RadialGradient(1500 * k, 90 * k, 1400 * k, new int[] {0xFF0E2A66, 0xFF071637, 0xFF03060F, 0xFF02040C},
                new float[] {0, 0.4f, 0.75f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(screen, fill);
        fill.setShader(null);
        float base = 74 * k + Ui.halfCap(sheetTitle);
        c.drawText("All Apps", M * k, base, sheetTitle);
        c.drawText(all.size() + (all.size() == 1 ? " app" : " apps"), M * k + sheetTitle.measureText("All Apps") + 22 * k, base, sheetCount);
        c.drawText(clock, RIGHT * k - topText.measureText(clock), 74 * k + Ui.halfCap(topText), topText);
        float shift = sheetScroll.value * LIB_ROW * k;
        for (int i = 0; i < all.size(); i++) {
            int r = i / LIB_COLUMNS, col = i % LIB_COLUMNS;
            float x = (M + col * (LIB_TILE + LIB_GAP)) * k, y = (LIB_TOP + r * LIB_ROW) * k - shift;
            if (y > 900 * k || y + LIB_ROW * k < 120 * k) continue;
            cell.set(x, y, x + LIB_TILE * k, y + LIB_TILE * k);
            float f = Ui.clamp(gridFocus[i].value);
            if (i == gridIndex) focusedCell.set(cell);
            int save = c.save();
            float lift = 1 + LIB_LIFT * f - (i == gridIndex ? PRESS_DIP * Ui.clamp(press.value) : 0);
            c.scale(lift, lift, cell.centerX(), cell.centerY());
            if (f > 0.01f) shadows.draw(c, cell.centerX(), cell.centerY() + 16 * k, cell.width(), cell.height(), 16 * k, 26 * k, Ui.alpha(0xFF000000, 0.55f * f), 1);
            Apps.Item it = all.get(i);
            c.drawBitmap(art(it), null, cell, image);
            if (f > 0.01f) ring(c, cell, 16 * k, f);
            Paint p = f > 0.5f ? cardTitle : rowLabel;
            float size = p.getTextSize();
            p.setTextSize(22 * k);
            int color = p.getColor();
            p.setColor(blend(0xCCFFFFFF, WHITE, f));
            String label = fit(it.label, p, LIB_TILE * k);
            c.drawText(label, cell.left, cell.bottom + 36 * k, p);
            p.setTextSize(size);
            p.setColor(color);
            if (onHome(it)) {
                fill.setColor(0xCCFFFFFF);
                c.drawCircle(cell.right - 18 * k, cell.top + 18 * k, 10 * k, fill);
                Glyphs.draw(c, "check", cell.right - 18 * k, cell.top + 18 * k, 14 * k, INK);
            }
            c.restoreToCount(save);
        }
        // The hints, PS5 style: a key cap and what it does.
        fill.setColor(0x1AFFFFFF);
        c.drawRect(M * k, 900 * k, RIGHT * k, 901 * k, fill);
        float x = RIGHT * k, cy = 948 * k;
        x = hint(c, "Close", "BACK", x, cy) - 34 * k;
        hint(c, "Options", "HOLD OK", x, cy);
    }

    /** Draws "[CAP] label" ending at `right`; returns where it starts. */
    private float hint(Canvas c, String label, String cap, float right, float cy) {
        float lw = hintText.measureText(label);
        c.drawText(label, right - lw, cy + Ui.halfCap(hintText), hintText);
        float cw = keyCap.measureText(cap) + 20 * k, x = right - lw - 10 * k - cw;
        tmp.set(x, cy - 17 * k, x + cw, cy + 17 * k);
        fill.setColor(0xDBFFFFFF);
        c.drawRoundRect(tmp, 6 * k, 6 * k, fill);
        c.drawText(cap, x + 10 * k, cy + Ui.halfCap(keyCap), keyCap);
        return x;
    }

    private void libraryOptions() {
        if (all.isEmpty()) return;
        Apps.Item it = all.get(gridIndex);
        menuLabels.clear();
        menuGlyphs.clear();
        menuActions.clear();
        if (onHome(it)) {
            menuLabels.add("Remove from Home");
            menuGlyphs.add("minus");
            menuActions.add(() -> removeFromHome(it));
        } else {
            menuLabels.add("Add to Home");
            menuGlyphs.add("plus");
            menuActions.add(() -> addToHome(it));
        }
        menuLabels.add("Open");
        menuGlyphs.add("play");
        menuActions.add(() -> launch(it.launch, focusedCell));
        openMenu(new RectF(focusedCell));
    }

    // MARK: - Options (hold OK)

    private void openMenu(RectF anchor) {
        menuOpen = true;
        menu.target = 1;
        menuIndex = 0;
        float mw = 420 * k, row = 68 * k, pad = 12 * k, mh = menuLabels.size() * (row + 4 * k) - 4 * k + 2 * pad;
        float x = anchor.right + 26 * k, y = anchor.top;
        if (!sheetOpen && zone == Z_ROW) {
            // Under the tile, so the row and the tile's name stay in view.
            x = anchor.left;
            y = anchor.bottom + 30 * k;
        } else if (x + mw > (RIGHT + 40) * k) {
            x = anchor.left - 26 * k - mw;
        }
        y = Math.max(40 * k, Math.min(y, h - 40 * k - mh));
        menuRect.set(x, y, x + mw, y + mh);
        menuDirty = motionDirty = true;
        click.open();
    }

    private void closeMenu() {
        menuOpen = false;
        menu.target = 0;
        motionDirty = true;
    }

    /** OK held: Options for a Home app (row) or a library app; elsewhere, the same as OK. */
    private void longPress() {
        applyHub();
        menuLabels.clear();
        menuGlyphs.clear();
        menuActions.clear();
        if (sheetOpen) {
            libraryOptions();
        } else if (zone == Z_ROW && !row.isEmpty() && row.get(rowIndex).kind == T_APP) {
            tileRect(rowIndex, focusedCell);
            appOptions(row.get(rowIndex).item, null);
        } else if (zone == Z_HUB && hubKind == T_WELCOME && livingIndex(targetIndex) >= 0) {
            // Held on a light or plug in the Living room widget: its page on the Home tab.
            rowIndexOf[TAB_HOME] = livingIndex(targetIndex) + 1;
            zone = Z_ROW;
            click.open();
            selectTab(TAB_HOME);
            retarget(false);
        } else {
            activate();
        }
    }

    private void drawMenu(Canvas c) {
        if (menuLabels.isEmpty()) return;
        float rad = 18 * k, row = 68 * k, pad = 12 * k;
        shadows.draw(c, menuRect.centerX(), menuRect.centerY() + 24 * k, menuRect.width(), menuRect.height(), rad, 40 * k, 0xB3000000, 1);
        fill.setColor(0xF720232C);
        c.drawRoundRect(menuRect, rad, rad, fill);
        stroke.setColor(0x14FFFFFF);
        stroke.setStrokeWidth(1.5f * k);
        c.drawRoundRect(menuRect, rad, rad, stroke);
        for (int i = 0; i < menuLabels.size(); i++) {
            float top = menuRect.top + pad + i * (row + 4 * k);
            tmp.set(menuRect.left + pad, top, menuRect.right - pad, top + row);
            boolean on = i == menuIndex;
            if (on) {
                fill.setColor(WHITE);
                c.drawRoundRect(tmp, 12 * k, 12 * k, fill);
            }
            int ink = on ? INK : WHITE;
            Glyphs.draw(c, menuGlyphs.get(i), tmp.left + 34 * k, tmp.centerY(), 28 * k, ink);
            menuText.setColor(ink);
            c.drawText(menuLabels.get(i), tmp.left + 64 * k, tmp.centerY() + Ui.halfCap(menuText), menuText);
        }
    }

    // MARK: - Toasts (a PS5 notification, top right)

    void showToast(String text) {
        toasts.add(text);
    }

    /** Slides in from the right and fades, out the same way: by the view's properties (drawn once per message). */
    private boolean tickToast(long up) {
        if (hint != null) {
            toastLayer.setAlpha(1);
            toastLayer.setTranslationX(0);
            return false;
        }
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null) {
            String next = toasts.poll();
            if (next != null) {
                toast = next;
                toastAt = up;
                toastLayer.invalidate();
            }
        }
        if (toast == null) return false;
        float age = (up - toastAt) / 1000f;
        float a = Math.min(Ui.out(age / 0.34f), Ui.ease((TOAST_MS / 1000f - age) / 0.4f));
        toastLayer.setAlpha(a);
        toastLayer.setTranslationX(Math.round((1 - a) * 48 * k));
        long ms = up - toastAt;
        return ms < 400 || ms > TOAST_MS - 450;
    }

    private void drawToast(Canvas c) {
        String text = hint != null ? hint : toast;
        if (text == null || toastText == null) return;
        float left = getLeft() + toastLayer.getLeft(), top = toastLayer.getTop();
        float cw = 640 * k, ch = 116 * k, x = left + toastLayer.getWidth() - cw - 8 * k, y = top + 20 * k;
        tmp.set(x, y, x + cw, y + ch);
        shadows.draw(c, tmp.centerX(), tmp.centerY() + 18 * k, cw, ch, 18 * k, 30 * k, 0x8C000000, 1);
        fill.setColor(0xF51C1F28);
        c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
        cell.set(x + 22 * k, y + 22 * k, x + 94 * k, y + 94 * k);
        fill.setColor(0x1AFFFFFF);
        c.drawRoundRect(cell, 14 * k, 14 * k, fill);
        Glyphs.draw(c, hint != null ? "move" : "bell", cell.centerX(), cell.centerY(), 36 * k, WHITE);
        c.drawText("Home", cell.right + 20 * k, y + 48 * k, toastTitle);
        String line = fit(text, toastText, cw - 160 * k);
        c.drawText(line, cell.right + 20 * k, y + 84 * k, toastText);
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<Apps.Item> apps = Apps.all(c, true);
            final List<Apps.Item> ins = Apps.inputs(c);
            handler.post(() -> {
                setApps(apps);
                setInputs(ins);
            });
        });
    }

    // MARK: - Keys: the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // A short press acts on release, and only if the press happened here (the release of an OK pressed
                // inside another app opens nothing); holding past the long-press timeout opens Options.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        longDone = false;
                        press.target = 1;
                    } else if (okDown && !longDone && e.isLongPress()) {
                        longDone = true;
                        if (!menuOpen && !moving) longPress();
                    }
                } else {
                    press.target = 0;
                    if (okDown && !longDone && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (menuOpen) {
            closeMenu();
        } else if (moving) {
            endMove();
        } else if (sheetOpen) {
            closeSheet();
        } else if (zone != Z_ROW) {
            zone = Z_ROW;
            retarget(false);
        } else if (rowIndex != 0) {
            focusRow(0);
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (dy > 0 && zone == Z_ROW) applyHub();
        if (row.isEmpty() && !sheetOpen && zone != Z_TOP) {
            if (dy < 0) {
                zone = Z_TOP;
                topIndex = tab;
                retarget(false);
            }
            return;
        }
        if (menuOpen) {
            if (dy != 0) {
                int next = Math.max(0, Math.min(menuLabels.size() - 1, menuIndex + dy));
                if (next == menuIndex) return;
                menuIndex = next;
                menuDirty = true;
            } else {
                closeMenu();
            }
            click.move();
            return;
        }
        if (moving) {
            if (dx != 0) shiftShelf(dx);
            else endMove();
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        switch (zone) {
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(TOP_SETTINGS, topIndex + dx));
                    if (next == topIndex) return;
                    topIndex = next;
                    if (topIndex <= TAB_HOME) selectTab(topIndex);   // focusing a tab selects it
                } else if (dy > 0) {
                    zone = Z_ROW;
                } else {
                    return;
                }
                break;
            case Z_ROW:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(row.size() - 1, rowIndex + dx));
                    if (next == rowIndex) return;
                    focusRow(next);
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    if (targets.isEmpty()) return;
                    zone = Z_HUB;
                    targetIndex = firstTarget();
                }
                break;
            default: {
                if (dx != 0 && targetIndex < targets.size() && targets.get(targetIndex).slider) {
                    nudgeBrightness(dx);
                    return;
                }
                int next = nextTarget(targetIndex, dx, dy);
                if (next < 0) {
                    if (dy < 0) zone = Z_ROW;
                    else return;
                } else {
                    targetIndex = next;
                }
                break;
            }
        }
        retarget(false);
        click.move();
    }

    private void activate() {
        applyHub();
        if (menuOpen) {
            Runnable action = menuActions.get(menuIndex);
            closeMenu();
            action.run();
            return;
        }
        if (moving) {
            endMove();
            return;
        }
        if (sheetOpen) {
            click.open();
            if (!all.isEmpty()) launch(all.get(gridIndex).launch, focusedCell);
            return;
        }
        switch (zone) {
            case Z_TOP:
                if (topIndex <= TAB_HOME) {
                    selectTab(topIndex);
                    zone = Z_ROW;
                    retarget(false);
                } else if (topIndex == TOP_STATS) {
                    click.open();
                    host.openStats();
                } else {
                    click.open();
                    launch(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), topRects[TOP_SETTINGS]);
                }
                return;
            case Z_HUB: {
                if (targetIndex >= targets.size()) return;
                Target t = targets.get(targetIndex);
                if (t.action == null) return;
                click.open();
                t.action.run();
                return;
            }
            default: {
                if (row.isEmpty()) return;
                Tile t = row.get(rowIndex);
                tileRect(rowIndex, cell);
                switch (t.kind) {
                    case T_WELCOME:
                    case T_ROOM:
                        if (targets.isEmpty()) return;
                        zone = Z_HUB;
                        targetIndex = firstTarget();
                        retarget(false);
                        click.move();
                        return;
                    case T_ALL:
                        click.open();
                        openSheet();
                        return;
                    case T_DEVICE:
                        click.open();
                        devices.toggle(t.device);
                        return;
                    default:
                        click.open();
                        launch(t.item.launch, new RectF(cell));
                }
            }
        }
    }

    private void launch(Intent intent, RectF r) {
        if (intent == null) {
            showToast("Couldn't open that.");
            return;
        }
        host.launch(intent, r);
    }

    // MARK: - Helpers

    /** `s` cut with … to fit `width` in `p`; cached, since labels are drawn on every frame of an animation. */
    private String fit(String s, Paint p, float width) {
        if (s == null) return "";
        String key = s + '\u0000' + System.identityHashCode(p) + '\u0000' + p.getTextSize() + '\u0000' + Math.round(width);
        String hit = fitted.get(key);
        if (hit != null) return hit;
        if (fitted.size() > 400) fitted.clear();
        String out = TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
        fitted.put(key, out);
        return out;
    }

    private static String abbr(Football.Side s) {
        return TextUtils.isEmpty(s.abbr) ? s.name : s.abbr;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String day(long t) {
        LocalDate d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate(), today = LocalDate.now();
        if (d.equals(today)) return "Today";
        if (d.equals(today.plusDays(1))) return "Tomorrow";
        if (d.equals(today.minusDays(1))) return "Yesterday";
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date(t));
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
