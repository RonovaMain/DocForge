package dev.ronova.docforge.ooxml;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.core.DocFormat;
import dev.ronova.docforge.core.OfficeEngine;
import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.TextSplice;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Просмотр и правка .pptx. Слайд рисуется как набор абсолютно позиционированных блоков
 * (координаты в пунктах, как в файле) и масштабируется под ширину экрана скриптом страницы.
 * Размеры, шрифты и цвета наследуются по цепочке слайд -> макет -> мастер -> тема.
 */
public final class PptxEngine implements OfficeEngine {

    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PRES = "ppt/presentation.xml";
    private static final double EMU = 12700.0;

    @Override
    public DocFormat format() {
        return DocFormat.PPTX;
    }

    // ------------------------------------------------------------------ колода

    private static final class Deck {
        final ZipPackage pkg;
        final Document pres;
        double width = 9144000;
        double height = 6858000;
        final List<String> slideParts = new ArrayList<>();
        Element defaultTextStyle;
        private final Map<String, Document> docs = new HashMap<>();
        private final Map<String, Map<String, String>> relCache = new HashMap<>();
        private final Map<String, Map<String, String>> themes = new HashMap<>();

        Deck(ZipPackage pkg) {
            this.pkg = pkg;
            this.pres = pkg.xml(PRES);
            if (pres == null) {
                throw new DocException("В файле нет ppt/presentation.xml — это не презентация PowerPoint");
            }
            List<Element> sz = Xml.descendants(pres, P, "sldSz");
            if (!sz.isEmpty()) {
                try {
                    width = Double.parseDouble(sz.get(0).getAttribute("cx"));
                    height = Double.parseDouble(sz.get(0).getAttribute("cy"));
                } catch (NumberFormatException ignored) {
                    // размер по умолчанию 4:3
                }
            }
            List<Element> dts = Xml.descendants(pres, P, "defaultTextStyle");
            defaultTextStyle = dts.isEmpty() ? null : dts.get(0);
            Map<String, String> rels = rels(PRES);
            for (Element id : Xml.descendants(pres, P, "sldId")) {
                String part = rels.get(id.getAttributeNS(R, "id"));
                if (part != null && pkg.has(part)) {
                    slideParts.add(part);
                }
            }
        }

        Document doc(String part) {
            if (part == null) return null;
            if (!docs.containsKey(part)) {
                docs.put(part, pkg.xml(part));
            }
            return docs.get(part);
        }

        /** id связи -> полное имя части (внешние ссылки пропускаются). */
        Map<String, String> rels(String part) {
            Map<String, String> m = relCache.get(part);
            if (m == null) {
                m = new HashMap<>();
                for (ZipPackage.Relationship r : pkg.relationships(part)) {
                    if (!r.external) m.put(r.id, r.target);
                }
                relCache.put(part, m);
            }
            return m;
        }

        String relOfType(String part, String typeSuffix) {
            for (ZipPackage.Relationship r : pkg.relationships(part)) {
                if (!r.external && r.type.endsWith(typeSuffix)) return r.target;
            }
            return null;
        }

        Map<String, String> theme(String themePart) {
            Map<String, String> t = themes.get(themePart);
            if (t != null) return t;
            t = new HashMap<>();
            Document doc = doc(themePart);
            if (doc != null) {
                List<Element> schemes = Xml.descendants(doc, A, "clrScheme");
                if (!schemes.isEmpty()) {
                    for (Element c : Xml.children(schemes.get(0))) {
                        for (Element v : Xml.children(c)) {
                            String hex = v.hasAttribute("lastClr") ? v.getAttribute("lastClr") : v.getAttribute("val");
                            if (Html.isHexColor(hex)) t.put(c.getLocalName(), hex.toUpperCase(Locale.ROOT));
                        }
                    }
                }
            }
            themes.put(themePart, t);
            return t;
        }
    }

    /** Всё, что нужно для отрисовки одного слайда. */
    private static final class Ctx {
        Deck deck;
        int index;
        String slidePart, layoutPart, masterPart;
        Document slide, layout, master;
        Map<String, String> slideRels, layoutRels, masterRels;
        Colors colors;
        IdentityHashMap<Element, Integer> paraIds = new IdentityHashMap<>();
    }

    private enum Level { SLIDE, LAYOUT, MASTER }

    private static Ctx context(Deck deck, int index) {
        Ctx c = new Ctx();
        c.deck = deck;
        c.index = index;
        c.slidePart = deck.slideParts.get(index);
        c.slide = deck.doc(c.slidePart);
        c.slideRels = deck.rels(c.slidePart);
        c.layoutPart = deck.relOfType(c.slidePart, "/slideLayout");
        c.layout = deck.doc(c.layoutPart);
        c.layoutRels = c.layoutPart == null ? new HashMap<>() : deck.rels(c.layoutPart);
        c.masterPart = c.layoutPart == null ? null : deck.relOfType(c.layoutPart, "/slideMaster");
        c.master = deck.doc(c.masterPart);
        c.masterRels = c.masterPart == null ? new HashMap<>() : deck.rels(c.masterPart);
        String themePart = c.masterPart == null ? null : deck.relOfType(c.masterPart, "/theme");
        Map<String, String> clrMap = new HashMap<>();
        if (c.master != null) {
            List<Element> maps = Xml.descendants(c.master, P, "clrMap");
            if (!maps.isEmpty()) {
                Element m = maps.get(0);
                for (int i = 0; i < m.getAttributes().getLength(); i++) {
                    clrMap.put(m.getAttributes().item(i).getNodeName(), m.getAttributes().item(i).getNodeValue());
                }
            }
        }
        c.colors = new Colors(themePart == null ? new HashMap<>() : deck.theme(themePart), clrMap);
        if (c.slide != null) {
            List<Element> all = Xml.descendants(c.slide, A, "p");
            for (int i = 0; i < all.size(); i++) c.paraIds.put(all.get(i), i);
        }
        return c;
    }

    // ------------------------------------------------------------------ цвета

    private static final class Colors {
        final Map<String, String> theme;
        final Map<String, String> clrMap;

        Colors(Map<String, String> theme, Map<String, String> clrMap) {
            this.theme = theme;
            this.clrMap = clrMap;
        }

        /** Цвет из srgbClr / schemeClr / sysClr (с модификаторами) в виде CSS или null. */
        String css(Element holder) {
            if (holder == null) return null;
            for (Element e : Xml.children(holder)) {
                String name = e.getLocalName();
                String hex = null;
                if ("srgbClr".equals(name)) {
                    hex = e.getAttribute("val");
                } else if ("schemeClr".equals(name)) {
                    String v = e.getAttribute("val");
                    if ("phClr".equals(v)) return null;
                    String mapped = clrMap.containsKey(v) ? clrMap.get(v) : v;
                    hex = theme.get(mapped);
                    if (hex == null) hex = fallbackTheme(mapped);
                } else if ("sysClr".equals(name)) {
                    hex = e.hasAttribute("lastClr") ? e.getAttribute("lastClr")
                            : "window".equals(e.getAttribute("val")) ? "FFFFFF" : "000000";
                } else if ("prstClr".equals(name)) {
                    hex = "white".equals(e.getAttribute("val")) ? "FFFFFF" : "000000";
                }
                if (!Html.isHexColor(hex)) {
                    continue;
                }
                int[] rgb = ColorUtil.parse(hex);
                double alpha = 1;
                double lumMod = 1;
                double lumOff = 0;
                boolean lum = false;
                for (Element m : Xml.children(e)) {
                    double v = number(m.getAttribute("val")) / 100000.0;
                    switch (m.getLocalName()) {
                        case "lumMod": lumMod = v; lum = true; break;
                        case "lumOff": lumOff = v; lum = true; break;
                        case "tint": rgb = ColorUtil.tint(rgb, v); break;
                        case "shade": rgb = ColorUtil.shade(rgb, v); break;
                        case "alpha": alpha = v; break;
                        default: break;
                    }
                }
                if (lum) rgb = ColorUtil.luminance(rgb, lumMod, lumOff);
                if (alpha < 0.999) {
                    return "rgba(" + ColorUtil.clamp(rgb[0]) + "," + ColorUtil.clamp(rgb[1]) + "," + ColorUtil.clamp(rgb[2]) + ","
                            + Html.num(alpha) + ")";
                }
                return "#" + ColorUtil.hex(rgb);
            }
            return null;
        }

        private static String fallbackTheme(String name) {
            switch (name) {
                case "dk1": return "000000";
                case "lt1": return "FFFFFF";
                case "dk2": return "44546A";
                case "lt2": return "E7E6E6";
                case "accent1": return "4472C4";
                case "accent2": return "ED7D31";
                case "accent3": return "A5A5A5";
                case "accent4": return "FFC000";
                case "accent5": return "5B9BD5";
                case "accent6": return "70AD47";
                default: return null;
            }
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Double numberOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ геометрия

    private static final class Xf {
        final double ax, bx, ay, by;

        Xf(double ax, double bx, double ay, double by) {
            this.ax = ax;
            this.bx = bx;
            this.ay = ay;
            this.by = by;
        }
    }

    private static final class Geo {
        double x, y, w, h, rot;
        boolean flipH, flipV;
    }

    private static Geo geometry(Element xfrm, Xf xf) {
        if (xfrm == null) return null;
        Element off = Xml.child(xfrm, A, "off");
        Element ext = Xml.child(xfrm, A, "ext");
        if (off == null || ext == null) return null;
        Geo g = new Geo();
        double x = number(off.getAttribute("x"));
        double y = number(off.getAttribute("y"));
        g.x = (xf.ax * x + xf.bx) / EMU;
        g.y = (xf.ay * y + xf.by) / EMU;
        g.w = xf.ax * number(ext.getAttribute("cx")) / EMU;
        g.h = xf.ay * number(ext.getAttribute("cy")) / EMU;
        g.rot = number(xfrm.getAttribute("rot")) / 60000.0;
        g.flipH = "1".equals(xfrm.getAttribute("flipH")) || "true".equals(xfrm.getAttribute("flipH"));
        g.flipV = "1".equals(xfrm.getAttribute("flipV")) || "true".equals(xfrm.getAttribute("flipV"));
        return g;
    }

    private static String boxCss(Geo g) {
        StringBuilder css = new StringBuilder();
        css.append("left:").append(Html.num(g.x)).append("px;top:").append(Html.num(g.y)).append("px;width:")
                .append(Html.num(g.w)).append("px;height:").append(Html.num(g.h)).append("px;");
        StringBuilder t = new StringBuilder();
        if (g.rot != 0) t.append("rotate(").append(Html.num(g.rot)).append("deg) ");
        if (g.flipH) t.append("scaleX(-1) ");
        if (g.flipV) t.append("scaleY(-1) ");
        if (t.length() > 0) css.append("transform:").append(t.toString().trim()).append(';');
        return css.toString();
    }

    // ------------------------------------------------------------------ рендер

    @Override
    public String renderHtml(byte[] source) {
        Deck deck = new Deck(ZipPackage.read(source));
        if (deck.slideParts.isEmpty()) {
            throw new DocException("В презентации нет слайдов");
        }
        StringBuilder body = new StringBuilder();
        double w = deck.width / EMU;
        double h = deck.height / EMU;
        for (int i = 0; i < deck.slideParts.size(); i++) {
            Ctx c = context(deck, i);
            if (c.slide == null) continue;
            body.append("<div class=\"slidebox\" data-w=\"").append(Html.num(w)).append("\" data-h=\"").append(Html.num(h))
                    .append("\"><div class=\"slide\" style=\"width:").append(Html.num(w)).append("px;height:").append(Html.num(h))
                    .append("px;").append(background(c)).append("\">");
            Xf identity = new Xf(1, 0, 1, 0);
            boolean slideShows = !"0".equals(rootAttr(c.slide, "showMasterSp"));
            boolean layoutShowsMaster = !"0".equals(rootAttr(c.layout, "showMasterSp"));
            if (slideShows && layoutShowsMaster) walk(c, tree(c.master), identity, Level.MASTER, c.masterRels, body);
            if (slideShows) walk(c, tree(c.layout), identity, Level.LAYOUT, c.layoutRels, body);
            walk(c, tree(c.slide), identity, Level.SLIDE, c.slideRels, body);
            body.append("</div></div>");
        }
        return Html.page(DocFormat.Kind.SLIDES, "", body.toString());
    }

    private static String rootAttr(Document doc, String name) {
        return doc == null ? null : doc.getDocumentElement().hasAttribute(name) ? doc.getDocumentElement().getAttribute(name) : null;
    }

    private static Element tree(Document doc) {
        if (doc == null) return null;
        List<Element> l = Xml.descendants(doc, P, "spTree");
        return l.isEmpty() ? null : l.get(0);
    }

    /** Фон слайда: слайд -> макет -> мастер. */
    private String background(Ctx c) {
        Document[] docs = {c.slide, c.layout, c.master};
        Map<String, String>[] rels = null;
        for (int i = 0; i < docs.length; i++) {
            if (docs[i] == null) continue;
            List<Element> bgs = Xml.descendants(docs[i], P, "bg");
            if (bgs.isEmpty()) continue;
            Element bg = bgs.get(0);
            Element pr = Xml.child(bg, P, "bgPr");
            if (pr != null) {
                String color = c.colors.css(Xml.child(pr, A, "solidFill"));
                if (color != null) return "background:" + color + ";";
                Element blip = firstDescendant(pr, A, "blip");
                if (blip != null) {
                    Map<String, String> r = i == 0 ? c.slideRels : i == 1 ? c.layoutRels : c.masterRels;
                    String part = r.get(blip.getAttributeNS(R, "embed"));
                    if (part != null && c.deck.pkg.has(part) && Html.imageMime(part) != null) {
                        return "background:url('" + Html.mediaUrl(part) + "') center/cover no-repeat;";
                    }
                }
            }
            Element ref = Xml.child(bg, P, "bgRef");
            if (ref != null) {
                String color = c.colors.css(ref);
                if (color != null) return "background:" + color + ";";
            }
        }
        return "background:#fff;";
    }

    private static Element firstDescendant(Element root, String ns, String local) {
        List<Element> l = Xml.descendants(root, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private void walk(Ctx c, Element tree, Xf xf, Level level, Map<String, String> rels, StringBuilder out) {
        if (tree == null) return;
        for (Element e : Xml.children(tree)) {
            if (Xml.is(e, P, "sp")) {
                shape(c, e, xf, level, out);
            } else if (Xml.is(e, P, "pic")) {
                picture(c, e, xf, rels, out);
            } else if (Xml.is(e, P, "grpSp")) {
                group(c, e, xf, level, rels, out);
            } else if (Xml.is(e, P, "graphicFrame")) {
                frame(c, e, xf, level, out);
            } else if (Xml.is(e, P, "cxnSp")) {
                connector(c, e, xf, out);
            }
        }
    }

    private void group(Ctx c, Element grp, Xf xf, Level level, Map<String, String> rels, StringBuilder out) {
        Element pr = Xml.child(grp, P, "grpSpPr");
        Element xfrm = pr == null ? null : Xml.child(pr, A, "xfrm");
        Xf inner = xf;
        if (xfrm != null) {
            Element off = Xml.child(xfrm, A, "off");
            Element ext = Xml.child(xfrm, A, "ext");
            Element chOff = Xml.child(xfrm, A, "chOff");
            Element chExt = Xml.child(xfrm, A, "chExt");
            if (off != null && ext != null && chOff != null && chExt != null) {
                double cx = number(ext.getAttribute("cx"));
                double cy = number(ext.getAttribute("cy"));
                double ccx = number(chExt.getAttribute("cx"));
                double ccy = number(chExt.getAttribute("cy"));
                double sx = ccx == 0 ? 1 : cx / ccx;
                double sy = ccy == 0 ? 1 : cy / ccy;
                double ox = number(off.getAttribute("x"));
                double oy = number(off.getAttribute("y"));
                double cox = number(chOff.getAttribute("x"));
                double coy = number(chOff.getAttribute("y"));
                inner = new Xf(xf.ax * sx, xf.ax * (ox - cox * sx) + xf.bx, xf.ay * sy, xf.ay * (oy - coy * sy) + xf.by);
            }
        }
        walk(c, grp, inner, level, rels, out);
    }

    // ---------------------------------------------------------- фигуры

    private static Element placeholder(Element nvPr) {
        return nvPr == null ? null : Xml.child(nvPr, P, "ph");
    }

    private static String normType(String type) {
        if (type == null || type.isEmpty() || "obj".equals(type) || "subTitle".equals(type)) return "body";
        if ("ctrTitle".equals(type)) return "title";
        return type;
    }

    /** Фигура-заполнитель макета или мастера с тем же типом/индексом. */
    private static Element findPlaceholder(Document doc, Element ph, boolean useIdx) {
        if (doc == null || ph == null) return null;
        String type = normType(ph.getAttribute("type"));
        String idx = ph.getAttribute("idx");
        Element byType = null;
        for (Element sp : Xml.descendants(doc, P, "sp")) {
            Element other = placeholder(Xml.child(Xml.child(sp, P, "nvSpPr"), P, "nvPr"));
            if (other == null) continue;
            if (useIdx && !idx.isEmpty() && idx.equals(other.getAttribute("idx"))) return sp;
            if (byType == null && type.equals(normType(other.getAttribute("type")))) byType = sp;
        }
        return byType;
    }

    private static Element xfrmOf(Element sp) {
        if (sp == null) return null;
        Element spPr = Xml.child(sp, P, "spPr");
        return spPr == null ? null : Xml.child(spPr, A, "xfrm");
    }

    private void shape(Ctx c, Element sp, Xf xf, Level level, StringBuilder out) {
        Element nv = Xml.child(sp, P, "nvSpPr");
        Element cNvPr = Xml.child(nv, P, "cNvPr");
        if (cNvPr != null && ("1".equals(cNvPr.getAttribute("hidden")) || "true".equals(cNvPr.getAttribute("hidden")))) return;
        Element ph = placeholder(Xml.child(nv, P, "nvPr"));
        if (ph != null && level != Level.SLIDE) return;
        Element spPr = Xml.child(sp, P, "spPr");

        Element layoutPh = ph == null ? null : findPlaceholder(c.layout, ph, true);
        Element masterPh = ph == null ? null : findPlaceholder(c.master, ph, false);
        Element xfrm = spPr == null ? null : Xml.child(spPr, A, "xfrm");
        if (xfrm == null) xfrm = xfrmOf(layoutPh);
        if (xfrm == null) xfrm = xfrmOf(masterPh);
        Geo g = geometry(xfrm, xf);
        if (g == null) return;

        Element geom = spPr == null ? null : Xml.child(spPr, A, "prstGeom");
        String prst = geom == null ? "rect" : geom.getAttribute("prst");
        Element style = Xml.child(sp, P, "style");
        String lineColor = null;
        double lineWidth = 0;
        if (spPr != null) {
            Element ln = Xml.child(spPr, A, "ln");
            if (ln != null && Xml.child(ln, A, "noFill") == null) {
                lineColor = c.colors.css(Xml.child(ln, A, "solidFill"));
                lineWidth = Math.max(1, number(ln.getAttribute("w")) / EMU);
                if (lineColor == null && style != null) lineColor = c.colors.css(Xml.child(style, A, "lnRef"));
            } else if (ln == null && style != null) {
                Element lnRef = Xml.child(style, A, "lnRef");
                if (lnRef != null && !"0".equals(lnRef.getAttribute("idx"))) {
                    lineColor = c.colors.css(lnRef);
                    lineWidth = 1;
                }
            }
        } else if (style != null) {
            Element lnRef = Xml.child(style, A, "lnRef");
            if (lnRef != null && !"0".equals(lnRef.getAttribute("idx"))) {
                lineColor = c.colors.css(lnRef);
                lineWidth = 1;
            }
        }
        if ("line".equals(prst) || "straightConnector1".equals(prst)) {
            line(g, lineColor == null ? "#000" : lineColor, Math.max(lineWidth, 1), out);
            return;
        }

        String fill = null;
        if (spPr != null) {
            if (Xml.child(spPr, A, "noFill") != null) {
                fill = "none";
            } else if (Xml.child(spPr, A, "solidFill") != null) {
                fill = c.colors.css(Xml.child(spPr, A, "solidFill"));
            } else if (Xml.child(spPr, A, "gradFill") != null) {
                Element gs = firstDescendant(Xml.child(spPr, A, "gradFill"), A, "gs");
                fill = gs == null ? null : c.colors.css(gs);
            }
        }
        if (fill == null && style != null) {
            Element fillRef = Xml.child(style, A, "fillRef");
            if (fillRef != null && !"0".equals(fillRef.getAttribute("idx"))) fill = c.colors.css(fillRef);
        }
        String fontColor = null;
        if (style != null) fontColor = c.colors.css(Xml.child(style, A, "fontRef"));

        StringBuilder css = new StringBuilder(boxCss(g));
        if (fill != null && !"none".equals(fill)) css.append("background:").append(fill).append(';');
        if (lineColor != null) css.append("border:").append(Html.num(lineWidth)).append("px solid ").append(lineColor).append(';');
        if ("ellipse".equals(prst)) css.append("border-radius:50%;");
        else if ("roundRect".equals(prst)) css.append("border-radius:").append(Html.num(Math.min(g.w, g.h) * 0.1667)).append("px;");

        Element txBody = Xml.child(sp, P, "txBody");
        out.append("<div class=\"sh\" style=\"").append(css).append("\">");
        if (txBody != null) {
            text(c, txBody, ph, layoutPh, masterPh, fontColor, out);
        }
        out.append("</div>");
    }

    private void line(Geo g, String color, double width, StringBuilder out) {
        double w = Math.max(g.w, 0.01);
        double h = Math.max(g.h, 0.01);
        double x1 = g.flipH ? w : 0;
        double x2 = g.flipH ? 0 : w;
        double y1 = g.flipV ? h : 0;
        double y2 = g.flipV ? 0 : h;
        out.append("<div class=\"sh\" style=\"left:").append(Html.num(g.x)).append("px;top:").append(Html.num(g.y))
                .append("px;width:").append(Html.num(Math.max(w, width))).append("px;height:").append(Html.num(Math.max(h, width)))
                .append("px\"><svg width=\"").append(Html.num(Math.max(w, width))).append("\" height=\"").append(Html.num(Math.max(h, width)))
                .append("\" style=\"overflow:visible\"><line x1=\"").append(Html.num(x1)).append("\" y1=\"").append(Html.num(y1))
                .append("\" x2=\"").append(Html.num(x2)).append("\" y2=\"").append(Html.num(y2)).append("\" stroke=\"")
                .append(Html.esc(color)).append("\" stroke-width=\"").append(Html.num(width)).append("\"/></svg></div>");
    }

    private void connector(Ctx c, Element cxn, Xf xf, StringBuilder out) {
        Element spPr = Xml.child(cxn, P, "spPr");
        Geo g = geometry(spPr == null ? null : Xml.child(spPr, A, "xfrm"), xf);
        if (g == null) return;
        String color = null;
        double width = 1;
        Element ln = spPr == null ? null : Xml.child(spPr, A, "ln");
        if (ln != null) {
            color = c.colors.css(Xml.child(ln, A, "solidFill"));
            width = Math.max(1, number(ln.getAttribute("w")) / EMU);
        }
        if (color == null) {
            Element style = Xml.child(cxn, P, "style");
            color = style == null ? null : c.colors.css(Xml.child(style, A, "lnRef"));
        }
        line(g, color == null ? "#000" : color, width, out);
    }

    // ---------------------------------------------------------- картинки

    private void picture(Ctx c, Element pic, Xf xf, Map<String, String> rels, StringBuilder out) {
        Element cNvPr = Xml.child(Xml.child(pic, P, "nvPicPr"), P, "cNvPr");
        if (cNvPr != null && "1".equals(cNvPr.getAttribute("hidden"))) return;
        Element spPr = Xml.child(pic, P, "spPr");
        Geo g = geometry(spPr == null ? null : Xml.child(spPr, A, "xfrm"), xf);
        if (g == null) return;
        Element blipFill = Xml.child(pic, P, "blipFill");
        Element blip = blipFill == null ? null : Xml.child(blipFill, A, "blip");
        String part = blip == null ? null : rels.get(blip.getAttributeNS(R, "embed"));
        out.append("<div class=\"sh\" style=\"").append(boxCss(g)).append("overflow:hidden;\">");
        if (part == null || !c.deck.pkg.has(part) || Html.imageMime(part) == null) {
            out.append("<div style=\"width:100%;height:100%;background:#eee\"></div>");
        } else {
            Element crop = blipFill == null ? null : Xml.child(blipFill, A, "srcRect");
            double l = crop == null ? 0 : number(crop.getAttribute("l")) / 100000.0;
            double t = crop == null ? 0 : number(crop.getAttribute("t")) / 100000.0;
            double r = crop == null ? 0 : number(crop.getAttribute("r")) / 100000.0;
            double b = crop == null ? 0 : number(crop.getAttribute("b")) / 100000.0;
            double vw = 1 - l - r;
            double vh = 1 - t - b;
            if (vw <= 0.01 || vh <= 0.01) {
                l = t = r = b = 0;
                vw = vh = 1;
            }
            out.append("<img src=\"").append(Html.mediaUrl(part)).append("\" alt=\"\" style=\"position:absolute;left:")
                    .append(Html.num(-l / vw * 100)).append("%;top:").append(Html.num(-t / vh * 100)).append("%;width:")
                    .append(Html.num(100 / vw)).append("%;height:").append(Html.num(100 / vh)).append("%\">");
        }
        out.append("</div>");
    }

    // ---------------------------------------------------------- таблицы и диаграммы

    private void frame(Ctx c, Element gf, Xf xf, Level level, StringBuilder out) {
        if (level != Level.SLIDE) return;
        Geo g = geometry(Xml.child(gf, P, "xfrm"), xf);
        if (g == null) return;
        Element tbl = firstDescendant(gf, A, "tbl");
        if (tbl == null) {
            String uri = "";
            Element data = firstDescendant(gf, A, "graphicData");
            if (data != null) uri = data.getAttribute("uri");
            String label = uri.contains("chart") ? "Диаграмма" : uri.contains("diagram") ? "Схема SmartArt" : "Объект";
            out.append("<div class=\"sh\" style=\"").append(boxCss(g)).append("border:1px dashed #aaa;background:#f6f6f6;")
                    .append("display:flex;align-items:center;justify-content:center;color:#777;font:14px sans-serif\">[")
                    .append(label).append("]</div>");
            return;
        }
        out.append("<div class=\"sh\" style=\"").append(boxCss(g)).append("\"><table><colgroup>");
        Element grid = Xml.child(tbl, A, "tblGrid");
        if (grid != null) {
            for (Element col : Xml.children(grid, A, "gridCol")) {
                out.append("<col style=\"width:").append(Html.num(number(col.getAttribute("w")) * xf.ax / EMU)).append("px\">");
            }
        }
        out.append("</colgroup><tbody>");
        List<Element> styles = new ArrayList<>();
        if (c.deck.defaultTextStyle != null) styles.add(c.deck.defaultTextStyle);
        for (Element tr : Xml.children(tbl, A, "tr")) {
            out.append("<tr style=\"height:").append(Html.num(number(tr.getAttribute("h")) * xf.ay / EMU)).append("px\">");
            for (Element tc : Xml.children(tr, A, "tc")) {
                if ("1".equals(tc.getAttribute("hMerge")) || "1".equals(tc.getAttribute("vMerge"))) continue;
                out.append("<td");
                int cs = (int) number(tc.getAttribute("gridSpan"));
                int rs = (int) number(tc.getAttribute("rowSpan"));
                if (cs > 1) out.append(" colspan=\"").append(cs).append('"');
                if (rs > 1) out.append(" rowspan=\"").append(rs).append('"');
                Element tcPr = Xml.child(tc, A, "tcPr");
                StringBuilder css = new StringBuilder();
                boolean anyBorder = false;
                if (tcPr != null) {
                    String bg = c.colors.css(Xml.child(tcPr, A, "solidFill"));
                    if (bg != null) css.append("background:").append(bg).append(';');
                    String[][] sides = {{"lnL", "left"}, {"lnR", "right"}, {"lnT", "top"}, {"lnB", "bottom"}};
                    for (String[] side : sides) {
                        Element ln = Xml.child(tcPr, A, side[0]);
                        if (ln == null || Xml.child(ln, A, "noFill") != null) continue;
                        String col = c.colors.css(Xml.child(ln, A, "solidFill"));
                        if (col != null) {
                            anyBorder = true;
                            css.append("border-").append(side[1]).append(':').append(Html.num(Math.max(1, number(ln.getAttribute("w")) / EMU)))
                                    .append("px solid ").append(col).append(';');
                        }
                    }
                    String pad = "";
                    if (tcPr.hasAttribute("marL")) css.append("padding-left:").append(Html.num(number(tcPr.getAttribute("marL")) / EMU)).append("px;");
                    if (tcPr.hasAttribute("marR")) css.append("padding-right:").append(Html.num(number(tcPr.getAttribute("marR")) / EMU)).append("px;");
                    css.append(pad);
                }
                if (!anyBorder) css.append("border:1px solid #c8c8c8;");
                if (css.indexOf("padding-left") < 0) css.append("padding:3px 7px;");
                out.append(" style=\"").append(css).append("\">");
                Element tx = Xml.child(tc, A, "txBody");
                if (tx != null) {
                    TextEnv env = new TextEnv(c, styles, 1.0, null, false);
                    for (Element p : Xml.children(tx, A, "p")) paragraph(c, p, env, out);
                }
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
    }

    // ---------------------------------------------------------- текст

    private static final class TextEnv {
        final Ctx ctx;
        final List<Element> styles;
        final double fontScale;
        final String fontColor;
        final boolean bodyPlaceholder;
        final int[] counters = new int[9];

        TextEnv(Ctx ctx, List<Element> styles, double fontScale, String fontColor, boolean bodyPlaceholder) {
            this.ctx = ctx;
            this.styles = styles;
            this.fontScale = fontScale;
            this.fontColor = fontColor;
            this.bodyPlaceholder = bodyPlaceholder;
        }
    }

    private static Element lstStyleOf(Element sp) {
        Element tx = sp == null ? null : Xml.child(sp, P, "txBody");
        return tx == null ? null : Xml.child(tx, A, "lstStyle");
    }

    private static Element bodyPrOf(Element sp) {
        Element tx = sp == null ? null : Xml.child(sp, P, "txBody");
        return tx == null ? null : Xml.child(tx, A, "bodyPr");
    }

    private static String bodyPrAttr(String name, Element... chain) {
        for (Element e : chain) {
            if (e != null && e.hasAttribute(name)) return e.getAttribute(name);
        }
        return null;
    }

    private void text(Ctx c, Element txBody, Element ph, Element layoutPh, Element masterPh, String fontColor, StringBuilder out) {
        Element ownBody = Xml.child(txBody, A, "bodyPr");
        Element layoutBody = bodyPrOf(layoutPh);
        Element masterBody = bodyPrOf(masterPh);
        List<Element> styles = new ArrayList<>();
        Element own = Xml.child(txBody, A, "lstStyle");
        if (own != null) styles.add(own);
        Element l = lstStyleOf(layoutPh);
        if (l != null) styles.add(l);
        Element m = lstStyleOf(masterPh);
        if (m != null) styles.add(m);
        String kind = ph == null ? null : normType(ph.getAttribute("type"));
        if (kind != null && c.master != null) {
            List<Element> txStyles = Xml.descendants(c.master, P, "txStyles");
            if (!txStyles.isEmpty()) {
                String name = "title".equals(kind) ? "titleStyle" : "body".equals(kind) ? "bodyStyle" : "otherStyle";
                Element st = Xml.child(txStyles.get(0), P, name);
                if (st != null) styles.add(st);
            }
        }
        if (c.deck.defaultTextStyle != null) styles.add(c.deck.defaultTextStyle);

        double scale = 1.0;
        if (ownBody != null) {
            Element fit = Xml.child(ownBody, A, "normAutofit");
            if (fit != null && fit.hasAttribute("fontScale")) scale = number(fit.getAttribute("fontScale")) / 100000.0;
        }
        String anchor = bodyPrAttr("anchor", ownBody, layoutBody, masterBody);
        String wrap = bodyPrAttr("wrap", ownBody, layoutBody, masterBody);
        double lIns = insetOf("lIns", 91440, ownBody, layoutBody, masterBody);
        double tIns = insetOf("tIns", 45720, ownBody, layoutBody, masterBody);
        double rIns = insetOf("rIns", 91440, ownBody, layoutBody, masterBody);
        double bIns = insetOf("bIns", 45720, ownBody, layoutBody, masterBody);

        StringBuilder css = new StringBuilder();
        css.append("justify-content:").append("ctr".equals(anchor) ? "center" : "b".equals(anchor) ? "flex-end" : "flex-start").append(';');
        css.append("padding:").append(Html.num(tIns)).append("px ").append(Html.num(rIns)).append("px ").append(Html.num(bIns))
                .append("px ").append(Html.num(lIns)).append("px;");
        if ("none".equals(wrap)) css.append("white-space:nowrap;");
        out.append("<div class=\"tx\" style=\"").append(css).append("\">");
        TextEnv env = new TextEnv(c, styles, scale, fontColor, "body".equals(kind));
        for (Element p : Xml.children(txBody, A, "p")) paragraph(c, p, env, out);
        out.append("</div>");
    }

    private static double insetOf(String name, double def, Element... chain) {
        String v = bodyPrAttr(name, chain);
        return (v == null ? def : number(v)) / EMU;
    }

    private static List<Element> levelChain(Element pPr, List<Element> styles, int lvl) {
        List<Element> chain = new ArrayList<>();
        if (pPr != null) chain.add(pPr);
        for (Element st : styles) {
            Element l = Xml.child(st, A, "lvl" + (lvl + 1) + "pPr");
            if (l != null) chain.add(l);
        }
        return chain;
    }

    private static String attrOf(List<Element> chain, String name) {
        for (Element e : chain) {
            if (e.hasAttribute(name)) return e.getAttribute(name);
        }
        return null;
    }

    private static Element childOf(List<Element> chain, String local) {
        for (Element e : chain) {
            Element c = Xml.child(e, A, local);
            if (c != null) return c;
        }
        return null;
    }

    /** Первый элемент цепочки, где задан любой из вариантов маркера. */
    private static Element bulletOf(List<Element> chain) {
        for (Element e : chain) {
            for (String n : new String[]{"buNone", "buChar", "buAutoNum"}) {
                Element b = Xml.child(e, A, n);
                if (b != null) return b;
            }
        }
        return null;
    }

    /** Атрибут run-свойств: сначала свой rPr, затем defRPr вдоль цепочки. */
    private static String runAttr(Element rPr, List<Element> chain, String name, boolean inherit) {
        if (rPr != null && rPr.hasAttribute(name)) return rPr.getAttribute(name);
        if (inherit) {
            for (Element e : chain) {
                Element d = Xml.child(e, A, "defRPr");
                if (d != null && d.hasAttribute(name)) return d.getAttribute(name);
            }
        }
        return null;
    }

    private static Element runFill(Element rPr, List<Element> chain, boolean inherit) {
        if (rPr != null && Xml.child(rPr, A, "solidFill") != null) return Xml.child(rPr, A, "solidFill");
        if (inherit) {
            for (Element e : chain) {
                Element d = Xml.child(e, A, "defRPr");
                if (d != null && Xml.child(d, A, "solidFill") != null) return Xml.child(d, A, "solidFill");
            }
        }
        return null;
    }

    private static String runFont(Element rPr, List<Element> chain, boolean inherit) {
        Element latin = rPr == null ? null : Xml.child(rPr, A, "latin");
        if (latin == null && inherit) {
            for (Element e : chain) {
                Element d = Xml.child(e, A, "defRPr");
                if (d != null && Xml.child(d, A, "latin") != null) {
                    latin = Xml.child(d, A, "latin");
                    break;
                }
            }
        }
        String face = latin == null ? null : latin.getAttribute("typeface");
        return face == null || face.startsWith("+") ? null : Html.fontFamily(face);
    }

    private void paragraph(Ctx c, Element p, TextEnv env, StringBuilder out) {
        Element pPr = Xml.child(p, A, "pPr");
        int lvl = (int) Math.max(0, Math.min(8, number(pPr == null ? null : pPr.getAttribute("lvl"))));
        List<Element> chain = levelChain(pPr, env.styles, lvl);

        double baseSize = 18;
        String sz = runAttr(null, chain, "sz", true);
        if (sz != null) baseSize = number(sz) / 100.0;
        baseSize *= env.fontScale;

        StringBuilder css = new StringBuilder();
        String algn = attrOf(chain, "algn");
        if (algn != null) {
            css.append("text-align:").append("ctr".equals(algn) ? "center" : "r".equals(algn) ? "right"
                    : "just".equals(algn) || "dist".equals(algn) ? "justify" : "left").append(';');
        }
        Double marL = numberOrNull(attrOf(chain, "marL"));
        Double indent = numberOrNull(attrOf(chain, "indent"));
        Element bullet = bulletOf(chain);
        String marker = null;
        if (bullet != null && !"buNone".equals(bullet.getLocalName())) {
            if ("buChar".equals(bullet.getLocalName())) {
                String ch = bullet.getAttribute("char");
                marker = ch.length() == 1 && (ch.charAt(0) >= 0x2010 && ch.charAt(0) < 0xE000 || "•-–—*".indexOf(ch.charAt(0)) >= 0) ? ch : "\u2022";
                for (int i = lvl; i < 9; i++) env.counters[i] = 0;
            } else {
                int start = (int) number(bullet.getAttribute("startAt"));
                env.counters[lvl] = env.counters[lvl] == 0 ? (start > 0 ? start : 1) : env.counters[lvl] + 1;
                for (int i = lvl + 1; i < 9; i++) env.counters[i] = 0;
                marker = autoNumber(env.counters[lvl], bullet.getAttribute("type"));
            }
        } else {
            for (int i = lvl; i < 9; i++) env.counters[i] = 0;
        }
        if (marL != null) css.append("margin-left:").append(Html.num(marL / EMU)).append("px;");
        if (indent != null) css.append("text-indent:").append(Html.num(indent / EMU)).append("px;");
        double hanging = indent == null || indent >= 0 ? 0 : -indent / EMU;

        Element lnSpc = childOf(chain, "lnSpc");
        if (lnSpc != null) {
            Element pct = Xml.child(lnSpc, A, "spcPct");
            Element pts = Xml.child(lnSpc, A, "spcPts");
            if (pct != null) css.append("line-height:").append(Html.num(number(pct.getAttribute("val")) / 100000.0 * 1.2)).append(';');
            else if (pts != null) css.append("line-height:").append(Html.num(number(pts.getAttribute("val")) / 100.0)).append("px;");
        }
        spacing(css, "margin-top", childOf(chain, "spcBef"), baseSize);
        spacing(css, "margin-bottom", childOf(chain, "spcAft"), baseSize);

        css.append("font-size:").append(Html.num(baseSize)).append("px;");
        String baseColor = env.ctx.colors.css(runFill(null, chain, true));
        if (baseColor == null) baseColor = env.fontColor;
        if (baseColor != null) css.append("color:").append(baseColor).append(';');
        String b = runAttr(null, chain, "b", true);
        if ("1".equals(b)) css.append("font-weight:bold;");
        String i = runAttr(null, chain, "i", true);
        if ("1".equals(i)) css.append("font-style:italic;");
        String font = runFont(null, chain, true);
        if (font != null) css.append("font-family:").append(font).append(';');

        StringBuilder inner = new StringBuilder();
        if (marker != null) {
            inner.append("<span class=\"mk\" style=\"min-width:").append(Html.num(hanging)).append("px\">").append(Html.esc(marker)).append("</span>");
        }
        boolean any = false;
        for (Element e : Xml.children(p)) {
            if (Xml.is(e, A, "r") || Xml.is(e, A, "fld")) {
                Element t = Xml.child(e, A, "t");
                if (t == null) continue;
                runHtml(c, e, t.getTextContent(), chain, env.fontScale, inner);
                any = true;
            } else if (Xml.is(e, A, "br")) {
                inner.append("<br>");
                any = true;
            }
        }
        if (!any && marker == null) inner.append("<br>");
        out.append("<p");
        Integer id = c.paraIds.get(p);
        if (id != null) out.append(" data-id=\"").append(c.index).append(':').append(id).append('"');
        out.append(" style=\"").append(css).append("\">").append(inner).append("</p>");
    }

    private void runHtml(Ctx c, Element run, String text, List<Element> chain, double scale, StringBuilder out) {
        Element rPr = Xml.child(run, A, "rPr");
        StringBuilder css = new StringBuilder();
        if (rPr != null) {
            String sz = rPr.getAttribute("sz");
            if (!sz.isEmpty()) css.append("font-size:").append(Html.num(number(sz) / 100.0 * scale)).append("px;");
            if (rPr.hasAttribute("b")) css.append("font-weight:").append("1".equals(rPr.getAttribute("b")) ? "bold" : "normal").append(';');
            if (rPr.hasAttribute("i")) css.append("font-style:").append("1".equals(rPr.getAttribute("i")) ? "italic" : "normal").append(';');
            String u = rPr.getAttribute("u");
            String strike = rPr.getAttribute("strike");
            boolean under = !u.isEmpty() && !"none".equals(u);
            boolean st = !strike.isEmpty() && !"noStrike".equals(strike);
            if (under || st) css.append("text-decoration:").append(under && st ? "underline line-through" : under ? "underline" : "line-through").append(';');
            String color = c.colors.css(Xml.child(rPr, A, "solidFill"));
            if (color != null) css.append("color:").append(color).append(';');
            String font = runFont(rPr, chain, false);
            if (font != null) css.append("font-family:").append(font).append(';');
            double baseline = number(rPr.getAttribute("baseline"));
            if (baseline > 0) css.append("vertical-align:super;font-size:smaller;");
            else if (baseline < 0) css.append("vertical-align:sub;font-size:smaller;");
            if ("all".equals(rPr.getAttribute("cap"))) css.append("text-transform:uppercase;");
        }
        if (css.length() == 0) {
            out.append(Html.esc(text));
        } else {
            out.append("<span style=\"").append(css).append("\">").append(Html.esc(text)).append("</span>");
        }
    }

    private static void spacing(StringBuilder css, String prop, Element spc, double size) {
        if (spc == null) return;
        Element pts = Xml.child(spc, A, "spcPts");
        Element pct = Xml.child(spc, A, "spcPct");
        if (pts != null) css.append(prop).append(':').append(Html.num(number(pts.getAttribute("val")) / 100.0)).append("px;");
        else if (pct != null) css.append(prop).append(':').append(Html.num(number(pct.getAttribute("val")) / 100000.0 * size)).append("px;");
    }

    private static String autoNumber(int n, String type) {
        String t = type == null ? "" : type;
        String body;
        if (t.startsWith("alphaLc")) body = letters(n, false);
        else if (t.startsWith("alphaUc")) body = letters(n, true);
        else if (t.startsWith("romanLc")) body = roman(n).toLowerCase(Locale.ROOT);
        else if (t.startsWith("romanUc")) body = roman(n);
        else body = Integer.toString(n);
        return t.contains("ParenR") ? body + ")" : t.contains("ParenBoth") ? "(" + body + ")" : t.contains("Plain") ? body : body + ".";
    }

    private static String letters(int n, boolean upper) {
        StringBuilder sb = new StringBuilder();
        int v = Math.max(n, 1);
        while (v > 0) {
            int rem = (v - 1) % 26;
            sb.insert(0, (char) ((upper ? 'A' : 'a') + rem));
            v = (v - 1) / 26;
        }
        return sb.toString();
    }

    private static String roman(int n) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int v = Math.max(1, Math.min(n, 3999));
        for (int i = 0; i < values.length; i++) {
            while (v >= values[i]) {
                sb.append(symbols[i]);
                v -= values[i];
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ правка

    private static final class Target {
        ZipPackage pkg;
        String part;
        Document doc;
        Element paragraph;
    }

    private static Target resolve(byte[] source, String id) {
        if (id == null || id.indexOf(':') < 0) return null;
        try {
            int slide = Integer.parseInt(id.substring(0, id.indexOf(':')));
            int index = Integer.parseInt(id.substring(id.indexOf(':') + 1));
            Target t = new Target();
            t.pkg = ZipPackage.read(source);
            Deck deck = new Deck(t.pkg);
            if (slide < 0 || slide >= deck.slideParts.size()) return null;
            t.part = deck.slideParts.get(slide);
            t.doc = t.pkg.xml(t.part);
            if (t.doc == null) return null;
            List<Element> all = Xml.descendants(t.doc, A, "p");
            if (index < 0 || index >= all.size()) return null;
            t.paragraph = all.get(index);
            return t;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Element> textRuns(Element paragraph) {
        List<Element> out = new ArrayList<>();
        for (Element e : Xml.children(paragraph)) {
            if (Xml.is(e, A, "r") || Xml.is(e, A, "fld")) {
                Element t = Xml.child(e, A, "t");
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    @Override
    public String textAt(byte[] source, String id) {
        Target t = resolve(source, id);
        return t == null ? null : TextSplice.join(textRuns(t.paragraph));
    }

    @Override
    public String displayAt(byte[] source, String id) {
        return textAt(source, id);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        Target t = resolve(source, id);
        if (t == null) {
            throw new DocException("Текст не найден — возможно, документ изменился");
        }
        String text = TextSplice.singleLine(newText);
        List<Element> runs = textRuns(t.paragraph);
        if (!TextSplice.apply(runs, text, false) && !text.isEmpty()) {
            Element r = t.doc.createElementNS(A, "a:r");
            Element tt = t.doc.createElementNS(A, "a:t");
            tt.setTextContent(text);
            r.appendChild(tt);
            Element end = Xml.child(t.paragraph, A, "endParaRPr");
            t.paragraph.insertBefore(r, end);
        }
        t.pkg.putXml(t.part, t.doc);
        return t.pkg.toBytes();
    }
}
