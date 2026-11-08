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

/** Просмотр и правка .docx: документ превращается в HTML, правится текст абзацев. */
public final class DocxEngine implements OfficeEngine {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String V = "urn:schemas-microsoft-com:vml";
    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String MAIN = "word/document.xml";

    @Override
    public DocFormat format() {
        return DocFormat.DOCX;
    }

    // ------------------------------------------------------------------ рендер

    @Override
    public String renderHtml(byte[] source) {
        ZipPackage pkg = ZipPackage.read(source);
        Document doc = pkg.xml(MAIN);
        if (doc == null) {
            throw new DocException("В файле нет word/document.xml — это не документ Word");
        }
        Element body = Xml.descendants(doc, W, "body").isEmpty() ? null : Xml.descendants(doc, W, "body").get(0);
        if (body == null) {
            throw new DocException("Документ Word пуст");
        }
        Context ctx = new Context(pkg, doc);
        StringBuilder sb = new StringBuilder("<div class=\"page\"");
        String pageCss = ctx.styles.pageCss();
        if (!pageCss.isEmpty()) {
            sb.append(" style=\"").append(pageCss).append('"');
        }
        sb.append('>');
        ctx.blocks(body, sb);
        sb.append("</div>");
        return Html.page(DocFormat.Kind.TEXT, "", sb.toString());
    }

    private static final class Context {
        final ZipPackage pkg;
        final Styles styles;
        final Numbering numbering;
        final Map<String, String> rels = new HashMap<>();
        final IdentityHashMap<Element, Integer> paragraphIds = new IdentityHashMap<>();

        Context(ZipPackage pkg, Document doc) {
            this.pkg = pkg;
            this.styles = new Styles(pkg.xml("word/styles.xml"));
            this.numbering = new Numbering(pkg.xml("word/numbering.xml"));
            for (ZipPackage.Relationship r : pkg.relationships(MAIN)) {
                rels.put(r.id, r.external ? "" : r.target);
            }
            List<Element> all = Xml.descendants(doc, W, "p");
            for (int i = 0; i < all.size(); i++) {
                paragraphIds.put(all.get(i), i);
            }
        }

        void blocks(Element container, StringBuilder sb) {
            for (Element c : Xml.children(container)) {
                if (Xml.is(c, W, "p")) {
                    paragraph(c, sb);
                } else if (Xml.is(c, W, "tbl")) {
                    table(c, sb);
                } else if (Xml.is(c, W, "sdt")) {
                    Element content = Xml.child(c, W, "sdtContent");
                    if (content != null) {
                        blocks(content, sb);
                    }
                }
            }
        }

        // ---------------------------------------------------------- абзац

        void paragraph(Element p, StringBuilder sb) {
            Element pPr = Xml.child(p, W, "pPr");
            Props pp = styles.paragraphProps(wval(Xml.child(pPr, W, "pStyle")), pPr);

            StringBuilder inner = new StringBuilder();
            Numbering.Marker marker = pp.numId != null ? numbering.next(pp.numId, pp.ilvl == null ? 0 : pp.ilvl) : null;
            Double left = pp.indLeft;
            Double hanging = pp.hanging;
            if (marker != null) {
                if (left == null) {
                    left = marker.left;
                }
                if (hanging == null && pp.firstLine == null) {
                    hanging = marker.hanging;
                }
                double width = hanging == null ? 18 : hanging;
                inner.append("<span class=\"mk\" style=\"min-width:").append(Html.num(width)).append("pt\">")
                        .append(Html.esc(marker.text)).append("</span>");
            }
            inline(p, inner);

            StringBuilder css = new StringBuilder();
            if (pp.align != null) css.append("text-align:").append(pp.align).append(';');
            if (left != null) css.append("margin-left:").append(Html.num(left)).append("pt;");
            if (pp.indRight != null) css.append("margin-right:").append(Html.num(pp.indRight)).append("pt;");
            if (hanging != null && hanging > 0) {
                css.append("text-indent:-").append(Html.num(hanging)).append("pt;");
            } else if (pp.firstLine != null) {
                css.append("text-indent:").append(Html.num(pp.firstLine)).append("pt;");
            }
            if (pp.before != null) css.append("margin-top:").append(Html.num(pp.before)).append("pt;");
            if (pp.after != null) css.append("margin-bottom:").append(Html.num(pp.after)).append("pt;");
            if (pp.lineMult != null) css.append("line-height:").append(Html.num(pp.lineMult)).append(';');
            if (pp.shade != null) css.append("background:#").append(pp.shade).append(';');
            css.append(runCss(pp));

            sb.append("<p");
            Integer id = paragraphIds.get(p);
            if (id != null) {
                sb.append(" data-id=\"p").append(id).append('"');
            }
            if (css.length() > 0) {
                sb.append(" style=\"").append(css).append('"');
            }
            sb.append('>').append(inner).append("</p>");
        }

        /** Содержимое абзаца: раны, гиперссылки, вставки; удалённый текст пропускается. */
        void inline(Element parent, StringBuilder out) {
            for (Element c : Xml.children(parent)) {
                if (Xml.is(c, W, "r")) {
                    run(c, out);
                } else if (Xml.is(c, W, "hyperlink")) {
                    out.append("<span style=\"color:#0563C1;text-decoration:underline\">");
                    inline(c, out);
                    out.append("</span>");
                } else if (Xml.is(c, W, "ins") || Xml.is(c, W, "smartTag") || Xml.is(c, W, "fldSimple")
                        || Xml.is(c, W, "customXml") || Xml.is(c, W, "bdo") || Xml.is(c, W, "dir")) {
                    inline(c, out);
                } else if (Xml.is(c, W, "sdt")) {
                    Element content = Xml.child(c, W, "sdtContent");
                    if (content != null) {
                        inline(content, out);
                    }
                }
            }
        }

        void run(Element r, StringBuilder out) {
            Element rPr = Xml.child(r, W, "rPr");
            Props rp = new Props();
            styles.applyStyle(rp, wval(Xml.child(rPr, W, "rStyle")));
            parseRPr(rPr, rp);
            if (Boolean.TRUE.equals(rp.hidden)) {
                return;
            }
            StringBuilder content = new StringBuilder();
            runContent(r, content);
            if (content.length() == 0) {
                return;
            }
            String css = runCss(rp);
            if (css.isEmpty()) {
                out.append(content);
            } else {
                out.append("<span style=\"").append(css).append("\">").append(content).append("</span>");
            }
        }

        void runContent(Element r, StringBuilder out) {
            for (Element c : Xml.children(r)) {
                if (Xml.is(c, W, "t")) {
                    out.append(Html.esc(c.getTextContent()));
                } else if (Xml.is(c, W, "tab") || Xml.is(c, W, "ptab")) {
                    out.append("<span class=\"tab\"></span>");
                } else if (Xml.is(c, W, "br")) {
                    String type = c.getAttributeNS(W, "type");
                    out.append("page".equals(type) ? "<hr class=\"pb\">" : "<br>");
                } else if (Xml.is(c, W, "cr")) {
                    out.append("<br>");
                } else if (Xml.is(c, W, "noBreakHyphen")) {
                    out.append('\u2011');
                } else if (Xml.is(c, W, "drawing")) {
                    drawing(c, out);
                } else if (Xml.is(c, W, "pict")) {
                    pict(c, out);
                } else if (Xml.is(c, MC, "AlternateContent")) {
                    Element choice = Xml.child(c, MC, "Choice");
                    if (choice != null) {
                        runContent(choice, out);
                    }
                }
            }
        }

        void drawing(Element drawing, StringBuilder out) {
            List<Element> blips = Xml.descendants(drawing, A, "blip");
            String part = null;
            for (Element b : blips) {
                String id = b.getAttributeNS(R, "embed");
                if (id != null && !id.isEmpty()) {
                    part = rels.get(id);
                    break;
                }
            }
            List<Element> extents = Xml.descendants(drawing, WP, "extent");
            Double widthPx = null;
            if (!extents.isEmpty()) {
                try {
                    widthPx = Long.parseLong(extents.get(0).getAttribute("cx")) / 9525.0;
                } catch (NumberFormatException ignored) {
                    // размер не обязателен
                }
            }
            List<Element> props = Xml.descendants(drawing, WP, "docPr");
            String alt = props.isEmpty() ? "" : props.get(0).getAttribute("descr");
            image(part, widthPx, alt, out);
        }

        void pict(Element pict, StringBuilder out) {
            for (Element img : Xml.descendants(pict, V, "imagedata")) {
                String id = img.getAttributeNS(R, "id");
                if (id != null && !id.isEmpty()) {
                    image(rels.get(id), null, "", out);
                    return;
                }
            }
        }

        void image(String part, Double widthPx, String alt, StringBuilder out) {
            if (part == null || part.isEmpty() || !pkg.has(part) || Html.imageMime(part) == null) {
                out.append("<span class=\"noimg\">[изображение]</span>");
                return;
            }
            out.append("<img src=\"").append(Html.mediaUrl(part)).append("\" alt=\"").append(Html.esc(alt)).append('"');
            if (widthPx != null && widthPx > 0) {
                out.append(" style=\"width:").append(Html.num(widthPx)).append("px\"");
            }
            out.append('>');
        }

        // ---------------------------------------------------------- таблица

        private static final class Cell {
            Element tc;
            int col;
            int span = 1;
            String vmerge;
            int rowspan = 1;
            boolean skip;
        }

        void table(Element tbl, StringBuilder sb) {
            Element tblPr = Xml.child(tbl, W, "tblPr");
            boolean bordered = hasBorders(Xml.child(tblPr, W, "tblBorders"))
                    || styles.tableBordered(wval(Xml.child(tblPr, W, "tblStyle")));
            List<Element> rows = Xml.children(tbl, W, "tr");
            List<List<Cell>> grid = new ArrayList<>();
            for (Element tr : rows) {
                List<Cell> cells = new ArrayList<>();
                int col = 0;
                Element trPr = Xml.child(tr, W, "trPr");
                String before = wval(Xml.child(trPr, W, "gridBefore"));
                if (before != null) {
                    col += parseInt(before, 0);
                }
                for (Element tc : Xml.children(tr, W, "tc")) {
                    Cell cell = new Cell();
                    cell.tc = tc;
                    cell.col = col;
                    Element tcPr = Xml.child(tc, W, "tcPr");
                    String span = wval(Xml.child(tcPr, W, "gridSpan"));
                    cell.span = Math.max(1, parseInt(span, 1));
                    Element vm = Xml.child(tcPr, W, "vMerge");
                    if (vm != null) {
                        String v = wval(vm);
                        cell.vmerge = (v == null || v.isEmpty() || "continue".equals(v)) ? "continue" : "restart";
                    }
                    col += cell.span;
                    cells.add(cell);
                }
                grid.add(cells);
            }
            for (int r = 0; r < grid.size(); r++) {
                for (Cell cell : grid.get(r)) {
                    if (!"restart".equals(cell.vmerge)) {
                        continue;
                    }
                    for (int j = r + 1; j < grid.size(); j++) {
                        Cell below = cellAt(grid.get(j), cell.col);
                        if (below != null && "continue".equals(below.vmerge)) {
                            cell.rowspan++;
                            below.skip = true;
                        } else {
                            break;
                        }
                    }
                }
            }

            sb.append("<div style=\"overflow-x:auto\"><table").append(bordered ? " class=\"b\"" : "").append("><tbody>");
            for (List<Cell> row : grid) {
                sb.append("<tr>");
                for (Cell cell : row) {
                    if (cell.skip) {
                        continue;
                    }
                    Element tcPr = Xml.child(cell.tc, W, "tcPr");
                    StringBuilder css = new StringBuilder();
                    Element tcW = Xml.child(tcPr, W, "tcW");
                    if (tcW != null) {
                        int w = parseInt(tcW.getAttributeNS(W, "w"), 0);
                        String type = tcW.getAttributeNS(W, "type");
                        if (w > 0 && "dxa".equals(type)) {
                            css.append("width:").append(Html.num(w / 20.0)).append("pt;");
                        } else if (w > 0 && "pct".equals(type)) {
                            css.append("width:").append(Html.num(w / 50.0)).append("%;");
                        }
                    }
                    Element shd = Xml.child(tcPr, W, "shd");
                    String fill = shd == null ? null : shd.getAttributeNS(W, "fill");
                    if (Html.isHexColor(fill)) {
                        css.append("background:#").append(fill).append(';');
                    }
                    css.append(cellBorders(Xml.child(tcPr, W, "tcBorders")));
                    sb.append("<td");
                    if (cell.span > 1) sb.append(" colspan=\"").append(cell.span).append('"');
                    if (cell.rowspan > 1) sb.append(" rowspan=\"").append(cell.rowspan).append('"');
                    if (css.length() > 0) sb.append(" style=\"").append(css).append('"');
                    sb.append('>');
                    blocks(cell.tc, sb);
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }

        private static Cell cellAt(List<Cell> row, int col) {
            for (Cell c : row) {
                if (c.col == col) {
                    return c;
                }
            }
            return null;
        }

        private static String cellBorders(Element tcBorders) {
            if (tcBorders == null) {
                return "";
            }
            StringBuilder css = new StringBuilder();
            String[][] sides = {{"top", "top"}, {"left", "left"}, {"start", "left"}, {"bottom", "bottom"},
                    {"right", "right"}, {"end", "right"}};
            for (String[] side : sides) {
                Element e = Xml.child(tcBorders, W, side[0]);
                if (e != null && borderVisible(e)) {
                    String color = e.getAttributeNS(W, "color");
                    css.append("border-").append(side[1]).append(":1px solid #")
                            .append(Html.isHexColor(color) ? color : "888888").append(';');
                }
            }
            return css.toString();
        }
    }

    private static boolean hasBorders(Element borders) {
        if (borders == null) {
            return false;
        }
        for (Element e : Xml.children(borders)) {
            if (borderVisible(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean borderVisible(Element e) {
        String v = e.getAttributeNS(W, "val");
        return v != null && !v.isEmpty() && !"nil".equals(v) && !"none".equals(v);
    }

    // ------------------------------------------------------------------ свойства

    /** Свойства абзаца и текста; null — «не задано» (наследуется). */
    static final class Props {
        Boolean bold, italic, underline, strike, hidden, smallCaps, caps;
        String color, font, highlight, shade, align, vertAlign;
        Double size, indLeft, indRight, firstLine, hanging, before, after, lineMult;
        Integer numId, ilvl;

        void apply(Props o) {
            if (o.bold != null) bold = o.bold;
            if (o.italic != null) italic = o.italic;
            if (o.underline != null) underline = o.underline;
            if (o.strike != null) strike = o.strike;
            if (o.hidden != null) hidden = o.hidden;
            if (o.smallCaps != null) smallCaps = o.smallCaps;
            if (o.caps != null) caps = o.caps;
            if (o.color != null) color = o.color;
            if (o.font != null) font = o.font;
            if (o.highlight != null) highlight = o.highlight;
            if (o.shade != null) shade = o.shade;
            if (o.align != null) align = o.align;
            if (o.vertAlign != null) vertAlign = o.vertAlign;
            if (o.size != null) size = o.size;
            if (o.indLeft != null) indLeft = o.indLeft;
            if (o.indRight != null) indRight = o.indRight;
            if (o.firstLine != null) { firstLine = o.firstLine; hanging = null; }
            if (o.hanging != null) { hanging = o.hanging; firstLine = null; }
            if (o.before != null) before = o.before;
            if (o.after != null) after = o.after;
            if (o.lineMult != null) lineMult = o.lineMult;
            if (o.numId != null) numId = o.numId;
            if (o.ilvl != null) ilvl = o.ilvl;
        }
    }

    static String wval(Element e) {
        if (e == null) {
            return null;
        }
        if (e.hasAttributeNS(W, "val")) {
            return e.getAttributeNS(W, "val");
        }
        return e.hasAttribute("val") ? e.getAttribute("val") : null;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double twips(Element e, String attr) {
        if (e == null || !e.hasAttributeNS(W, attr)) {
            return null;
        }
        try {
            return Double.parseDouble(e.getAttributeNS(W, attr)) / 20.0;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Булево свойство Word: элемент без значения означает «включено». */
    private static Boolean toggle(Element e) {
        if (e == null) {
            return null;
        }
        String v = wval(e);
        if (v == null || v.isEmpty()) {
            return Boolean.TRUE;
        }
        return !("0".equals(v) || "false".equals(v) || "off".equals(v));
    }

    static void parseRPr(Element rPr, Props p) {
        if (rPr == null) {
            return;
        }
        Boolean b = toggle(Xml.child(rPr, W, "b"));
        if (b != null) p.bold = b;
        Boolean i = toggle(Xml.child(rPr, W, "i"));
        if (i != null) p.italic = i;
        Boolean s = toggle(Xml.child(rPr, W, "strike"));
        if (s == null) s = toggle(Xml.child(rPr, W, "dstrike"));
        if (s != null) p.strike = s;
        Boolean h = toggle(Xml.child(rPr, W, "vanish"));
        if (h != null) p.hidden = h;
        Boolean sc = toggle(Xml.child(rPr, W, "smallCaps"));
        if (sc != null) p.smallCaps = sc;
        Boolean cp = toggle(Xml.child(rPr, W, "caps"));
        if (cp != null) p.caps = cp;
        Element u = Xml.child(rPr, W, "u");
        if (u != null) {
            String v = wval(u);
            p.underline = v == null || !"none".equals(v);
        }
        Element color = Xml.child(rPr, W, "color");
        if (color != null) {
            String v = wval(color);
            if (Html.isHexColor(v)) p.color = v;
        }
        Element sz = Xml.child(rPr, W, "sz");
        if (sz != null) {
            try {
                p.size = Double.parseDouble(wval(sz)) / 2.0;
            } catch (Exception ignored) {
                // ok
            }
        }
        Element fonts = Xml.child(rPr, W, "rFonts");
        if (fonts != null) {
            String f = fonts.getAttributeNS(W, "ascii");
            if (f == null || f.isEmpty()) f = fonts.getAttributeNS(W, "hAnsi");
            if (f != null && !f.isEmpty()) p.font = f;
        }
        Element hl = Xml.child(rPr, W, "highlight");
        if (hl != null) {
            p.highlight = highlightColor(wval(hl));
        }
        Element shd = Xml.child(rPr, W, "shd");
        if (shd != null && Html.isHexColor(shd.getAttributeNS(W, "fill"))) {
            p.shade = shd.getAttributeNS(W, "fill");
        }
        Element va = Xml.child(rPr, W, "vertAlign");
        if (va != null) {
            String v = wval(va);
            p.vertAlign = "superscript".equals(v) ? "super" : "subscript".equals(v) ? "sub" : null;
        }
    }

    static void parsePPr(Element pPr, Props p) {
        if (pPr == null) {
            return;
        }
        Element jc = Xml.child(pPr, W, "jc");
        if (jc != null) {
            String v = wval(jc);
            if ("center".equals(v)) p.align = "center";
            else if ("right".equals(v) || "end".equals(v)) p.align = "right";
            else if ("both".equals(v) || "distribute".equals(v)) p.align = "justify";
            else if ("left".equals(v) || "start".equals(v)) p.align = "left";
        }
        Element ind = Xml.child(pPr, W, "ind");
        if (ind != null) {
            Double left = twips(ind, "left");
            if (left == null) left = twips(ind, "start");
            if (left != null) p.indLeft = left;
            Double right = twips(ind, "right");
            if (right == null) right = twips(ind, "end");
            if (right != null) p.indRight = right;
            Double first = twips(ind, "firstLine");
            if (first != null) p.firstLine = first;
            Double hang = twips(ind, "hanging");
            if (hang != null) p.hanging = hang;
        }
        Element spacing = Xml.child(pPr, W, "spacing");
        if (spacing != null) {
            Double before = twips(spacing, "before");
            if (before != null) p.before = before;
            Double after = twips(spacing, "after");
            if (after != null) p.after = after;
            String rule = spacing.getAttributeNS(W, "lineRule");
            if (spacing.hasAttributeNS(W, "line") && (rule == null || rule.isEmpty() || "auto".equals(rule))) {
                try {
                    p.lineMult = Double.parseDouble(spacing.getAttributeNS(W, "line")) / 240.0;
                } catch (NumberFormatException ignored) {
                    // ok
                }
            }
        }
        Element shd = Xml.child(pPr, W, "shd");
        if (shd != null && Html.isHexColor(shd.getAttributeNS(W, "fill"))) {
            p.shade = shd.getAttributeNS(W, "fill");
        }
        Element numPr = Xml.child(pPr, W, "numPr");
        if (numPr != null) {
            String id = wval(Xml.child(numPr, W, "numId"));
            String lvl = wval(Xml.child(numPr, W, "ilvl"));
            if (id != null) p.numId = parseInt(id, 0);
            if (lvl != null) p.ilvl = parseInt(lvl, 0);
        }
    }

    private static String highlightColor(String name) {
        if (name == null) return null;
        switch (name) {
            case "yellow": return "ffff00";
            case "green": return "00ff00";
            case "cyan": return "00ffff";
            case "magenta": return "ff00ff";
            case "blue": return "0000ff";
            case "red": return "ff0000";
            case "darkBlue": return "000080";
            case "darkCyan": return "008080";
            case "darkGreen": return "008000";
            case "darkMagenta": return "800080";
            case "darkRed": return "800000";
            case "darkYellow": return "808000";
            case "darkGray": return "808080";
            case "lightGray": return "c0c0c0";
            case "black": return "000000";
            case "white": return "ffffff";
            default: return null;
        }
    }

    /** CSS для свойств текста (шрифт, размер, начертание, цвет). */
    static String runCss(Props p) {
        StringBuilder css = new StringBuilder();
        if (p.font != null) {
            String f = Html.fontFamily(p.font);
            if (f != null) css.append("font-family:").append(f).append(';');
        }
        if (p.size != null) css.append("font-size:").append(Html.num(p.size)).append("pt;");
        if (p.bold != null) css.append("font-weight:").append(p.bold ? "bold" : "normal").append(';');
        if (p.italic != null) css.append("font-style:").append(p.italic ? "italic" : "normal").append(';');
        if (p.underline != null || p.strike != null) {
            boolean u = Boolean.TRUE.equals(p.underline);
            boolean s = Boolean.TRUE.equals(p.strike);
            css.append("text-decoration:").append(u && s ? "underline line-through" : u ? "underline" : s ? "line-through" : "none").append(';');
        }
        if (p.color != null) css.append("color:#").append(p.color).append(';');
        if (p.highlight != null) css.append("background:#").append(p.highlight).append(';');
        if (p.vertAlign != null) css.append("vertical-align:").append(p.vertAlign).append(";font-size:smaller;");
        if (Boolean.TRUE.equals(p.caps)) css.append("text-transform:uppercase;");
        if (Boolean.TRUE.equals(p.smallCaps)) css.append("font-variant:small-caps;");
        return css.toString();
    }

    // ------------------------------------------------------------------ стили

    static final class Styles {
        private static final class Def {
            String id, name, basedOn, type;
            boolean isDefault;
            Props props = new Props();
            boolean tableBorders;
        }

        private final Map<String, Def> byId = new HashMap<>();
        private final Props defaults = new Props();
        private String defaultParagraph;

        Styles(Document doc) {
            if (doc == null) {
                return;
            }
            Element dd = firstOf(doc, "docDefaults");
            if (dd != null) {
                Element rd = Xml.child(dd, W, "rPrDefault");
                Element pd = Xml.child(dd, W, "pPrDefault");
                if (rd != null) parseRPr(Xml.child(rd, W, "rPr"), defaults);
                if (pd != null) parsePPr(Xml.child(pd, W, "pPr"), defaults);
            }
            for (Element s : Xml.descendants(doc, W, "style")) {
                Def d = new Def();
                d.id = s.getAttributeNS(W, "styleId");
                d.type = s.getAttributeNS(W, "type");
                d.isDefault = "1".equals(s.getAttributeNS(W, "default")) || "true".equals(s.getAttributeNS(W, "default"));
                d.name = wval(Xml.child(s, W, "name"));
                d.basedOn = wval(Xml.child(s, W, "basedOn"));
                parsePPr(Xml.child(s, W, "pPr"), d.props);
                parseRPr(Xml.child(s, W, "rPr"), d.props);
                Element tblPr = Xml.child(s, W, "tblPr");
                d.tableBorders = tblPr != null && hasBorders(Xml.child(tblPr, W, "tblBorders"));
                if (d.id != null && !d.id.isEmpty()) {
                    byId.put(d.id, d);
                    if ("paragraph".equals(d.type) && d.isDefault) {
                        defaultParagraph = d.id;
                    }
                }
            }
        }

        private static Element firstOf(Document doc, String local) {
            List<Element> l = Xml.descendants(doc, W, local);
            return l.isEmpty() ? null : l.get(0);
        }

        /** Стили цепочки наследования применяются от базового к производному. */
        void applyStyle(Props target, String styleId) {
            applyChain(target, styleId, 0);
        }

        private void applyChain(Props target, String styleId, int depth) {
            if (styleId == null || depth > 12) {
                return;
            }
            Def d = byId.get(styleId);
            if (d == null) {
                return;
            }
            applyChain(target, d.basedOn, depth + 1);
            target.apply(d.props);
        }

        Props paragraphProps(String styleId, Element pPr) {
            Props p = new Props();
            p.apply(defaults);
            applyStyle(p, defaultParagraph);
            if (styleId != null && !styleId.equals(defaultParagraph)) {
                applyStyle(p, styleId);
            }
            Props direct = new Props();
            parsePPr(pPr, direct);
            p.apply(direct);
            return p;
        }

        boolean tableBordered(String styleId) {
            for (int depth = 0; styleId != null && depth < 12; depth++) {
                Def d = byId.get(styleId);
                if (d == null) {
                    return false;
                }
                if (d.tableBorders) {
                    return true;
                }
                styleId = d.basedOn;
            }
            return false;
        }

        /** Стиль страницы: общий размер шрифта и гарнитура документа. */
        String pageCss() {
            Props p = new Props();
            p.font = defaults.font;
            p.size = defaults.size;
            return runCss(p);
        }
    }

    // ------------------------------------------------------------------ нумерация

    static final class Numbering {
        static final class Level {
            String fmt = "decimal";
            String text = "%1.";
            int start = 1;
            double left = 36;
            double hanging = 18;
        }

        static final class Marker {
            String text;
            double left;
            double hanging;
        }

        private final Map<Integer, Integer> numToAbstract = new HashMap<>();
        private final Map<Integer, Map<Integer, Level>> levels = new HashMap<>();
        private final Map<Integer, int[]> counters = new HashMap<>();

        Numbering(Document doc) {
            if (doc == null) {
                return;
            }
            for (Element an : Xml.descendants(doc, W, "abstractNum")) {
                int id = parseInt(an.getAttributeNS(W, "abstractNumId"), -1);
                Map<Integer, Level> map = new HashMap<>();
                for (Element lvl : Xml.children(an, W, "lvl")) {
                    Level l = new Level();
                    int ilvl = parseInt(lvl.getAttributeNS(W, "ilvl"), 0);
                    String start = wval(Xml.child(lvl, W, "start"));
                    if (start != null) l.start = parseInt(start, 1);
                    String fmt = wval(Xml.child(lvl, W, "numFmt"));
                    if (fmt != null) l.fmt = fmt;
                    String text = wval(Xml.child(lvl, W, "lvlText"));
                    if (text != null) l.text = text;
                    Element ind = Xml.child(Xml.child(lvl, W, "pPr"), W, "ind");
                    if (ind != null) {
                        Double left = twips(ind, "left");
                        if (left == null) left = twips(ind, "start");
                        if (left != null) l.left = left;
                        Double hang = twips(ind, "hanging");
                        l.hanging = hang != null ? hang : 18;
                    }
                    map.put(ilvl, l);
                }
                levels.put(id, map);
            }
            for (Element num : Xml.descendants(doc, W, "num")) {
                int numId = parseInt(num.getAttributeNS(W, "numId"), -1);
                String abs = wval(Xml.child(num, W, "abstractNumId"));
                if (abs != null) {
                    numToAbstract.put(numId, parseInt(abs, -1));
                }
            }
        }

        /** Следующий номер списка; null — абзац не нумерован. Счётчики общие для всех numId одного abstractNum. */
        Marker next(int numId, int ilvl) {
            if (numId == 0 || ilvl < 0 || ilvl > 8) {
                return null;
            }
            Integer abs = numToAbstract.get(numId);
            Map<Integer, Level> map = abs == null ? null : levels.get(abs);
            if (map == null) {
                return null;
            }
            Level level = map.get(ilvl);
            if (level == null) {
                return null;
            }
            Marker m = new Marker();
            m.left = level.left;
            m.hanging = level.hanging;
            if ("bullet".equals(level.fmt)) {
                m.text = ilvl == 0 ? "\u2022" : ilvl == 1 ? "\u25E6" : "\u25AA";
                return m;
            }
            int[] cnt = counters.get(abs);
            if (cnt == null) {
                cnt = new int[9];
                counters.put(abs, cnt);
            }
            cnt[ilvl] = cnt[ilvl] == 0 ? level.start : cnt[ilvl] + 1;
            for (int i = ilvl + 1; i < 9; i++) {
                cnt[i] = 0;
            }
            if ("none".equals(level.fmt)) {
                m.text = "";
                return m;
            }
            StringBuilder out = new StringBuilder();
            String pattern = level.text;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '%' && i + 1 < pattern.length() && Character.isDigit(pattern.charAt(i + 1))) {
                    int k = pattern.charAt(i + 1) - '1';
                    Level kl = map.get(k);
                    int value = k >= 0 && k < 9 ? Math.max(cnt[k], kl == null ? 1 : kl.start) : 1;
                    out.append(format(value, kl == null ? "decimal" : kl.fmt));
                    i++;
                } else {
                    out.append(c);
                }
            }
            m.text = out.toString();
            return m;
        }

        private static String format(int n, String fmt) {
            switch (fmt) {
                case "lowerLetter": return letters(n, false);
                case "upperLetter": return letters(n, true);
                case "lowerRoman": return roman(n).toLowerCase(Locale.ROOT);
                case "upperRoman": return roman(n);
                case "decimalZero": return n < 10 ? "0" + n : Integer.toString(n);
                default: return Integer.toString(n);
            }
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
    }

    // ------------------------------------------------------------------ правка

    private static Element paragraphById(Document doc, String id) {
        if (id == null || !id.startsWith("p")) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
        List<Element> all = Xml.descendants(doc, W, "p");
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /** Элементы w:t абзаца, не заходя во вложенные абзацы (текстовые поля). */
    private static List<Element> textRuns(Element paragraph) {
        List<Element> out = new ArrayList<>();
        collectRuns(paragraph, out);
        return out;
    }

    private static void collectRuns(Element node, List<Element> out) {
        for (Element c : Xml.children(node)) {
            if (Xml.is(c, W, "p")) {
                continue;
            }
            if (Xml.is(c, W, "t")) {
                out.add(c);
            } else {
                collectRuns(c, out);
            }
        }
    }

    @Override
    public String textAt(byte[] source, String id) {
        Document doc = ZipPackage.read(source).xml(MAIN);
        Element p = doc == null ? null : paragraphById(doc, id);
        return p == null ? null : TextSplice.join(textRuns(p));
    }

    @Override
    public String displayAt(byte[] source, String id) {
        return textAt(source, id);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        ZipPackage pkg = ZipPackage.read(source);
        Document doc = pkg.xml(MAIN);
        Element p = doc == null ? null : paragraphById(doc, id);
        if (p == null) {
            throw new DocException("Абзац не найден — возможно, документ изменился");
        }
        String text = TextSplice.singleLine(newText);
        List<Element> runs = textRuns(p);
        if (!TextSplice.apply(runs, text, true)) {
            if (!text.isEmpty()) {
                Element r = doc.createElementNS(W, "w:r");
                Element t = doc.createElementNS(W, "w:t");
                t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
                t.setTextContent(text);
                r.appendChild(t);
                p.appendChild(r);
            }
        }
        pkg.putXml(MAIN, doc);
        return pkg.toBytes();
    }
}
