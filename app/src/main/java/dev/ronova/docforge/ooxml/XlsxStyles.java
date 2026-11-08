package dev.ronova.docforge.ooxml;

import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Стили ячеек Excel (xl/styles.xml): шрифты, заливка, границы, выравнивание, числовые форматы. */
final class XlsxStyles {
    static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    /** Итог по одному стилю ячейки (индексу в cellXfs). */
    static final class CellStyle {
        String css = "";
        String numFmtCode = "General";
        String horizontal;   // left, center, right, justify, fill или null (общий)
        boolean wrap;
    }

    private static final class Font {
        boolean bold, italic, underline, strike;
        String color;
        double size = 11;
        String name;
    }

    private static final class Xf {
        int numFmtId, fontId, fillId, borderId;
        String horizontal, vertical;
        boolean wrap;
        int indent;
    }

    private final Map<Integer, String> customFormats = new HashMap<>();
    private final List<Font> fonts = new ArrayList<>();
    private final List<String> fills = new ArrayList<>();
    private final List<String> borders = new ArrayList<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final String[] theme = new String[12];
    private final Map<Integer, CellStyle> cache = new HashMap<>();
    private double baseFontSize = 11;

    XlsxStyles(Document styles, Document themeDoc) {
        loadTheme(themeDoc);
        if (styles == null) {
            return;
        }
        for (Element nf : Xml.descendants(styles, S, "numFmt")) {
            try {
                customFormats.put(Integer.parseInt(nf.getAttribute("numFmtId")), nf.getAttribute("formatCode"));
            } catch (NumberFormatException ignored) {
                // пропускаем
            }
        }
        Element fontsEl = first(styles, "fonts");
        if (fontsEl != null) {
            for (Element f : Xml.children(fontsEl, S, "font")) {
                Font font = new Font();
                font.bold = isOn(Xml.child(f, S, "b"));
                font.italic = isOn(Xml.child(f, S, "i"));
                Element u = Xml.child(f, S, "u");
                font.underline = u != null && !"none".equals(u.getAttribute("val"));
                font.strike = isOn(Xml.child(f, S, "strike"));
                Element sz = Xml.child(f, S, "sz");
                if (sz != null) {
                    try {
                        font.size = Double.parseDouble(sz.getAttribute("val"));
                    } catch (NumberFormatException ignored) {
                        // оставляем 11
                    }
                }
                Element name = Xml.child(f, S, "name");
                if (name != null) font.name = name.getAttribute("val");
                font.color = color(Xml.child(f, S, "color"));
                fonts.add(font);
            }
        }
        if (!fonts.isEmpty()) {
            baseFontSize = fonts.get(0).size;
        }
        Element fillsEl = first(styles, "fills");
        if (fillsEl != null) {
            for (Element f : Xml.children(fillsEl, S, "fill")) {
                String bg = null;
                Element pf = Xml.child(f, S, "patternFill");
                if (pf != null && "solid".equals(pf.getAttribute("patternType"))) {
                    bg = color(Xml.child(pf, S, "fgColor"));
                }
                fills.add(bg);
            }
        }
        Element bordersEl = first(styles, "borders");
        if (bordersEl != null) {
            for (Element b : Xml.children(bordersEl, S, "border")) {
                StringBuilder css = new StringBuilder();
                String[][] sides = {{"left", "left"}, {"right", "right"}, {"top", "top"}, {"bottom", "bottom"}};
                for (String[] side : sides) {
                    Element e = Xml.child(b, S, side[0]);
                    if (e == null) continue;
                    String style = e.getAttribute("style");
                    if (style == null || style.isEmpty()) continue;
                    String c = color(Xml.child(e, S, "color"));
                    int w = "thin".equals(style) || "hair".equals(style) ? 1 : 2;
                    css.append("border-").append(side[1]).append(':').append(w).append("px ")
                            .append(style.startsWith("dash") || "mediumDashed".equals(style) ? "dashed"
                                    : style.startsWith("dot") ? "dotted" : "solid")
                            .append(" #").append(c == null ? "000000" : c).append(';');
                }
                borders.add(css.toString());
            }
        }
        Element xfsEl = first(styles, "cellXfs");
        if (xfsEl != null) {
            for (Element x : Xml.children(xfsEl, S, "xf")) {
                Xf xf = new Xf();
                xf.numFmtId = intAttr(x, "numFmtId");
                xf.fontId = intAttr(x, "fontId");
                xf.fillId = intAttr(x, "fillId");
                xf.borderId = intAttr(x, "borderId");
                Element al = Xml.child(x, S, "alignment");
                if (al != null) {
                    String h = al.getAttribute("horizontal");
                    xf.horizontal = h == null || h.isEmpty() || "general".equals(h) ? null : h;
                    String v = al.getAttribute("vertical");
                    xf.vertical = v == null || v.isEmpty() ? null : v;
                    xf.wrap = "1".equals(al.getAttribute("wrapText")) || "true".equals(al.getAttribute("wrapText"));
                    xf.indent = intAttr(al, "indent");
                }
                xfs.add(xf);
            }
        }
    }

    private static Element first(Document doc, String local) {
        List<Element> l = Xml.descendants(doc, S, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean isOn(Element e) {
        if (e == null) return false;
        String v = e.getAttribute("val");
        return v == null || v.isEmpty() || "1".equals(v) || "true".equals(v);
    }

    private static int intAttr(Element e, String name) {
        try {
            return Integer.parseInt(e.getAttribute(name));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Цвета темы в порядке индексов Excel: lt1, dk1, lt2, dk2, accent1..6, hlink, folHlink. */
    private void loadTheme(Document doc) {
        String[] names = {"lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};
        String[] fallback = {"FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47", "0563C1", "954F72"};
        for (int i = 0; i < 12; i++) {
            theme[i] = fallback[i];
        }
        if (doc == null) {
            return;
        }
        List<Element> schemes = Xml.descendants(doc, A, "clrScheme");
        if (schemes.isEmpty()) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            Element c = Xml.child(schemes.get(0), A, names[i]);
            if (c == null) continue;
            for (Element v : Xml.children(c)) {
                String hex = v.hasAttribute("lastClr") ? v.getAttribute("lastClr") : v.getAttribute("val");
                if (Html.isHexColor(hex)) {
                    theme[i] = hex.toUpperCase(java.util.Locale.ROOT);
                }
            }
        }
    }

    private static final String[] INDEXED = {"000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "800000", "008000", "000080",
            "808000", "800080", "008080", "C0C0C0", "808080"};

    /** Цвет элемента (rgb / theme + tint / indexed) в виде RRGGBB либо null. */
    private String color(Element c) {
        if (c == null) return null;
        String rgb = c.getAttribute("rgb");
        if (rgb != null && rgb.length() == 8) {
            String hex = rgb.substring(2);
            return Html.isHexColor(hex) ? hex.toUpperCase(java.util.Locale.ROOT) : null;
        }
        if (c.hasAttribute("theme")) {
            int idx = intAttr(c, "theme");
            if (idx >= 0 && idx < theme.length) {
                double tint = 0;
                try {
                    tint = Double.parseDouble(c.getAttribute("tint"));
                } catch (NumberFormatException ignored) {
                    // без оттенка
                }
                return tint == 0 ? theme[idx] : applyTint(theme[idx], tint);
            }
        }
        if (c.hasAttribute("indexed")) {
            int idx = intAttr(c, "indexed");
            return idx >= 0 && idx < INDEXED.length ? INDEXED[idx] : null;
        }
        return null;
    }

    /** Оттенок Excel: меняется яркость цвета в модели HLS. */
    private static String applyTint(String hex, double tint) {
        double r = Integer.parseInt(hex.substring(0, 2), 16) / 255.0;
        double g = Integer.parseInt(hex.substring(2, 4), 16) / 255.0;
        double b = Integer.parseInt(hex.substring(4, 6), 16) / 255.0;
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
        l = tint < 0 ? l * (1 + tint) : l * (1 - tint) + tint;
        l = Math.max(0, Math.min(1, l));
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        int rr = (int) Math.round(hue(p, q, h + 1.0 / 3) * 255);
        int gg = (int) Math.round(hue(p, q, h) * 255);
        int bb = (int) Math.round(hue(p, q, h - 1.0 / 3) * 255);
        return String.format(java.util.Locale.ROOT, "%02X%02X%02X", rr, gg, bb);
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 0.5) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    CellStyle style(int xfIndex) {
        CellStyle cached = cache.get(xfIndex);
        if (cached != null) {
            return cached;
        }
        CellStyle cs = new CellStyle();
        if (xfIndex >= 0 && xfIndex < xfs.size()) {
            Xf xf = xfs.get(xfIndex);
            StringBuilder css = new StringBuilder();
            if (xf.fontId >= 0 && xf.fontId < fonts.size()) {
                Font f = fonts.get(xf.fontId);
                if (f.bold) css.append("font-weight:bold;");
                if (f.italic) css.append("font-style:italic;");
                if (f.underline || f.strike) {
                    css.append("text-decoration:").append(f.underline && f.strike ? "underline line-through"
                            : f.underline ? "underline" : "line-through").append(';');
                }
                if (f.color != null && !"000000".equals(f.color)) css.append("color:#").append(f.color).append(';');
                if (f.size > 0 && Math.abs(f.size - baseFontSize) > 0.1) {
                    css.append("font-size:").append(Html.num(f.size / baseFontSize)).append("em;");
                }
                String family = f.name == null || f.name.equals(fonts.get(0).name) ? null : Html.fontFamily(f.name);
                if (family != null) css.append("font-family:").append(family).append(';');
            }
            if (xf.fillId >= 0 && xf.fillId < fills.size() && fills.get(xf.fillId) != null) {
                css.append("background:#").append(fills.get(xf.fillId)).append(';');
            }
            if (xf.borderId >= 0 && xf.borderId < borders.size()) {
                css.append(borders.get(xf.borderId));
            }
            if (xf.horizontal != null) {
                String h = xf.horizontal;
                String align = "centerContinuous".equals(h) || "center".equals(h) ? "center"
                        : "right".equals(h) ? "right" : "justify".equals(h) ? "justify" : "left";
                css.append("text-align:").append(align).append(';');
                cs.horizontal = align;
            }
            if (xf.vertical != null) {
                css.append("vertical-align:").append("center".equals(xf.vertical) ? "middle"
                        : "top".equals(xf.vertical) ? "top" : "bottom").append(';');
            }
            if (xf.wrap) {
                css.append("white-space:pre-wrap;overflow-wrap:anywhere;");
                cs.wrap = true;
            }
            if (xf.indent > 0) css.append("padding-left:").append(4 + xf.indent * 9).append("px;");
            cs.css = css.toString();
            cs.numFmtCode = customFormats.containsKey(xf.numFmtId) ? customFormats.get(xf.numFmtId) : NumFmt.builtin(xf.numFmtId);
        }
        cache.put(xfIndex, cs);
        return cs;
    }
}
