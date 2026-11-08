package dev.ronova.docforge.odf;

import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.util.IdentityHashMap;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Рендер текстового потока ODF (абзацы, списки, таблицы, картинки) в HTML — общий для ODT, ODP и ячеек ODS. */
final class OdfBlocks {
    private final ZipPackage pkg;
    private final OdfStyles styles;
    private final IdentityHashMap<Element, Integer> ids;

    OdfBlocks(ZipPackage pkg, OdfStyles styles, IdentityHashMap<Element, Integer> ids) {
        this.pkg = pkg;
        this.styles = styles;
        this.ids = ids;
    }

    private static boolean skippedBlock(String name) {
        return name.contains("decls") || name.contains("-source") || name.contains("tracked") || "soft-page-break".equals(name)
                || "forms".equals(name) || "bookmark".equals(name) || "bookmark-start".equals(name) || "bookmark-end".equals(name)
                || "note".equals(name) || "section-source".equals(name);
    }

    void blocks(Element container, StringBuilder out) {
        for (Element c : Xml.children(container)) {
            String ns = c.getNamespaceURI();
            String name = c.getLocalName();
            if (Odf.TEXT.equals(ns)) {
                if ("p".equals(name) || "h".equals(name)) {
                    paragraph(c, out);
                } else if ("list".equals(name)) {
                    list(c, out);
                } else if (!skippedBlock(name)) {
                    blocks(c, out);
                }
            } else if (Odf.TABLE.equals(ns) && "table".equals(name)) {
                table(c, out);
            } else if (Odf.DRAW.equals(ns) && "frame".equals(name)) {
                StringBuilder img = new StringBuilder();
                image(c, img);
                out.append("<p>").append(img).append("</p>");
            }
        }
    }

    void paragraph(Element p, StringBuilder out) {
        String style = Odf.a(p, Odf.TEXT, "style-name");
        String css = styles.paraCss(style);
        StringBuilder inner = new StringBuilder();
        inline(p, inner);
        out.append("<p");
        Integer id = ids.get(p);
        if (id != null) out.append(" data-id=\"p").append(id).append('"');
        if (!css.isEmpty()) out.append(" style=\"").append(Html.esc(css)).append('"');
        out.append('>').append(inner).append("</p>");
    }

    void inline(Element parent, StringBuilder out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                out.append(Html.esc(n.getNodeValue()));
                continue;
            }
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String ns = e.getNamespaceURI();
            String name = e.getLocalName();
            if (Odf.TEXT.equals(ns)) {
                if ("span".equals(name)) {
                    String css = styles.textCss(Odf.a(e, Odf.TEXT, "style-name"));
                    if (css.isEmpty()) {
                        inline(e, out);
                    } else {
                        out.append("<span style=\"").append(Html.esc(css)).append("\">");
                        inline(e, out);
                        out.append("</span>");
                    }
                } else if ("s".equals(name)) {
                    int count = Odf.repeat(e, Odf.TEXT, "c");
                    for (int i = 0; i < count && i < 500; i++) out.append("&nbsp;");
                } else if ("tab".equals(name)) {
                    out.append("<span class=\"tab\"></span>");
                } else if ("line-break".equals(name)) {
                    out.append("<br>");
                } else if ("a".equals(name)) {
                    out.append("<span style=\"color:#0563C1;text-decoration:underline\">");
                    inline(e, out);
                    out.append("</span>");
                } else if (!skippedBlock(name) && !"list".equals(name)) {
                    inline(e, out);
                }
            } else if (Odf.DRAW.equals(ns) && "frame".equals(name)) {
                image(e, out);
            }
        }
    }

    /** Картинка из draw:frame; вложенные текстовые блоки и объекты не рисуются. */
    void image(Element frame, StringBuilder out) {
        Element img = Xml.child(frame, Odf.DRAW, "image");
        if (img == null) return;
        String href = Odf.a(img, Odf.XLINK, "href");
        if (href == null || href.isEmpty() || href.startsWith("http") || !pkg.has(href) || Html.imageMime(href) == null) {
            out.append("<span class=\"noimg\">[изображение]</span>");
            return;
        }
        Double w = Odf.toPt(Odf.a(frame, Odf.SVG, "width"));
        out.append("<img src=\"").append(Html.mediaUrl(href)).append("\" alt=\"\"");
        if (w != null && w > 0) out.append(" style=\"width:").append(Html.num(w)).append("pt\"");
        out.append('>');
    }

    void list(Element list, StringBuilder out) {
        String tag = styles.listTag(Odf.a(list, Odf.TEXT, "style-name"));
        out.append('<').append(tag).append('>');
        for (Element item : Xml.children(list)) {
            if (Odf.TEXT.equals(item.getNamespaceURI())
                    && ("list-item".equals(item.getLocalName()) || "list-header".equals(item.getLocalName()))) {
                out.append("<li>");
                blocks(item, out);
                out.append("</li>");
            }
        }
        out.append("</").append(tag).append('>');
    }

    void table(Element table, StringBuilder out) {
        out.append("<div style=\"overflow-x:auto\"><table style=\"width:100%\"><colgroup>");
        List<String> columnDefaults = new java.util.ArrayList<>();
        for (Element col : columns(table)) {
            int rep = Math.min(Odf.repeat(col, Odf.TABLE, "number-columns-repeated"), 60);
            Double w = styles.columnWidth(Odf.a(col, Odf.TABLE, "style-name"));
            for (int i = 0; i < rep; i++) {
                out.append("<col").append(w != null ? " style=\"width:" + Html.num(w) + "pt\"" : "").append('>');
                columnDefaults.add(Odf.a(col, Odf.TABLE, "default-cell-style-name"));
            }
        }
        out.append("</colgroup><tbody>");
        rows(table, out, columnDefaults);
        out.append("</tbody></table></div>");
    }

    private static void collectColumns(Element parent, List<Element> out) {
        for (Element c : Xml.children(parent)) {
            if (!Odf.TABLE.equals(c.getNamespaceURI())) continue;
            if ("table-column".equals(c.getLocalName())) out.add(c);
            else if ("table-columns".equals(c.getLocalName()) || "table-header-columns".equals(c.getLocalName())
                    || "table-column-group".equals(c.getLocalName())) collectColumns(c, out);
        }
    }

    private static List<Element> columns(Element table) {
        List<Element> out = new java.util.ArrayList<>();
        collectColumns(table, out);
        return out;
    }

    private void rows(Element parent, StringBuilder out, List<String> columnDefaults) {
        for (Element c : Xml.children(parent)) {
            if (!Odf.TABLE.equals(c.getNamespaceURI())) continue;
            String name = c.getLocalName();
            if ("table-row".equals(name)) {
                row(c, out, columnDefaults);
            } else if ("table-header-rows".equals(name) || "table-rows".equals(name) || "table-row-group".equals(name)) {
                rows(c, out, columnDefaults);
            }
        }
    }

    private void row(Element row, StringBuilder out, List<String> columnDefaults) {
        String rowDefault = Odf.a(row, Odf.TABLE, "default-cell-style-name");
        Double rowHeight = styles.rowHeight(Odf.a(row, Odf.TABLE, "style-name"));
        out.append("<tr").append(rowHeight == null ? "" : " style=\"height:" + Html.num(rowHeight) + "pt\"").append('>');
        int column = 0;
        {
            for (Element cell : Xml.children(row)) {
                if (!Odf.TABLE.equals(cell.getNamespaceURI())) continue;
                boolean covered = "covered-table-cell".equals(cell.getLocalName());
                if (!covered && !"table-cell".equals(cell.getLocalName())) continue;
                int startColumn = column;
                column += Odf.repeat(cell, Odf.TABLE, "number-columns-repeated");
                if (covered) continue;
                int span = Odf.repeat(cell, Odf.TABLE, "number-columns-spanned");
                int rspan = Odf.repeat(cell, Odf.TABLE, "number-rows-spanned");
                int crep = Math.min(Odf.repeat(cell, Odf.TABLE, "number-columns-repeated"), 60);
                for (int k = 0; k < crep; k++) {
                    out.append("<td");
                    if (span > 1) out.append(" colspan=\"").append(span).append('"');
                    if (rspan > 1) out.append(" rowspan=\"").append(rspan).append('"');
                    String styleName = Odf.a(cell, Odf.TABLE, "style-name");
                    if (styleName == null) styleName = rowDefault;
                    if (styleName == null && startColumn + k < columnDefaults.size()) styleName = columnDefaults.get(startColumn + k);
                    String css = styles.cellCss(styleName);
                    if (!css.isEmpty()) out.append(" style=\"").append(Html.esc(css)).append('"');
                    out.append('>');
                    blocks(cell, out);
                    out.append("</td>");
                }
            }
        }
        out.append("</tr>");
    }
}
