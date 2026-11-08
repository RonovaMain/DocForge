package dev.ronova.docforge.odf;

import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Стили ODF (content.xml и styles.xml): сворачиваются в CSS с учётом наследования. */
final class OdfStyles {

    private static final class Def {
        String family;
        String name;
        String parent;
        final Map<String, String> text = new LinkedHashMap<>();
        final Map<String, String> para = new LinkedHashMap<>();
        final Map<String, String> cell = new LinkedHashMap<>();
        /** «Сырые» свойства фигур и страниц (draw:fill-color и т. п.) — CSS из них строит вызывающий код. */
        final Map<String, String> graphic = new LinkedHashMap<>();
        Double columnWidth;
        Double rowHeight;
        boolean hidden;
        String listKind;
    }

    private final Map<String, Def> defs = new HashMap<>();
    private final Map<String, Double[]> pageSizes = new HashMap<>();
    private final Map<String, String> masterLayouts = new HashMap<>();
    private final Map<String, String> masterBackground = new HashMap<>();

    OdfStyles(Document content, Document styles) {
        load(styles);
        load(content);
    }

    private static String key(String family, String name) {
        return family + ":" + (name == null ? "" : name);
    }

    private void load(Document doc) {
        if (doc == null) return;
        for (Element e : Xml.descendants(doc, Odf.STYLE, "style")) {
            Def d = new Def();
            d.family = Odf.a(e, Odf.STYLE, "family");
            d.name = Odf.a(e, Odf.STYLE, "name");
            d.parent = Odf.a(e, Odf.STYLE, "parent-style-name");
            if (d.family == null || d.name == null) continue;
            fill(d, e);
            defs.put(key(d.family, d.name), d);
            String master = Odf.a(e, Odf.STYLE, "master-page-name");
            if (master != null && !master.isEmpty()) {
                masterBackground.put(key(d.family, d.name), master);
            }
        }
        for (Element e : Xml.descendants(doc, Odf.STYLE, "default-style")) {
            Def d = new Def();
            d.family = Odf.a(e, Odf.STYLE, "family");
            d.name = "";
            if (d.family == null) continue;
            fill(d, e);
            defs.put(key(d.family, ""), d);
        }
        for (Element e : Xml.descendants(doc, Odf.TEXT, "list-style")) {
            Def d = new Def();
            d.family = "list";
            d.name = Odf.a(e, Odf.STYLE, "name");
            List<Element> levels = Xml.children(e);
            d.listKind = !levels.isEmpty() && levels.get(0).getLocalName().contains("number") ? "ol" : "ul";
            if (d.name != null) defs.put(key("list", d.name), d);
        }
        for (Element e : Xml.descendants(doc, Odf.STYLE, "page-layout")) {
            String name = Odf.a(e, Odf.STYLE, "name");
            Element props = Xml.child(e, Odf.STYLE, "page-layout-properties");
            if (name == null || props == null) continue;
            Double w = Odf.toPt(Odf.a(props, Odf.FO, "page-width"));
            Double h = Odf.toPt(Odf.a(props, Odf.FO, "page-height"));
            if (w != null && h != null) pageSizes.put(name, new Double[]{w, h});
        }
        for (Element e : Xml.descendants(doc, Odf.STYLE, "master-page")) {
            String name = Odf.a(e, Odf.STYLE, "name");
            String layout = Odf.a(e, Odf.STYLE, "page-layout-name");
            if (name != null && layout != null) masterLayouts.put(name, layout);
            String bg = Odf.a(e, Odf.DRAW, "style-name");
            if (name != null && bg != null) masterBackground.put("master:" + name, bg);
        }
    }

    // ------------------------------------------------------------------ разбор свойств

    private static void fill(Def d, Element style) {
        for (Element p : Xml.children(style)) {
            String n = p.getLocalName();
            if ("text-properties".equals(n)) {
                parseText(p, d.text);
            } else if ("paragraph-properties".equals(n)) {
                parsePara(p, d.para);
            } else if ("table-cell-properties".equals(n)) {
                parseCell(p, d.cell);
            } else if ("graphic-properties".equals(n) || "drawing-page-properties".equals(n)) {
                parseGraphic(p, d.graphic);
            } else if ("table-column-properties".equals(n)) {
                d.columnWidth = Odf.toPt(Odf.a(p, Odf.STYLE, "column-width"));
            } else if ("table-row-properties".equals(n)) {
                d.rowHeight = Odf.toPt(Odf.a(p, Odf.STYLE, "row-height"));
            } else if ("table-properties".equals(n)) {
                d.hidden = "false".equals(Odf.a(p, Odf.TABLE, "display"));
            }
        }
    }

    private static String color(String v) {
        if (v == null || !v.startsWith("#")) return null;
        String hex = v.substring(1);
        return Html.isHexColor(hex) ? "#" + hex : null;
    }

    private static void parseText(Element tp, Map<String, String> out) {
        String weight = Odf.a(tp, Odf.FO, "font-weight");
        if (weight != null) {
            boolean bold = "bold".equals(weight) || (weight.matches("\\d+") && Integer.parseInt(weight) >= 600);
            out.put("font-weight", bold ? "bold" : "normal");
        }
        String fs = Odf.a(tp, Odf.FO, "font-style");
        if (fs != null) out.put("font-style", "italic".equals(fs) || "oblique".equals(fs) ? "italic" : "normal");
        String ul = Odf.a(tp, Odf.STYLE, "text-underline-style");
        String st = Odf.a(tp, Odf.STYLE, "text-line-through-style");
        if (ul != null || st != null) {
            boolean u = ul != null && !"none".equals(ul);
            boolean s = st != null && !"none".equals(st);
            out.put("text-decoration", u && s ? "underline line-through" : u ? "underline" : s ? "line-through" : "none");
        }
        String c = color(Odf.a(tp, Odf.FO, "color"));
        if (c != null) out.put("color", c);
        String bg = color(Odf.a(tp, Odf.FO, "background-color"));
        if (bg != null) out.put("background", bg);
        String size = Odf.a(tp, Odf.FO, "font-size");
        if (size != null) {
            Double pt = Odf.toPt(size);
            if (pt != null) out.put("font-size", Html.num(pt) + "pt");
            else if (size.endsWith("%")) out.put("font-size", size);
        }
        String font = Odf.a(tp, Odf.STYLE, "font-name");
        if (font == null) font = Odf.a(tp, Odf.FO, "font-family");
        String family = Html.fontFamily(font == null ? null : font.replace("'", ""));
        if (family != null) out.put("font-family", family);
        String pos = Odf.a(tp, Odf.STYLE, "text-position");
        if (pos != null) {
            if (pos.startsWith("super")) out.put("vertical-align", "super;font-size:smaller");
            else if (pos.startsWith("sub")) out.put("vertical-align", "sub;font-size:smaller");
        }
        String tt = Odf.a(tp, Odf.FO, "text-transform");
        if ("uppercase".equals(tt)) out.put("text-transform", "uppercase");
        if ("small-caps".equals(Odf.a(tp, Odf.FO, "font-variant"))) out.put("font-variant", "small-caps");
    }

    private static String border(String v) {
        if (v == null || "none".equals(v.trim())) return null;
        String[] parts = v.trim().split("\\s+");
        Double w = Odf.toPt(parts[0]);
        String style = parts.length > 1 ? parts[1] : "solid";
        String col = null;
        for (String p : parts) {
            if (color(p) != null) col = color(p);
        }
        double px = Math.max(1, (w == null ? 0.75 : w) * 96 / 72);
        return Html.num(Math.round(px)) + "px " + ("dashed".equals(style) ? "dashed" : "dotted".equals(style) ? "dotted" : "solid")
                + " " + (col == null ? "#000" : col);
    }

    private static void borders(Element e, Map<String, String> out) {
        String all = border(Odf.a(e, Odf.FO, "border"));
        String[] sides = {"left", "right", "top", "bottom"};
        for (String side : sides) {
            String raw = Odf.a(e, Odf.FO, "border-" + side);
            String b = raw != null ? border(raw) : all;
            if (raw != null || all != null) {
                out.put("border-" + side, b == null ? "none" : b);
            }
        }
    }

    private static void parsePara(Element pp, Map<String, String> out) {
        String align = Odf.a(pp, Odf.FO, "text-align");
        if (align != null) {
            out.put("text-align", "center".equals(align) ? "center" : "end".equals(align) || "right".equals(align) ? "right"
                    : "justify".equals(align) ? "justify" : "left");
        }
        String[][] margins = {{"margin-left", "margin-left"}, {"margin-right", "margin-right"}, {"margin-top", "margin-top"},
                {"margin-bottom", "margin-bottom"}, {"text-indent", "text-indent"}};
        for (String[] m : margins) {
            Double pt = Odf.toPt(Odf.a(pp, Odf.FO, m[0]));
            if (pt != null) out.put(m[1], Html.num(pt) + "pt");
        }
        String lh = Odf.a(pp, Odf.FO, "line-height");
        if (lh != null && lh.endsWith("%")) {
            try {
                out.put("line-height", Html.num(Double.parseDouble(lh.substring(0, lh.length() - 1)) / 100.0));
            } catch (NumberFormatException ignored) {
                // без межстрочного интервала
            }
        }
        String bg = color(Odf.a(pp, Odf.FO, "background-color"));
        if (bg != null) out.put("background", bg);
        borders(pp, out);
    }

    private static void parseCell(Element cp, Map<String, String> out) {
        String bg = color(Odf.a(cp, Odf.FO, "background-color"));
        if (bg != null) out.put("background", bg);
        borders(cp, out);
        String va = Odf.a(cp, Odf.STYLE, "vertical-align");
        if (va != null) out.put("vertical-align", "middle".equals(va) ? "middle" : "top".equals(va) ? "top" : "bottom");
        if ("wrap".equals(Odf.a(cp, Odf.FO, "wrap-option"))) out.put("white-space", "pre-wrap;overflow-wrap:anywhere");
        Double pad = Odf.toPt(Odf.a(cp, Odf.FO, "padding"));
        if (pad != null) out.put("padding", Html.num(pad) + "pt");
    }

    private static void parseGraphic(Element gp, Map<String, String> out) {
        String[][] map = {{Odf.DRAW, "fill"}, {Odf.DRAW, "fill-color"}, {Odf.DRAW, "stroke"}, {Odf.SVG, "stroke-color"},
                {Odf.SVG, "stroke-width"}, {Odf.DRAW, "textarea-vertical-align"}, {Odf.FO, "padding-left"},
                {Odf.FO, "padding-right"}, {Odf.FO, "padding-top"}, {Odf.FO, "padding-bottom"}, {Odf.DRAW, "textarea-horizontal-align"},
                {Odf.FO, "wrap-option"}};
        for (String[] m : map) {
            String v = Odf.a(gp, m[0], m[1]);
            if (v != null) out.put(m[1], v);
        }
    }

    // ------------------------------------------------------------------ запросы

    private Def def(String family, String name) {
        return defs.get(key(family, name));
    }

    /** Цепочка наследования от базового стиля к производному (с «умолчательным» стилем семейства впереди). */
    private List<Def> chain(String family, String name) {
        List<Def> list = new ArrayList<>();
        String current = name;
        for (int i = 0; i < 12 && current != null && !current.isEmpty(); i++) {
            Def d = def(family, current);
            if (d == null) break;
            list.add(0, d);
            current = d.parent;
        }
        Def base = def(family, "");
        if (base != null) list.add(0, base);
        return list;
    }

    private static String css(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    String textCss(String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Def d : chain("text", name)) m.putAll(d.text);
        return css(m);
    }

    /** Абзац: свойства абзаца и текста по цепочке стилей. */
    String paraCss(String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Def d : chain("paragraph", name)) {
            m.putAll(d.text);
            m.putAll(d.para);
        }
        return css(m);
    }

    /** Базовое оформление документа: свойства стиля абзаца по умолчанию и стиля Standard. */
    String baseTextCss() {
        Map<String, String> m = new LinkedHashMap<>();
        Def base = def("paragraph", "");
        if (base != null) m.putAll(base.text);
        Def standard = def("paragraph", "Standard");
        if (standard != null) m.putAll(standard.text);
        m.remove("color");
        return css(m);
    }

    /** Ячейка таблицы: свойства ячейки плюс текст и абзац из того же стиля. */
    String cellCss(String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Def d : chain("table-cell", name)) {
            m.putAll(d.text);
            m.putAll(d.para);
            m.putAll(d.cell);
            // в таблицах Impress заливка ячейки записана как свойства фигуры (loext:graphic-properties)
            String fill = d.graphic.get("fill-color");
            if ("solid".equals(d.graphic.get("fill")) && fill != null && fill.startsWith("#")) m.put("background", fill);
        }
        return css(m);
    }

    /** Есть ли у стиля ячейки явное выравнивание текста. */
    boolean cellHasAlign(String name) {
        for (Def d : chain("table-cell", name)) {
            if (d.para.containsKey("text-align")) return true;
        }
        return false;
    }

    Double columnWidth(String name) {
        Double w = null;
        for (Def d : chain("table-column", name)) if (d.columnWidth != null) w = d.columnWidth;
        return w;
    }

    Double rowHeight(String name) {
        Double h = null;
        for (Def d : chain("table-row", name)) if (d.rowHeight != null) h = d.rowHeight;
        return h;
    }

    boolean tableHidden(String name) {
        for (Def d : chain("table", name)) if (d.hidden) return true;
        return false;
    }

    /** Тег списка по имени стиля: ol для нумерованных, иначе ul. */
    String listTag(String styleName) {
        Def d = def("list", styleName);
        return d != null && "ol".equals(d.listKind) ? "ol" : "ul";
    }

    /** Свойства фигуры (graphic/presentation/drawing-page) по цепочке — «сырые» ключи ODF. */
    Map<String, String> graphic(String family, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Def d : chain(family, name)) m.putAll(d.graphic);
        return m;
    }

    /** Текстовые свойства стиля фигуры в виде CSS. */
    String shapeTextCss(String family, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Def d : chain(family, name)) {
            m.putAll(d.text);
        }
        return css(m);
    }

    /** Размер страницы (пт) по имени мастер-страницы; null — не задан. */
    Double[] pageSize(String masterName) {
        String layout = masterLayouts.get(masterName);
        return layout == null ? null : pageSizes.get(layout);
    }

    /** Имя стиля фона мастер-страницы. */
    String masterDrawStyle(String masterName) {
        return masterBackground.get("master:" + masterName);
    }

}
