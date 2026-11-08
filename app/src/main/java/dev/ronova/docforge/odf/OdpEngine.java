package dev.ronova.docforge.odf;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.core.DocFormat;
import dev.ronova.docforge.core.OfficeEngine;
import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Просмотр и правка .odp (LibreOffice Impress). Слайд рисуется набором абсолютно позиционированных блоков;
 * 1 пункт файла = 4/3 пикселя, поэтому размеры шрифтов в pt совпадают с задуманными.
 */
public final class OdpEngine implements OfficeEngine {

    private static final double PX = 96.0 / 72.0;
    private final OdtEngine editing = new OdtEngine();

    @Override
    public DocFormat format() {
        return DocFormat.ODP;
    }

    private static final class Ctx {
        ZipPackage pkg;
        OdfStyles styles;
        OdfBlocks blocks;
        Document stylesDoc;
    }

    @Override
    public String renderHtml(byte[] source) {
        ZipPackage pkg = ZipPackage.read(source);
        Document content = pkg.xml("content.xml");
        if (content == null) {
            throw new DocException("В файле нет content.xml — это не документ OpenDocument");
        }
        List<Element> bodies = Xml.descendants(content, Odf.OFFICE, "presentation");
        if (bodies.isEmpty()) {
            throw new DocException("Это не презентация OpenDocument");
        }
        Ctx c = new Ctx();
        c.pkg = pkg;
        c.stylesDoc = pkg.xml("styles.xml");
        c.styles = new OdfStyles(content, c.stylesDoc);
        IdentityHashMap<Element, Integer> ids = new IdentityHashMap<>();
        List<Element> all = Odf.paragraphs(bodies.get(0));
        for (int i = 0; i < all.size(); i++) ids.put(all.get(i), i);
        c.blocks = new OdfBlocks(pkg, c.styles, ids);

        List<Element> pages = Xml.children(bodies.get(0), Odf.DRAW, "page");
        if (pages.isEmpty()) {
            throw new DocException("В презентации нет слайдов");
        }
        StringBuilder out = new StringBuilder();
        for (Element page : pages) {
            String master = Odf.a(page, Odf.DRAW, "master-page-name");
            Double[] size = master == null ? null : c.styles.pageSize(master);
            double w = (size == null ? 793.7 : size[0]) * PX;
            double h = (size == null ? 446.5 : size[1]) * PX;
            out.append("<div class=\"slidebox\" data-w=\"").append(Html.num(w)).append("\" data-h=\"").append(Html.num(h))
                    .append("\"><div class=\"slide\" style=\"width:").append(Html.num(w)).append("px;height:").append(Html.num(h))
                    .append("px;").append(background(c, page, master)).append("\">");
            Element masterEl = master == null ? null : masterPage(c.stylesDoc, master);
            if (masterEl != null) shapes(c, masterEl, true, out);
            shapes(c, page, false, out);
            out.append("</div></div>");
        }
        return Html.page(DocFormat.Kind.SLIDES, "", out.toString());
    }

    private static Element masterPage(Document stylesDoc, String name) {
        if (stylesDoc == null) return null;
        for (Element m : Xml.descendants(stylesDoc, Odf.STYLE, "master-page")) {
            if (name.equals(Odf.a(m, Odf.STYLE, "name"))) return m;
        }
        return null;
    }

    private String background(Ctx c, Element page, String master) {
        String own = Odf.a(page, Odf.DRAW, "style-name");
        String css = fillOf(c.styles.graphic("drawing-page", own));
        if (css == null && master != null) css = fillOf(c.styles.graphic("drawing-page", c.styles.masterDrawStyle(master)));
        return css == null ? "background:#fff;" : css;
    }

    private static String fillOf(Map<String, String> g) {
        String fill = g.get("fill");
        String color = g.get("fill-color");
        if ("solid".equals(fill) && color != null && color.startsWith("#") && Html.isHexColor(color.substring(1))) {
            return "background:" + color + ";";
        }
        return null;
    }

    // ------------------------------------------------------------------ фигуры

    private void shapes(Ctx c, Element container, boolean master, StringBuilder out) {
        for (Element e : Xml.children(container)) {
            if (!Odf.DRAW.equals(e.getNamespaceURI())) continue;
            String name = e.getLocalName();
            if ("g".equals(name)) {
                shapes(c, e, master, out);
            } else if ("frame".equals(name) || "custom-shape".equals(name) || "rect".equals(name)
                    || "ellipse".equals(name) || "circle".equals(name)) {
                // заготовки текста на мастер-странице («Щёлкните, чтобы…») не показываются
                if (master && e.hasAttributeNS(Odf.PRESENTATION, "class")) continue;
                if (!master && "true".equals(Odf.a(e, Odf.PRESENTATION, "placeholder")) && Odf.paragraphs(e).isEmpty()
                        && Xml.child(e, Odf.DRAW, "image") == null) continue;
                shape(c, e, out);
            } else if ("line".equals(name) || "connector".equals(name)) {
                line(c, e, out);
            }
        }
    }

    private static Double len(Element e, String local) {
        return Odf.toPt(Odf.a(e, Odf.SVG, local));
    }

    private void shape(Ctx c, Element e, StringBuilder out) {
        Double x = len(e, "x");
        Double y = len(e, "y");
        Double w = len(e, "width");
        Double h = len(e, "height");
        if (x == null || y == null || w == null || h == null) return;

        String drawStyle = Odf.a(e, Odf.DRAW, "style-name");
        String presStyle = Odf.a(e, Odf.PRESENTATION, "style-name");
        Map<String, String> g = c.styles.graphic("presentation", presStyle);
        g.putAll(c.styles.graphic("graphic", drawStyle));

        StringBuilder css = new StringBuilder();
        css.append("left:").append(Html.num(x * PX)).append("px;top:").append(Html.num(y * PX)).append("px;width:")
                .append(Html.num(w * PX)).append("px;height:").append(Html.num(h * PX)).append("px;");
        boolean hasTable = Xml.child(e, Odf.TABLE, "table") != null;
        String fill = hasTable ? null : fillOf(g);
        if (fill != null) css.append(fill);
        if (!hasTable && "solid".equals(g.get("stroke")) && g.get("stroke-color") != null && g.get("stroke-color").startsWith("#")) {
            Double sw = Odf.toPt(g.get("stroke-width"));
            css.append("border:").append(Html.num(Math.max(1, (sw == null ? 0.75 : sw) * PX))).append("px solid ")
                    .append(g.get("stroke-color")).append(';');
        }
        Element geometry = Xml.child(e, Odf.DRAW, "enhanced-geometry");
        String type = geometry == null ? null : Odf.a(geometry, Odf.DRAW, "type");
        String local = e.getLocalName();
        String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("ellipse") || "ellipse".equals(local) || "circle".equals(local)) css.append("border-radius:50%;");
        else if (t.contains("round")) css.append("border-radius:").append(Html.num(Math.min(w, h) * PX * 0.17)).append("px;");

        out.append("<div class=\"sh\" style=\"").append(Html.esc(css.toString())).append("\">");
        Element image = hasTable ? null : Xml.child(e, Odf.DRAW, "image");
        if (image != null) {
            String href = Odf.a(image, Odf.XLINK, "href");
            if (href != null && c.pkg.has(href) && Html.imageMime(href) != null) {
                out.append("<img src=\"").append(Html.mediaUrl(href)).append("\" alt=\"\" style=\"width:100%;height:100%\">");
            } else {
                out.append("<div style=\"width:100%;height:100%;background:#eee\"></div>");
            }
        } else if (Xml.child(e, Odf.DRAW, "object") != null || Xml.child(e, Odf.DRAW, "plugin") != null) {
            out.append("<div style=\"width:100%;height:100%;border:1px dashed #aaa;background:#f6f6f6;display:flex;"
                    + "align-items:center;justify-content:center;color:#777;font:14px sans-serif\">[Объект]</div>");
        } else {
            Element textBox = Xml.child(e, Odf.DRAW, "text-box");
            Element source = textBox != null ? textBox : e;
            StringBuilder inner = new StringBuilder();
            c.blocks.blocks(source, inner);
            if (inner.length() > 0) {
                String valign = g.get("textarea-vertical-align");
                StringBuilder tx = new StringBuilder("font-size:18pt;");
                tx.append(c.styles.shapeTextCss("presentation", presStyle)).append(c.styles.shapeTextCss("graphic", drawStyle));
                tx.append("justify-content:").append("middle".equals(valign) ? "center" : "bottom".equals(valign) ? "flex-end" : "flex-start").append(';');
                tx.append("padding:").append(pad(g, "top")).append(' ').append(pad(g, "right")).append(' ')
                        .append(pad(g, "bottom")).append(' ').append(pad(g, "left")).append(';');
                out.append("<div class=\"tx\" style=\"").append(Html.esc(tx.toString())).append("\">").append(inner).append("</div>");
            }
        }
        out.append("</div>");
    }

    private static String pad(Map<String, String> g, String side) {
        Double v = Odf.toPt(g.get("padding-" + side));
        return Html.num((v == null ? (side.equals("left") || side.equals("right") ? 7.2 : 3.6) : v) * PX) + "px";
    }

    private void line(Ctx c, Element e, StringBuilder out) {
        Double x1 = len(e, "x1");
        Double y1 = len(e, "y1");
        Double x2 = len(e, "x2");
        Double y2 = len(e, "y2");
        if (x1 == null || y1 == null || x2 == null || y2 == null) return;
        Map<String, String> g = c.styles.graphic("graphic", Odf.a(e, Odf.DRAW, "style-name"));
        String color = g.get("stroke-color") != null && g.get("stroke-color").startsWith("#") ? g.get("stroke-color") : "#000000";
        Double sw = Odf.toPt(g.get("stroke-width"));
        double left = Math.min(x1, x2) * PX;
        double top = Math.min(y1, y2) * PX;
        double w = Math.max(Math.abs(x2 - x1) * PX, 1);
        double h = Math.max(Math.abs(y2 - y1) * PX, 1);
        out.append("<div class=\"sh\" style=\"left:").append(Html.num(left)).append("px;top:").append(Html.num(top))
                .append("px;width:").append(Html.num(w)).append("px;height:").append(Html.num(h))
                .append("px\"><svg width=\"").append(Html.num(w)).append("\" height=\"").append(Html.num(h))
                .append("\" style=\"overflow:visible\"><line x1=\"").append(Html.num((x1 * PX) - left)).append("\" y1=\"")
                .append(Html.num((y1 * PX) - top)).append("\" x2=\"").append(Html.num((x2 * PX) - left)).append("\" y2=\"")
                .append(Html.num((y2 * PX) - top)).append("\" stroke=\"").append(Html.esc(color)).append("\" stroke-width=\"")
                .append(Html.num(Math.max(1, (sw == null ? 0.75 : sw) * PX))).append("\"/></svg></div>");
    }

    // ------------------------------------------------------------------ правка

    @Override
    public String textAt(byte[] source, String id) {
        return editing.textAt(source, id);
    }

    @Override
    public String displayAt(byte[] source, String id) {
        return editing.displayAt(source, id);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        return editing.editText(source, id, newText);
    }
}
