package dev.ronova.docforge.odf;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.core.DocFormat;
import dev.ronova.docforge.core.OfficeEngine;
import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Просмотр и правка .ods (LibreOffice Calc). Адрес ячейки в id: s{лист}:{строка}:{колонка}, с нуля. */
public final class OdsEngine implements OfficeEngine {

    private static final int MAX_COLS = 100;
    private static final int MAX_ROWS = 3000;
    private static final int MAX_CELLS = 60000;
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)([eE][+-]?\\d+)?$");
    private static final String[] VALUE_ATTRS = {"value-type", "value", "string-value", "date-value", "time-value",
            "boolean-value", "boolean", "currency"};

    @Override
    public DocFormat format() {
        return DocFormat.ODS;
    }

    // ------------------------------------------------------------------ модель листа

    private static final class Cell {
        Element el;
        int colspan = 1;
        int rowspan = 1;
        boolean covered;
    }

    private static final class Grid {
        final Map<Long, Cell> cells = new HashMap<>();
        final Set<Long> covered = new HashSet<>();
        final Map<Integer, Double> rowHeights = new HashMap<>();
        final Set<Integer> hiddenRows = new HashSet<>();
        final List<Double> colWidths = new ArrayList<>();
        final List<String> colStyles = new ArrayList<>();
        final List<Boolean> colHidden = new ArrayList<>();
        int maxRow = -1;
        int maxCol = -1;
    }

    private static long key(int row, int col) {
        return (long) row * 100000L + col;
    }

    private static boolean is(Element e, String local) {
        return Odf.TABLE.equals(e.getNamespaceURI()) && local.equals(e.getLocalName());
    }

    private static List<Element> sheets(Document content) {
        List<Element> out = new ArrayList<>();
        List<Element> bodies = Xml.descendants(content, Odf.OFFICE, "spreadsheet");
        if (bodies.isEmpty()) return out;
        for (Element t : Xml.children(bodies.get(0), Odf.TABLE, "table")) out.add(t);
        return out;
    }

    /** Строки таблицы в порядке следования, включая строки внутри групп и заголовков. */
    private static void collectRows(Element parent, List<Element> out) {
        for (Element c : Xml.children(parent)) {
            if (!Odf.TABLE.equals(c.getNamespaceURI())) continue;
            if ("table-row".equals(c.getLocalName())) out.add(c);
            else if ("table-header-rows".equals(c.getLocalName()) || "table-rows".equals(c.getLocalName())
                    || "table-row-group".equals(c.getLocalName())) collectRows(c, out);
        }
    }

    private static void collectColumns(Element parent, List<Element> out) {
        for (Element c : Xml.children(parent)) {
            if (!Odf.TABLE.equals(c.getNamespaceURI())) continue;
            if ("table-column".equals(c.getLocalName())) out.add(c);
            else if ("table-header-columns".equals(c.getLocalName()) || "table-columns".equals(c.getLocalName())
                    || "table-column-group".equals(c.getLocalName())) collectColumns(c, out);
        }
    }

    private static boolean isEmptyCell(Element cell) {
        if (Odf.a(cell, Odf.OFFICE, "value-type") != null) return false;
        return Xml.child(cell, Odf.TEXT, "p") == null && Xml.child(cell, Odf.DRAW, "frame") == null;
    }

    private Grid grid(Element table, OdfStyles styles) {
        Grid g = new Grid();
        List<Element> cols = new ArrayList<>();
        collectColumns(table, cols);
        for (Element col : cols) {
            int rep = Math.min(Odf.repeat(col, Odf.TABLE, "number-columns-repeated"), MAX_COLS);
            String style = Odf.a(col, Odf.TABLE, "style-name");
            Double w = styles.columnWidth(style);
            boolean hidden = "collapse".equals(Odf.a(col, Odf.TABLE, "visibility"));
            for (int i = 0; i < rep && g.colWidths.size() < MAX_COLS; i++) {
                g.colWidths.add(w);
                g.colStyles.add(Odf.a(col, Odf.TABLE, "default-cell-style-name"));
                g.colHidden.add(hidden);
            }
        }
        List<Element> rows = new ArrayList<>();
        collectRows(table, rows);
        int r = 0;
        for (Element row : rows) {
            int rep = Odf.repeat(row, Odf.TABLE, "number-rows-repeated");
            if (r >= MAX_ROWS) break;
            List<Object[]> recs = new ArrayList<>();
            int c = 0;
            boolean any = false;
            for (Element cell : Xml.children(row)) {
                if (!Odf.TABLE.equals(cell.getNamespaceURI())) continue;
                boolean covered = "covered-table-cell".equals(cell.getLocalName());
                if (!covered && !"table-cell".equals(cell.getLocalName())) continue;
                int crep = Odf.repeat(cell, Odf.TABLE, "number-columns-repeated");
                boolean empty = !covered && isEmptyCell(cell);
                if (!empty && c < MAX_COLS) {
                    for (int k = 0; k < crep && c + k < MAX_COLS; k++) recs.add(new Object[]{c + k, cell, covered});
                    any = true;
                }
                c += crep;
            }
            if (any) {
                for (int k = 0; k < Math.min(rep, MAX_ROWS - r); k++) {
                    for (Object[] rec : recs) {
                        int col = (Integer) rec[0];
                        Element el = (Element) rec[1];
                        boolean covered = (Boolean) rec[2];
                        if (covered) {
                            g.covered.add(key(r + k, col));
                        } else {
                            Cell cell = new Cell();
                            cell.el = el;
                            cell.colspan = Odf.repeat(el, Odf.TABLE, "number-columns-spanned");
                            cell.rowspan = Odf.repeat(el, Odf.TABLE, "number-rows-spanned");
                            g.cells.put(key(r + k, col), cell);
                            g.maxCol = Math.max(g.maxCol, col + cell.colspan - 1);
                        }
                    }
                    g.maxRow = Math.max(g.maxRow, r + k);
                }
            }
            Double h = styles.rowHeight(Odf.a(row, Odf.TABLE, "style-name"));
            String vis = Odf.a(row, Odf.TABLE, "visibility");
            for (int k = 0; k < Math.min(rep, 50) && r + k < MAX_ROWS; k++) {
                if (h != null) g.rowHeights.put(r + k, h * 96 / 72);
                if ("collapse".equals(vis) || "filter".equals(vis)) g.hiddenRows.add(r + k);
            }
            r += rep;
        }
        return g;
    }

    // ------------------------------------------------------------------ значения

    private static String display(Element cell) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Element p : Xml.children(cell, Odf.TEXT, "p")) {
            if (!first) sb.append('\n');
            sb.append(Odf.plainText(p));
            first = false;
        }
        if (sb.length() == 0) {
            String v = Odf.a(cell, Odf.OFFICE, "value");
            if (v != null) return v;
            String b = Odf.a(cell, Odf.OFFICE, "boolean-value");
            if (b != null) return b.toUpperCase(java.util.Locale.ROOT);
        }
        return sb.toString();
    }

    private static String raw(Element cell) {
        String type = Odf.a(cell, Odf.OFFICE, "value-type");
        String v = Odf.a(cell, Odf.OFFICE, "value");
        if (v != null && ("float".equals(type) || "percentage".equals(type) || "currency".equals(type))) return v;
        return display(cell);
    }

    private static boolean numeric(Element cell) {
        String t = Odf.a(cell, Odf.OFFICE, "value-type");
        return "float".equals(t) || "percentage".equals(t) || "currency".equals(t) || "date".equals(t) || "time".equals(t);
    }

    // ------------------------------------------------------------------ рендер

    @Override
    public String renderHtml(byte[] source) {
        ZipPackage pkg = ZipPackage.read(source);
        Document content = pkg.xml("content.xml");
        if (content == null) {
            throw new DocException("В файле нет content.xml — это не документ OpenDocument");
        }
        OdfStyles styles = new OdfStyles(content, pkg.xml("styles.xml"));
        List<Element> tables = sheets(content);
        if (tables.isEmpty()) {
            throw new DocException("Это не электронная таблица OpenDocument");
        }
        List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            if (!styles.tableHidden(Odf.a(tables.get(i), Odf.TABLE, "style-name"))) visible.add(i);
        }
        if (visible.isEmpty()) visible.add(0);

        StringBuilder body = new StringBuilder();
        if (visible.size() > 1) {
            body.append("<div class=\"tabs\">");
            for (int k = 0; k < visible.size(); k++) {
                String name = Odf.a(tables.get(visible.get(k)), Odf.TABLE, "name");
                body.append("<button data-tab=\"").append(k).append('"').append(k == 0 ? " class=\"on\"" : "").append('>')
                        .append(Html.esc(name)).append("</button>");
            }
            body.append("</div>");
        }
        for (int k = 0; k < visible.size(); k++) {
            body.append("<div class=\"sheetwrap").append(k == 0 ? " first" : "").append("\">");
            renderSheet(tables.get(visible.get(k)), visible.get(k), styles, body);
            body.append("</div>");
        }
        return Html.page(DocFormat.Kind.SHEET, "", body.toString());
    }

    private void renderSheet(Element table, int sheetIndex, OdfStyles styles, StringBuilder out) {
        Grid g = grid(table, styles);
        int cols = Math.min(Math.max(Math.max(g.maxCol + 1, g.colWidths.size() > 0 ? Math.min(g.colWidths.size(), g.maxCol + 3) : 0), 6), MAX_COLS);
        int rows = Math.min(Math.max(g.maxRow + 1, 12), MAX_ROWS);
        if ((long) rows * cols > MAX_CELLS) rows = Math.max(1, MAX_CELLS / cols);
        boolean truncated = g.maxRow + 1 > rows || g.maxCol + 1 > cols;

        double[] widths = new double[cols];
        boolean[] hidden = new boolean[cols];
        double total = 40;
        for (int i = 0; i < cols; i++) {
            Double w = i < g.colWidths.size() ? g.colWidths.get(i) : null;
            widths[i] = (w == null ? 48 : w) * 96 / 72;
            hidden[i] = i < g.colHidden.size() && g.colHidden.get(i);
            if (!hidden[i]) total += widths[i];
        }
        out.append("<table class=\"sheet\" style=\"width:").append(Html.num(total)).append("px\"><colgroup><col style=\"width:40px\">");
        for (int i = 0; i < cols; i++) {
            if (!hidden[i]) out.append("<col style=\"width:").append(Html.num(widths[i])).append("px\">");
        }
        out.append("</colgroup><thead><tr><th class=\"corner rh\"></th>");
        for (int i = 0; i < cols; i++) {
            if (!hidden[i]) out.append("<th>").append(colName(i)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (int r = 0; r < rows; r++) {
            if (g.hiddenRows.contains(r)) continue;
            out.append("<tr");
            Double h = g.rowHeights.get(r);
            if (h != null) out.append(" style=\"height:").append(Html.num(h)).append("px\"");
            out.append("><th class=\"rh\">").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                if (hidden[c] || g.covered.contains(key(r, c))) continue;
                Cell cell = g.cells.get(key(r, c));
                String id = "s" + sheetIndex + ":" + r + ":" + c;
                StringBuilder css = new StringBuilder();
                String text = "";
                String styleName = cell == null ? null : Odf.a(cell.el, Odf.TABLE, "style-name");
                if (styleName == null && c < g.colStyles.size()) styleName = g.colStyles.get(c);
                if (styleName != null) css.append(styles.cellCss(styleName));
                if (cell != null) {
                    text = display(cell.el);
                    if (numeric(cell.el) && (styleName == null || !styles.cellHasAlign(styleName))) css.append("text-align:right;");
                }
                out.append("<td data-id=\"").append(id).append('"');
                if (cell != null) {
                    if (cell.colspan > 1) out.append(" colspan=\"").append(cell.colspan).append('"');
                    if (cell.rowspan > 1) out.append(" rowspan=\"").append(cell.rowspan).append('"');
                }
                if (css.length() > 0) out.append(" style=\"").append(Html.esc(css.toString())).append('"');
                out.append('>').append(Html.esc(text)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table>");
        if (truncated) {
            out.append("<div class=\"note\">Показана часть листа (до ").append(MAX_ROWS).append(" строк и ")
                    .append(MAX_COLS).append(" колонок).</div>");
        }
    }

    private static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ поиск и создание ячейки

    private static final class Target {
        ZipPackage pkg;
        Document content;
        Element table;
        int row;
        int col;
    }

    private static Target resolve(byte[] source, String id) {
        if (id == null || !id.startsWith("s")) return null;
        String[] parts = id.substring(1).split(":");
        if (parts.length != 3) return null;
        try {
            int sheet = Integer.parseInt(parts[0]);
            Target t = new Target();
            t.row = Integer.parseInt(parts[1]);
            t.col = Integer.parseInt(parts[2]);
            t.pkg = ZipPackage.read(source);
            t.content = t.pkg.xml("content.xml");
            if (t.content == null) return null;
            List<Element> tables = sheets(t.content);
            if (sheet < 0 || sheet >= tables.size() || t.row < 0 || t.col < 0) return null;
            t.table = tables.get(sheet);
            return t;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void setRepeat(Element e, String local, int n) {
        if (n <= 1) {
            e.removeAttributeNS(Odf.TABLE, local);
        } else {
            e.setAttributeNS(Odf.TABLE, "table:" + local, Integer.toString(n));
        }
    }

    /**
     * Выделяет из повторяющегося элемента (строки или ячейки) ровно один — на позиции k из rep.
     * Остальное остаётся копиями до и после, чтобы остальные ячейки листа не изменились.
     */
    private static Element isolate(Element el, int k, int rep, String attr) {
        if (rep <= 1) return el;
        Node parent = el.getParentNode();
        if (k > 0) {
            Element before = (Element) el.cloneNode(true);
            setRepeat(before, attr, k);
            parent.insertBefore(before, el);
        }
        Element target = (Element) el.cloneNode(true);
        setRepeat(target, attr, 1);
        parent.insertBefore(target, el);
        int after = rep - k - 1;
        if (after > 0) {
            Element tail = (Element) el.cloneNode(true);
            setRepeat(tail, attr, after);
            parent.insertBefore(tail, el);
        }
        parent.removeChild(el);
        return target;
    }

    private static Element newRow(Document doc, int cellsRepeat) {
        Element row = doc.createElementNS(Odf.TABLE, "table:table-row");
        Element cell = doc.createElementNS(Odf.TABLE, "table:table-cell");
        if (cellsRepeat > 1) cell.setAttributeNS(Odf.TABLE, "table:number-columns-repeated", Integer.toString(cellsRepeat));
        row.appendChild(cell);
        return row;
    }

    private static Element rowAt(Document doc, Element table, int r, boolean create) {
        List<Element> rows = new ArrayList<>();
        collectRows(table, rows);
        int index = 0;
        for (Element row : rows) {
            int rep = Odf.repeat(row, Odf.TABLE, "number-rows-repeated");
            if (r < index + rep) {
                return create ? isolate(row, r - index, rep, "number-rows-repeated") : row;
            }
            index += rep;
        }
        if (!create) return null;
        Node parent = rows.isEmpty() ? table : rows.get(rows.size() - 1).getParentNode();
        Node anchor = rows.isEmpty() ? null : rows.get(rows.size() - 1).getNextSibling();
        int gap = r - index;
        if (gap > 0) {
            Element empty = newRow(doc, 1);
            empty.setAttributeNS(Odf.TABLE, "table:number-rows-repeated", Integer.toString(gap));
            parent.insertBefore(empty, anchor);
        }
        Element row = newRow(doc, 1);
        parent.insertBefore(row, anchor);
        return row;
    }

    private static Element cellAt(Document doc, Element row, int c, boolean create) {
        int index = 0;
        Element last = null;
        for (Element cell : Xml.children(row)) {
            if (!Odf.TABLE.equals(cell.getNamespaceURI())) continue;
            if (!"table-cell".equals(cell.getLocalName()) && !"covered-table-cell".equals(cell.getLocalName())) continue;
            int rep = Odf.repeat(cell, Odf.TABLE, "number-columns-repeated");
            if (c < index + rep) {
                return create ? isolate(cell, c - index, rep, "number-columns-repeated") : cell;
            }
            index += rep;
            last = cell;
        }
        if (!create) return null;
        Node anchor = last == null ? row.getFirstChild() : last.getNextSibling();
        if (last == null) {
            anchor = null;
        }
        int gap = c - index;
        if (gap > 0) {
            Element empty = doc.createElementNS(Odf.TABLE, "table:table-cell");
            if (gap > 1) empty.setAttributeNS(Odf.TABLE, "table:number-columns-repeated", Integer.toString(gap));
            row.insertBefore(empty, anchor);
        }
        Element cell = doc.createElementNS(Odf.TABLE, "table:table-cell");
        row.insertBefore(cell, anchor);
        return cell;
    }

    // ------------------------------------------------------------------ OfficeEngine: правка

    @Override
    public String textAt(byte[] source, String id) {
        Target t = resolve(source, id);
        if (t == null) return null;
        Element row = rowAt(t.content, t.table, t.row, false);
        Element cell = row == null ? null : cellAt(t.content, row, t.col, false);
        return cell == null ? "" : raw(cell);
    }

    @Override
    public String displayAt(byte[] source, String id) {
        Target t = resolve(source, id);
        if (t == null) return null;
        Element row = rowAt(t.content, t.table, t.row, false);
        Element cell = row == null ? null : cellAt(t.content, row, t.col, false);
        return cell == null ? "" : display(cell);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        Target t = resolve(source, id);
        if (t == null) {
            throw new DocException("Ячейка не найдена — возможно, документ изменился");
        }
        Element row = rowAt(t.content, t.table, t.row, true);
        Element cell = cellAt(t.content, row, t.col, true);
        setValue(t.content, cell, newText == null ? "" : newText);
        t.pkg.putXml("content.xml", t.content);
        return t.pkg.toBytes();
    }

    private static void setValue(Document doc, Element cell, String text) {
        for (Element child : Xml.children(cell)) {
            if (Odf.TEXT.equals(child.getNamespaceURI()) && "p".equals(child.getLocalName())) cell.removeChild(child);
        }
        for (String a : VALUE_ATTRS) {
            cell.removeAttributeNS(Odf.OFFICE, a);
        }
        cell.removeAttributeNS(Odf.TABLE, "formula");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        boolean number = NUMBER.matcher(trimmed).matches();
        String stored = number ? trimmed.replace(',', '.') : null;
        cell.setAttributeNS(Odf.OFFICE, "office:value-type", number ? "float" : "string");
        if (number) cell.setAttributeNS(Odf.OFFICE, "office:value", stored);
        Element p = doc.createElementNS(Odf.TEXT, "text:p");
        Odf.setText(doc, p, number ? trimmed : text);
        cell.appendChild(p);
    }
}
