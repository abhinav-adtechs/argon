package com.argon.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manchester City's look for Football Central and the screensaver: the club's sky blue and navy, the shield's diagonal
 * stripes, the wallpaper (drawn here once around the crest and kept in the files dir), the result pills and the lineups
 * on a pitch. The crest itself is never in the repo: City downloads it from ESPN on the TV.
 */
final class CityArt {
    static final int SKY = 0xFF6CABDD, SKY_LIGHT = 0xFF9CCAEB, NAVY = 0xFF1C2C5B, DEEP = 0xFF0A1631, MIDNIGHT = 0xFF050B1A,
            GOLD = 0xFFF2C14E, WIN = 0xFF3FCF7C, DRAW = 0xFF9AA4B8, LOSS = 0xFFFF5A4E, LIVE = 0xFFFF453A,
            CARD = 0xD90C1830, CARD_LINE = 0x336CABDD, INK = 0xFF0A1631;
    private static final int W = 1920, H = 1080, VERSION = 1;
    private static final String TAG = "CityArt";

    private CityArt() {}

    static int outcome(char o) {
        return o == 'W' ? WIN : o == 'L' ? LOSS : DRAW;
    }

    /** A W / D / L square with rounded corners, the letter in it. */
    static void pill(Canvas c, char o, float cx, float cy, float size, Paint fill, Paint letter) {
        fill.setShader(null);
        fill.setColor(outcome(o));
        float r = size * 0.24f;
        c.drawRoundRect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2, r, r, fill);
        int color = letter.getColor();
        Paint.Align align = letter.getTextAlign();
        letter.setColor(o == 'D' ? INK : 0xFFFFFFFF);
        letter.setTextAlign(Paint.Align.CENTER);
        c.drawText(String.valueOf(o), cx, cy + Ui.halfCap(letter), letter);
        letter.setColor(color);
        letter.setTextAlign(align);
    }

    /** The shield's diagonal sky stripes over `r` (clipped by the caller), `alpha` 0…1. */
    static void stripes(Canvas c, RectF r, float width, float alpha, Paint fill) {
        fill.setShader(null);
        fill.setColor(Ui.alpha(SKY, alpha));
        Path p = new Path();
        float h = r.height();
        for (float x = r.left - h; x < r.right + h; x += width * 2) {
            p.moveTo(x, r.bottom);
            p.lineTo(x + width, r.bottom);
            p.lineTo(x + width + h, r.top);
            p.lineTo(x + h, r.top);
            p.close();
        }
        c.drawPath(p, fill);
    }

    /** A team's colour on a dark pitch: its own unless it's too close to the club's sky blue or too dark to see. */
    static int kit(City.Side s, boolean club) {
        if (club) return SKY;
        int c = s.color;
        if (near(c, SKY) || luminance(c) < 0.08f) c = s.altColor;
        if (near(c, SKY) || luminance(c) < 0.08f) c = 0xFFF2F2F2;
        return c;
    }

    static float luminance(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }

    private static boolean near(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db < 60 * 60;
    }

    // MARK: - The wallpaper

    /**
     * Midnight navy with a sky glow from the top right, the shield's stripes fading out across it, two light streaks,
     * and the crest large and faint behind the top right (where the row leaves room), the top darkened for the tabs.
     * Null until the crest has been downloaded; kept as files/city-wall-v1.png.
     */
    static Bitmap wallpaper(Context c, Bitmap crest) {
        File f = new File(c.getFilesDir(), "city-wall-v" + VERSION + ".png");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return opaque(b);
        }
        if (crest == null) return null;
        Bitmap b = draw(crest);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "wallpaper not saved: " + e.getMessage());
        }
        return opaque(b);
    }

    private static Bitmap opaque(Bitmap b) {
        b.setHasAlpha(false);
        return Backdrop.gpu(b);
    }

    private static Bitmap draw(Bitmap crest) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, W, H, new int[] {0xFF0D1C40, 0xFF081330, MIDNIGHT}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(1520, 250, 1250, new int[] {0xCC3B7DB8, 0x5C1F4C86, 0x14102A55, 0x00000000},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);

        // The shield's stripes, strongest under the crest, fading toward the bottom left.
        Bitmap band = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(band);
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripes(bc, new RectF(0, 0, W, H), 64, 0.11f, s);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setShader(new RadialGradient(1500, 330, 1150, new int[] {0xFF000000, 0x99000000, 0x00000000},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        bc.drawRect(0, 0, W, H, mask);
        c.drawBitmap(band, 0, 0, null);
        band.recycle();

        // Light streaks in sky blue, like the Etihad's lights at night.
        Shader streak = new LinearGradient(0, H, W, 0, new int[] {0x006CABDD, 0xFF9CCAEB, 0x00FFFFFF},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        Path a = new Path();
        a.moveTo(-100, 980);
        a.cubicTo(420, 820, 900, 900, 1300, 620);
        a.cubicTo(1600, 410, 1820, 300, 2050, 180);
        Path d = new Path();
        d.moveTo(-100, 1060);
        d.cubicTo(520, 900, 1050, 990, 1450, 720);
        d.cubicTo(1720, 540, 1900, 470, 2050, 420);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(16);
        glow.setShader(streak);
        glow.setAlpha(90);
        glow.setMaskFilter(new BlurMaskFilter(12, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(a, glow);
        c.drawPath(d, glow);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f);
        line.setShader(streak);
        line.setAlpha(200);
        c.drawPath(a, line);
        c.drawPath(d, line);

        // The crest, large and faint, with the rings of its border echoed around it.
        float cx = 1560, cy = 300, size = 820;
        Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        img.setAlpha(30);
        c.drawBitmap(crest, null, new RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), img);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2);
        for (int i = 0; i < 3; i++) {
            ring.setColor(Ui.alpha(SKY_LIGHT, 0.12f - i * 0.035f));
            c.drawCircle(cx, cy, size / 2 + 40 + i * 70, ring);
        }

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(SKY_LIGHT);
        float[][] dots = {{980, 610, 2, .5f}, {1120, 540, 1.5f, .45f}, {1380, 760, 2.5f, .4f}, {1700, 820, 1.5f, .5f},
                {1840, 700, 2, .45f}, {860, 760, 1.5f, .35f}};
        for (float[] o : dots) {
            dot.setAlpha(Math.round(255 * o[3]));
            c.drawCircle(o[0], o[1], o[2], dot);
        }
        // Darker where the tabs, the row and the tables sit.
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, 0, 360, 0xA6030714, 0x00030714, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, 360, scrim);
        scrim.setShader(new LinearGradient(0, 440, 0, H, 0x00030714, 0x8C030714, Shader.TileMode.CLAMP));
        c.drawRect(0, 440, W, H, scrim);
        return b;
    }

    // MARK: - The lineups on a pitch

    /** Draws the pitch (navy, mown stripes, white lines) and both starting elevens: home attacking right. */
    static void pitch(Canvas c, RectF r, City.Match m, float k, Paint fill, Paint stroke, Paint number, TextPaint name, Paint label) {
        float rad = 16 * k;
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, 0xF20F2348, 0xF2091731, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);
        int n = 12;
        float sw = r.width() / n;
        fill.setColor(0x0AFFFFFF);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        for (int i = 0; i < n; i += 2) c.drawRect(r.left + i * sw, r.top, r.left + (i + 1) * sw, r.bottom, fill);
        c.restore();

        float in = 18 * k;
        RectF f = new RectF(r.left + in, r.top + in, r.right - in, r.bottom - in);
        stroke.setShader(null);
        stroke.setPathEffect(null);
        stroke.setColor(0x47FFFFFF);
        stroke.setStrokeWidth(2 * k);
        c.drawRect(f, stroke);
        c.drawLine(f.centerX(), f.top, f.centerX(), f.bottom, stroke);
        c.drawCircle(f.centerX(), f.centerY(), f.height() * 0.135f, stroke);
        fill.setColor(0x66FFFFFF);
        c.drawCircle(f.centerX(), f.centerY(), 3 * k, fill);
        float boxW = f.width() * 0.157f, boxH = f.height() * 0.59f, smallW = f.width() * 0.052f, smallH = f.height() * 0.27f;
        for (int side = 0; side < 2; side++) {
            float edge = side == 0 ? f.left : f.right, dir = side == 0 ? 1 : -1;
            c.drawRect(Math.min(edge, edge + dir * boxW), f.centerY() - boxH / 2, Math.max(edge, edge + dir * boxW), f.centerY() + boxH / 2, stroke);
            c.drawRect(Math.min(edge, edge + dir * smallW), f.centerY() - smallH / 2, Math.max(edge, edge + dir * smallW), f.centerY() + smallH / 2, stroke);
            float spot = edge + dir * f.width() * 0.105f;
            c.drawCircle(spot, f.centerY(), 2.5f * k, fill);
            RectF arc = new RectF(spot - f.height() * 0.135f, f.centerY() - f.height() * 0.135f, spot + f.height() * 0.135f, f.centerY() + f.height() * 0.135f);
            c.drawArc(arc, side == 0 ? -53 : 127, 106, false, stroke);
        }

        if (!m.hasLineups()) return;
        team(c, f, m.home, true, m.home.id.equals(m.club), k, fill, stroke, number, name);
        team(c, f, m.away, false, m.away.id.equals(m.club), k, fill, stroke, number, name);
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText(m.home.abbr + "  " + m.home.lineup.formation, f.left + 14 * k, f.top + 30 * k, label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText(m.away.lineup.formation + "  " + m.away.abbr, f.right - 14 * k, f.top + 30 * k, label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * One eleven: the keeper on the goal line, the rest in the formation's lines from the back, each line ordered
     * left to right by position (L…, centre, R…); a team's left is the top of the screen when it attacks right.
     */
    private static void team(Canvas c, RectF f, City.Side s, boolean home, boolean club, float k, Paint fill,
                             Paint stroke, Paint number, TextPaint name) {
        List<City.Player> xi = new ArrayList<>(s.lineup.starters);
        City.Player keeper = null;
        for (City.Player p : xi) if (p.place == 1 || p.position.equals("G") || p.position.equals("GK")) keeper = p;
        if (keeper != null) xi.remove(keeper);
        xi.sort((a, b) -> {
            int d = Float.compare(depth(a.position), depth(b.position));
            return d != 0 ? d : Integer.compare(a.place, b.place);
        });
        List<Integer> lines = new ArrayList<>();
        for (String part : s.lineup.formation.split("-")) {
            try {
                lines.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        int total = 0;
        for (int v : lines) total += v;
        if (total != xi.size()) {
            lines.clear();
            for (int left = xi.size(); left > 0; left -= 4) lines.add(Math.min(4, left));
        }
        int kit = kit(s, club);
        float half = f.width() / 2;
        if (keeper != null) player(c, keeper, home ? f.left + f.width() * 0.05f : f.right - f.width() * 0.05f, f.centerY(), kit, k, fill, stroke, number, name);
        int at = 0;
        for (int li = 0; li < lines.size(); li++) {
            int count = lines.get(li);
            List<City.Player> line = new ArrayList<>(xi.subList(at, Math.min(xi.size(), at + count)));
            at += count;
            line.sort((a, b) -> {
                int d = Float.compare(lateral(a.position), lateral(b.position));
                return d != 0 ? d : Integer.compare(a.place, b.place);
            });
            if (!home) Collections.reverse(line);
            float depth = lines.size() == 1 ? 0.55f : 0.26f + 0.58f * li / (lines.size() - 1);
            float px = home ? f.left + half * depth : f.right - half * depth;
            for (int i = 0; i < line.size(); i++) {
                float py = f.top + f.height() * (0.1f + 0.8f * (i + 0.5f) / line.size());
                player(c, line.get(i), px, py, kit, k, fill, stroke, number, name);
            }
        }
    }

    private static void player(Canvas c, City.Player p, float cx, float cy, int kit, float k, Paint fill, Paint stroke,
                               Paint number, TextPaint name) {
        float r = 21 * k;
        int a = p.subOut ? 150 : 255;
        fill.setShader(null);
        fill.setColor(0x59000000);
        c.drawCircle(cx, cy + 3 * k, r, fill);
        fill.setColor(kit);
        fill.setAlpha(a);
        c.drawCircle(cx, cy, r, fill);
        stroke.setColor(0x8CFFFFFF);
        stroke.setStrokeWidth(2 * k);
        c.drawCircle(cx, cy, r, stroke);
        number.setTextAlign(Paint.Align.CENTER);
        number.setColor(luminance(kit) > 0.55f ? INK : 0xFFFFFFFF);
        number.setAlpha(a);
        c.drawText(p.jersey, cx, cy + Ui.halfCap(number), number);
        name.setTextAlign(Paint.Align.CENTER);
        name.setAlpha(p.subOut ? 160 : 255);
        String n = TextUtils.ellipsize(p.name, name, 104 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(n, cx, cy + r + 20 * k, name);
        name.setAlpha(255);
        float bx = cx + r * 0.78f, by = cy - r * 0.78f;
        for (int g = 0; g < Math.min(3, p.goals); g++) {
            ball(c, bx + g * 11 * k, by, 7 * k, fill, stroke);
        }
        if (p.yellow || p.red) {
            fill.setColor(p.red ? LIVE : 0xFFFFD43B);
            float cw = 8 * k, ch = 11 * k, lx = cx - r * 0.95f - cw / 2, ty = cy - r * 0.95f - ch / 2;
            c.drawRoundRect(lx, ty, lx + cw, ty + ch, 1.5f * k, 1.5f * k, fill);
        }
        if (p.subOut) {
            fill.setColor(LIVE);
            Path arrow = new Path();
            float ax = cx + r * 0.8f, ay = cy + r * 0.62f, s = 6 * k;
            arrow.moveTo(ax - s, ay - s * 0.4f);
            arrow.lineTo(ax + s, ay - s * 0.4f);
            arrow.lineTo(ax, ay + s * 0.8f);
            arrow.close();
            c.drawPath(arrow, fill);
        }
    }

    /** A small white ball with a dark centre panel. */
    static void ball(Canvas c, float cx, float cy, float r, Paint fill, Paint stroke) {
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, r, fill);
        fill.setColor(INK);
        c.drawCircle(cx, cy, r * 0.38f, fill);
        stroke.setColor(0x99000000);
        stroke.setStrokeWidth(Math.max(1, r * 0.16f));
        c.drawCircle(cx, cy, r, stroke);
    }

    /** How far back a position plays: keeper 0, defenders 1, … strikers 4. */
    private static float depth(String pos) {
        String p = pos.toUpperCase(java.util.Locale.US);
        if (p.equals("G") || p.equals("GK")) return 0;
        if (p.contains("WB")) return 1.5f;
        if (p.contains("CD") || p.contains("CB") || p.equals("SW") || p.equals("D") || p.equals("LB") || p.equals("RB")) return 1;
        if (p.startsWith("DM")) return 2;
        if (p.startsWith("AM")) return 3;
        if (p.equals("LW") || p.equals("RW") || p.equals("W")) return 3.5f;
        if (p.equals("F") || p.equals("CF") || p.equals("ST") || p.equals("S") || p.startsWith("CF")) return 4;
        return 2.5f;
    }

    /** Left (−1), centre (0) or right (1) of the team, from the position's letters (LB, CD-L, AM-R, RW…). */
    private static float lateral(String pos) {
        String p = pos.toUpperCase(java.util.Locale.US);
        if (p.endsWith("-L") || p.startsWith("L")) return -1;
        if (p.endsWith("-R") || p.startsWith("R")) return 1;
        return 0;
    }
}
