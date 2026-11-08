package dev.ronova.docforge.ooxml;

import java.util.Locale;

/** Цветовые преобразования для модификаторов DrawingML (lumMod, lumOff, tint, shade). */
final class ColorUtil {
    private ColorUtil() {
    }

    static int[] parse(String hex) {
        return new int[]{Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)};
    }

    static String hex(int[] rgb) {
        return String.format(Locale.ROOT, "%02X%02X%02X", clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]));
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** lumMod / lumOff в долях (1.0 = 100%). */
    static int[] luminance(int[] rgb, double mod, double off) {
        double r = rgb[0] / 255.0;
        double g = rgb[1] / 255.0;
        double b = rgb[2] / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double h = 0;
        double s = 0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        l = Math.max(0, Math.min(1, l * mod + off));
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return new int[]{(int) Math.round(channel(p, q, h + 1.0 / 3) * 255), (int) Math.round(channel(p, q, h) * 255),
                (int) Math.round(channel(p, q, h - 1.0 / 3) * 255)};
    }

    private static double channel(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 0.5) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    /** tint: доля исходного цвета, остальное — белый. */
    static int[] tint(int[] rgb, double amount) {
        return new int[]{(int) Math.round(rgb[0] * amount + 255 * (1 - amount)), (int) Math.round(rgb[1] * amount + 255 * (1 - amount)),
                (int) Math.round(rgb[2] * amount + 255 * (1 - amount))};
    }

    /** shade: доля исходного цвета, остальное — чёрный. */
    static int[] shade(int[] rgb, double amount) {
        return new int[]{(int) Math.round(rgb[0] * amount), (int) Math.round(rgb[1] * amount), (int) Math.round(rgb[2] * amount)};
    }
}
