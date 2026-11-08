package dev.ronova.docforge.ooxml;

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

/** Просмотр и правка .xlsx: листы показываются таблицами, правится значение ячейки. */
public final class XlsxEngine implements OfficeEngine {

    private static final String S = XlsxStyles.S;
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final int MAX_COLS = 100;
    private static final int MAX_ROWS = 3000;
    private static final int MAX_CELLS = 60000;
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)([eE][+-]?\\d+)?$");

    @Override
    public DocFormat format() {
        return DocFormat.XLSX;
    }

    // ------------------------------------------------------------------ книга

    private static final class SheetRef {
        String name;
        String part;
        boolean hidden;
    }

    private static final class Book {
        final ZipPackage pkg;
        final List<SheetRef> sheets = new ArrayList<>();
        final List<String> shared = new ArrayList<>();
        final XlsxStyles styles;
        boolean date1904;

        Book(ZipPackage pkg) {
            this.pkg = pkg;
            Document wb = pkg.xml(WORKBOOK);
            if (wb == null) {
                throw new DocException("В файле нет xl/workbook.xml — это не книга Excel");
            }
            Map<String, String> byId = new HashMap<>();
            String sharedPart = "xl/sharedStrings.xml";
            String stylesPart = "xl/styles.xml";
            String themePart = "xl/theme/theme1.xml";
            for (ZipPackage.Relationship r : pkg.relationships(WORKBOOK)) {
                byId.put(r.id, r.target);
                if (r.type.endsWith("/sharedStrings")) sharedPart = r.target;
                else if (r.type.endsWith("/styles")) stylesPart = r.target;
                else if (r.type.endsWith("/theme")) themePart = r.target;
            }
            for (Element pr : Xml.descendants(wb, S, "workbookPr")) {
                date1904 = "1".equals(pr.getAttribute("date1904")) || "true".equals(pr.getAttribute("date1904"));
            }
            for (Element sh : Xml.descendants(wb, S, "sheet")) {
                SheetRef ref = new SheetRef();
                ref.name = sh.getAttribute("name");
                ref.part = byId.get(sh.getAttributeNS(R, "id"));
                String state = sh.getAttribute("state");
                ref.hidden = "hidden".equals(state) || "veryHidden".equals(state);
                if (ref.part != null && pkg.has(ref.part)) {
                    sheets.add(ref);
                }
            }
            Document ss = pkg.xml(sharedPart);
            if (ss != null) {
                for (Element si : Xml.descendants(ss, S, "si")) {
                    shared.add(stringItem(si));
                }
            }
            styles = new XlsxStyles(pkg.xml(stylesPart), pkg.xml(themePart));
        }
    }

    /** Текст элемента si / is: t или набор r/t (rPh — фонетика — пропускается). */
    private static String stringItem(Element si) {
        StringBuilder sb = new StringBuilder();
        for (Element c : Xml.children(si)) {
            if (Xml.is(c, S, "t")) {
                sb.append(c.getTextContent());
            } else if (Xml.is(c, S, "r")) {
                Element t = Xml.child(c, S, "t");
                if (t != null) sb.append(t.getTextContent());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ адреса

    /** "AB12" -> {строка с 0, колонка с 0}; null, если адрес некорректен. */
    static int[] parseRef(String ref) {
        if (ref == null) return null;
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        if (i == 0 || i == ref.length()) return null;
        try {
            int row = Integer.parseInt(ref.substring(i));
            return row < 1 || col < 1 ? null : new int[]{row - 1, col - 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static long key(int row, int col) {
        return (long) row * 100000L + col;
    }

    // ------------------------------------------------------------------ значение ячейки

    private static String cellDisplay(Book b, Element c) {
        Element v = Xml.child(c, S, "v");
        String raw = v == null ? "" : v.getTextContent();
        Element f = Xml.child(c, S, "f");
        String type = c.getAttribute("t");
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                return idx >= 0 && idx < b.shared.size() ? b.shared.get(idx) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("inlineStr".equals(type)) {
            Element is = Xml.child(c, S, "is");
            return is == null ? "" : stringItem(is);
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        if (raw.isEmpty() && f != null) {
            return "=" + f.getTextContent();
        }
        if ("str".equals(type) || "e".equals(type) || "d".equals(type)) {
            return raw;
        }
        if (raw.isEmpty()) {
            return "";
        }
        try {
            double d = Double.parseDouble(raw.trim());
            int xf = parseIntSafe(c.getAttribute("s"));
            return NumFmt.format(d, b.styles.style(xf).numFmtCode, b.date1904);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** Значение для окна правки: «как введено» — число без форматирования, формула со знаком «=». */
    private static String cellRaw(Book b, Element c) {
        Element f = Xml.child(c, S, "f");
        if (f != null && !f.getTextContent().isEmpty()) {
            return "=" + f.getTextContent();
        }
        String type = c.getAttribute("t");
        if ("s".equals(type) || "inlineStr".equals(type) || "b".equals(type)) {
            return cellDisplay(b, c);
        }
        Element v = Xml.child(c, S, "v");
        return v == null ? "" : v.getTextContent();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isNumericCell(Element c) {
        String type = c.getAttribute("t");
        return !(type != null && ("s".equals(type) || "str".equals(type) || "inlineStr".equals(type) || "e".equals(type) || "b".equals(type)))
                && Xml.child(c, S, "v") != null;
    }

    // ------------------------------------------------------------------ рендер

    @Override
    public String renderHtml(byte[] source) {
        Book book = new Book(ZipPackage.read(source));
        StringBuilder body = new StringBuilder();
        List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < book.sheets.size(); i++) {
            if (!book.sheets.get(i).hidden) visible.add(i);
        }
        if (visible.isEmpty() && !book.sheets.isEmpty()) {
            visible.add(0);
        }
        if (visible.isEmpty()) {
            throw new DocException("В книге нет листов");
        }
        if (visible.size() > 1) {
            body.append("<div class=\"tabs\">");
            for (int k = 0; k < visible.size(); k++) {
                body.append("<button data-tab=\"").append(k).append('"').append(k == 0 ? " class=\"on\"" : "").append('>')
                        .append(Html.esc(book.sheets.get(visible.get(k)).name)).append("</button>");
            }
            body.append("</div>");
        }
        for (int k = 0; k < visible.size(); k++) {
            body.append("<div class=\"sheetwrap").append(k == 0 ? " first" : "").append("\">");
            renderSheet(book, visible.get(k), body);
            body.append("</div>");
        }
        return Html.page(DocFormat.Kind.SHEET, "", body.toString());
    }

    private void renderSheet(Book book, int sheetIndex, StringBuilder out) {
        SheetRef ref = book.sheets.get(sheetIndex);
        Document doc = book.pkg.xml(ref.part);
        Map<Long, Element> cells = new HashMap<>();
        Map<Integer, Double> heights = new HashMap<>();
        Set<Integer> hiddenRows = new HashSet<>();
        int maxRow = -1;
        int maxCol = -1;
        if (doc != null) {
            int seqRow = 0;
            for (Element row : Xml.descendants(doc, S, "row")) {
                int r = row.hasAttribute("r") ? parseIntSafe(row.getAttribute("r")) - 1 : seqRow;
                seqRow = r + 1;
                if (r < 0) continue;
                if ("1".equals(row.getAttribute("hidden"))) hiddenRows.add(r);
                if ("1".equals(row.getAttribute("customHeight")) && row.hasAttribute("ht")) {
                    try {
                        heights.put(r, Double.parseDouble(row.getAttribute("ht")) * 96.0 / 72.0);
                    } catch (NumberFormatException ignored) {
                        // высота по умолчанию
                    }
                }
                int seqCol = 0;
                for (Element c : Xml.children(row, S, "c")) {
                    int[] rc = parseRef(c.getAttribute("r"));
                    int col = rc != null ? rc[1] : seqCol;
                    seqCol = col + 1;
                    cells.put(key(r, col), c);
                    maxRow = Math.max(maxRow, r);
                    maxCol = Math.max(maxCol, col);
                }
            }
        }
        // объединённые ячейки: верхняя левая получает colspan/rowspan, остальные пропускаются
        Map<Long, int[]> merges = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        if (doc != null) {
            for (Element m : Xml.descendants(doc, S, "mergeCell")) {
                String[] parts = m.getAttribute("ref").split(":");
                if (parts.length != 2) continue;
                int[] a = parseRef(parts[0]);
                int[] z = parseRef(parts[1]);
                if (a == null || z == null) continue;
                merges.put(key(a[0], a[1]), new int[]{z[0] - a[0] + 1, z[1] - a[1] + 1});
                for (int r = a[0]; r <= z[0] && r - a[0] < 500; r++) {
                    for (int c = a[1]; c <= z[1] && c - a[1] < 200; c++) {
                        if (r != a[0] || c != a[1]) covered.add(key(r, c));
                    }
                }
                maxRow = Math.max(maxRow, z[0]);
                maxCol = Math.max(maxCol, z[1]);
            }
        }
        int cols = Math.min(Math.max(maxCol + 1, 6), MAX_COLS);
        int rows = Math.min(Math.max(maxRow + 1, 12), MAX_ROWS);
        if ((long) rows * cols > MAX_CELLS) {
            rows = Math.max(1, MAX_CELLS / cols);
        }
        boolean truncated = maxRow + 1 > rows || maxCol + 1 > cols;

        // ширины колонок
        double defaultWidth = 64;
        double[] widths = new double[cols];
        boolean[] hiddenCols = new boolean[cols];
        for (int i = 0; i < cols; i++) widths[i] = defaultWidth;
        if (doc != null) {
            for (Element fp : Xml.descendants(doc, S, "sheetFormatPr")) {
                try {
                    if (fp.hasAttribute("defaultColWidth")) {
                        defaultWidth = Double.parseDouble(fp.getAttribute("defaultColWidth")) * 7 + 5;
                    } else if (fp.hasAttribute("baseColWidth")) {
                        defaultWidth = Double.parseDouble(fp.getAttribute("baseColWidth")) * 7 + 12;
                    }
                } catch (NumberFormatException ignored) {
                    // оставляем 64
                }
            }
            for (int i = 0; i < cols; i++) widths[i] = defaultWidth;
            for (Element col : Xml.descendants(doc, S, "col")) {
                int min = parseIntSafe(col.getAttribute("min")) - 1;
                int max = parseIntSafe(col.getAttribute("max")) - 1;
                boolean hide = "1".equals(col.getAttribute("hidden"));
                double w = defaultWidth;
                try {
                    if (col.hasAttribute("width")) w = Double.parseDouble(col.getAttribute("width")) * 7 + 5;
                } catch (NumberFormatException ignored) {
                    // оставляем ширину по умолчанию
                }
                for (int i = Math.max(min, 0); i <= max && i < cols; i++) {
                    widths[i] = w;
                    hiddenCols[i] = hide;
                }
            }
        }
        double total = 40;
        for (int i = 0; i < cols; i++) if (!hiddenCols[i]) total += widths[i];

        out.append("<table class=\"sheet\" style=\"width:").append(Html.num(total)).append("px\"><colgroup><col style=\"width:40px\">");
        for (int i = 0; i < cols; i++) {
            if (!hiddenCols[i]) out.append("<col style=\"width:").append(Html.num(widths[i])).append("px\">");
        }
        out.append("</colgroup><thead><tr><th class=\"corner rh\"></th>");
        for (int i = 0; i < cols; i++) {
            if (!hiddenCols[i]) out.append("<th>").append(colName(i)).append("</th>");
        }
        out.append("</tr></thead><tbody>");
        for (int r = 0; r < rows; r++) {
            if (hiddenRows.contains(r)) continue;
            out.append("<tr");
            Double h = heights.get(r);
            if (h != null) out.append(" style=\"height:").append(Html.num(h)).append("px\"");
            out.append("><th class=\"rh\">").append(r + 1).append("</th>");
            for (int c = 0; c < cols; c++) {
                if (hiddenCols[c] || covered.contains(key(r, c))) continue;
                Element cell = cells.get(key(r, c));
                String id = "s" + sheetIndex + ":" + colName(c) + (r + 1);
                StringBuilder css = new StringBuilder();
                String text = "";
                if (cell != null) {
                    XlsxStyles.CellStyle cs = book.styles.style(parseIntSafe(cell.getAttribute("s")));
                    css.append(cs.css);
                    text = cellDisplay(book, cell);
                    if (cs.horizontal == null) {
                        String type = cell.getAttribute("t");
                        if ("b".equals(type) || "e".equals(type)) css.append("text-align:center;");
                        else if (isNumericCell(cell) && !text.startsWith("=")) css.append("text-align:right;");
                    }
                    if (!cs.wrap && text.indexOf('\n') >= 0) css.append("white-space:pre;");
                }
                out.append("<td data-id=\"").append(id).append('"');
                int[] span = merges.get(key(r, c));
                if (span != null) {
                    if (span[1] > 1) out.append(" colspan=\"").append(span[1]).append('"');
                    if (span[0] > 1) out.append(" rowspan=\"").append(span[0]).append('"');
                }
                if (css.length() > 0) out.append(" style=\"").append(css).append('"');
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

    // ------------------------------------------------------------------ поиск ячейки

    private static final class Target {
        Book book;
        SheetRef sheet;
        int sheetIndex;
        int row;
        int col;
    }

    private static Target resolve(byte[] source, String id) {
        if (id == null || !id.startsWith("s") || id.indexOf(':') < 0) {
            return null;
        }
        try {
            int idx = Integer.parseInt(id.substring(1, id.indexOf(':')));
            int[] rc = parseRef(id.substring(id.indexOf(':') + 1));
            Book book = new Book(ZipPackage.read(source));
            if (rc == null || idx < 0 || idx >= book.sheets.size()) return null;
            Target t = new Target();
            t.book = book;
            t.sheet = book.sheets.get(idx);
            t.sheetIndex = idx;
            t.row = rc[0];
            t.col = rc[1];
            return t;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Element findCell(Document doc, int row, int col, boolean create) {
        Element root = doc.getDocumentElement();
        Element sheetData = Xml.child(root, S, "sheetData");
        if (sheetData == null) {
            if (!create) return null;
            sheetData = make(doc, root, "sheetData");
            Node anchor = null;
            // sheetData идёт после sheetPr, dimension, sheetViews, sheetFormatPr и cols
            for (Element c : Xml.children(root)) {
                String n = c.getLocalName();
                if ("sheetPr".equals(n) || "dimension".equals(n) || "sheetViews".equals(n) || "sheetFormatPr".equals(n) || "cols".equals(n)) {
                    anchor = c.getNextSibling();
                }
            }
            root.insertBefore(sheetData, anchor);
        }
        Element rowEl = null;
        Element insertBefore = null;
        int seq = 0;
        for (Element r : Xml.children(sheetData, S, "row")) {
            int ri = r.hasAttribute("r") ? parseIntSafe(r.getAttribute("r")) - 1 : seq;
            seq = ri + 1;
            if (ri == row) {
                rowEl = r;
                break;
            }
            if (ri > row) {
                insertBefore = r;
                break;
            }
        }
        if (rowEl == null) {
            if (!create) return null;
            rowEl = make(doc, sheetData, "row");
            rowEl.setAttribute("r", Integer.toString(row + 1));
            sheetData.insertBefore(rowEl, insertBefore);
        }
        Element cellEl = null;
        Element cellBefore = null;
        int seqCol = 0;
        for (Element c : Xml.children(rowEl, S, "c")) {
            int[] rc = parseRef(c.getAttribute("r"));
            int ci = rc != null ? rc[1] : seqCol;
            seqCol = ci + 1;
            if (ci == col) {
                cellEl = c;
                break;
            }
            if (ci > col) {
                cellBefore = c;
                break;
            }
        }
        if (cellEl == null) {
            if (!create) return null;
            cellEl = make(doc, rowEl, "c");
            cellEl.setAttribute("r", colName(col) + (row + 1));
            rowEl.insertBefore(cellEl, cellBefore);
        }
        return cellEl;
    }

    private static Element make(Document doc, Element near, String local) {
        String prefix = near.getPrefix();
        return doc.createElementNS(S, prefix == null || prefix.isEmpty() ? local : prefix + ":" + local);
    }

    // ------------------------------------------------------------------ OfficeEngine: правка

    @Override
    public String textAt(byte[] source, String id) {
        Target t = resolve(source, id);
        if (t == null) return null;
        Document doc = t.book.pkg.xml(t.sheet.part);
        Element cell = doc == null ? null : findCell(doc, t.row, t.col, false);
        return cell == null ? "" : cellRaw(t.book, cell);
    }

    @Override
    public String displayAt(byte[] source, String id) {
        Target t = resolve(source, id);
        if (t == null) return null;
        Document doc = t.book.pkg.xml(t.sheet.part);
        Element cell = doc == null ? null : findCell(doc, t.row, t.col, false);
        return cell == null ? "" : cellDisplay(t.book, cell);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        Target t = resolve(source, id);
        if (t == null) {
            throw new DocException("Ячейка не найдена — возможно, документ изменился");
        }
        ZipPackage pkg = t.book.pkg;
        Document doc = pkg.xml(t.sheet.part);
        if (doc == null) {
            throw new DocException("Лист не найден");
        }
        Element cell = findCell(doc, t.row, t.col, true);
        setValue(doc, cell, newText);
        pkg.putXml(t.sheet.part, doc);
        invalidateCalculation(pkg);
        return pkg.toBytes();
    }

    private static void setValue(Document doc, Element cell, String text) {
        for (Element child : Xml.children(cell)) {
            String n = child.getLocalName();
            if ("f".equals(n) || "v".equals(n) || "is".equals(n)) {
                cell.removeChild(child);
            }
        }
        cell.removeAttribute("t");
        String value = text == null ? "" : text;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith("=") && trimmed.length() > 1) {
            Element f = make(doc, cell, "f");
            f.setTextContent(trimmed.substring(1));
            cell.appendChild(f);
        } else if (NUMBER.matcher(trimmed).matches()) {
            Element v = make(doc, cell, "v");
            v.setTextContent(trimmed.replace(',', '.'));
            cell.appendChild(v);
        } else {
            cell.setAttribute("t", "inlineStr");
            Element is = make(doc, cell, "is");
            Element t = make(doc, cell, "t");
            t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
            t.setTextContent(value);
            is.appendChild(t);
            cell.appendChild(is);
        }
    }

    /**
     * После правки значений кэш формул устарел. Цепочка вычислений (calcChain) может ссылаться на
     * ячейки, где формулы больше нет, — Excel счёл бы файл повреждённым, поэтому она удаляется
     * (Excel построит её заново), а пересчёт включается при открытии.
     */
    private static void invalidateCalculation(ZipPackage pkg) {
        pkg.remove("xl/calcChain.xml");
        Document types = pkg.xml("[Content_Types].xml");
        if (types != null) {
            boolean changed = false;
            for (Element o : Xml.children(types.getDocumentElement())) {
                if ("Override".equals(o.getLocalName()) && o.getAttribute("PartName").endsWith("calcChain.xml")) {
                    types.getDocumentElement().removeChild(o);
                    changed = true;
                }
            }
            if (changed) pkg.putXml("[Content_Types].xml", types);
        }
        Document rels = pkg.xml("xl/_rels/workbook.xml.rels");
        if (rels != null) {
            boolean changed = false;
            for (Element r : Xml.children(rels.getDocumentElement())) {
                if (r.getAttribute("Target").endsWith("calcChain.xml")) {
                    rels.getDocumentElement().removeChild(r);
                    changed = true;
                }
            }
            if (changed) pkg.putXml("xl/_rels/workbook.xml.rels", rels);
        }
        Document wb = pkg.xml(WORKBOOK);
        if (wb != null) {
            Element root = wb.getDocumentElement();
            Element calc = Xml.child(root, S, "calcPr");
            if (calc == null) {
                calc = make(wb, root, "calcPr");
                Node anchor = null;
                for (Element c : Xml.children(root)) {
                    String n = c.getLocalName();
                    if ("sheets".equals(n) || "functionGroups".equals(n) || "externalReferences".equals(n) || "definedNames".equals(n)) {
                        anchor = c.getNextSibling();
                    }
                }
                root.insertBefore(calc, anchor);
            }
            calc.setAttribute("fullCalcOnLoad", "1");
            pkg.putXml(WORKBOOK, wb);
        }
    }
}
