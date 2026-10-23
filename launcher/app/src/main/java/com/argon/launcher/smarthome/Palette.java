package com.argon.launcher.smarthome;

// The colours a light can be set to from the TV and the remote, and how a bulb's own colour (CIE xy or a white's
// mirek) looks on screen. Whites are colour temperatures (with their xy for colour-only bulbs), the rest xy points
// inside Hue's gamut C.
public final class Palette {
    private Palette() {
    }

    public static final class Preset {
        public final String id, name;
        public final int rgb;
        final int mirek;
        final double x, y;

        Preset(String id, String name, int rgb, int mirek, double x, double y) {
            this.id = id;
            this.name = name;
            this.rgb = rgb;
            this.mirek = mirek;
            this.x = x;
            this.y = y;
        }

        public boolean white() {
            return mirek > 0;
        }
    }

    public static final Preset[] PRESETS = {
            new Preset("warm", "Warm", 0xFFFFC778, 454, 0.5267, 0.4133),
            new Preset("soft", "Soft white", 0xFFFFE6C4, 370, 0.4578, 0.41),
            new Preset("daylight", "Daylight", 0xFFEEF4FF, 182, 0.3227, 0.329),
            new Preset("red", "Red", 0xFFFF4A3D, 0, 0.675, 0.322),
            new Preset("orange", "Orange", 0xFFFF8A2A, 0, 0.575, 0.395),
            new Preset("violet", "Violet", 0xFF9D6BFF, 0, 0.245, 0.105),
            new Preset("blue", "Blue", 0xFF3D7BFF, 0, 0.158, 0.06),
            new Preset("green", "Green", 0xFF3DDC84, 0, 0.21, 0.64),
    };

    public static Preset preset(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    /** The preset closest to a bulb's colour, or null when it's something else (set from the Hue app). */
    static Preset nearest(boolean white, int mirek, double x, double y) {
        Preset best = null;
        double bestD = Double.MAX_VALUE;
        for (Preset p : PRESETS) {
            // A white set as a colour temperature matches whites; anything set as xy matches by xy (whites too).
            if (white && !p.white()) continue;
            double d = white ? Math.abs(p.mirek - mirek) / 300.0 : Math.hypot(p.x - x, p.y - y);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return bestD < (white ? 0.2 : 0.07) ? best : null;
    }

    /** How a white looks: Tanner Helland's fit of blackbody colour to kelvin. */
    static int mirekRgb(int mirek) {
        double t = 1e6 / Math.max(153, Math.min(500, mirek)) / 100;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661 : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return rgb(r / 255, g / 255, b / 255);
    }

    /** CIE xy at full brightness to sRGB (Philips' wide-gamut matrix), scaled so the brightest channel is full. */
    static int xyRgb(double x, double y) {
        if (y <= 0) return 0xFFFFFFFF;
        double X = x / y, Z = (1 - x - y) / y;
        double r = X * 1.656492 - 0.354851 - Z * 0.255038;
        double g = -X * 0.707196 + 1.655397 + Z * 0.036152;
        double b = X * 0.051713 - 0.121364 + Z * 1.011530;
        double max = Math.max(r, Math.max(g, b));
        return rgb(gamma(r / max), gamma(g / max), gamma(b / max));
    }

    private static double gamma(double v) {
        v = Math.max(0, v);
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }

    private static int rgb(double r, double g, double b) {
        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
