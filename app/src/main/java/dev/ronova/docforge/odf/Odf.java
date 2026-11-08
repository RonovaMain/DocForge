package dev.ronova.docforge.odf;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Пространства имён ODF и общие операции с текстом абзацев. */
final class Odf {
    static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    static final String SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    static final String XLINK = "http://www.w3.org/1999/xlink";
    static final String PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0";

    private Odf() {
    }

    /** Значение атрибута с пространством имён или null. */
    static String a(Element e, String ns, String local) {
        return e != null && e.hasAttributeNS(ns, local) ? e.getAttributeNS(ns, local) : null;
    }

    /** Длина ("2.5cm", "10mm", "1in", "12pt") в пунктах; null, если не длина. */
    static Double toPt(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty() || v.endsWith("%")) return null;
        int i = 0;
        while (i < v.length() && (Character.isDigit(v.charAt(i)) || v.charAt(i) == '.' || v.charAt(i) == '-' || v.charAt(i) == '+')) i++;
        if (i == 0) return null;
        double n;
        try {
            n = Double.parseDouble(v.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = v.substring(i).trim();
        switch (unit) {
            case "cm": return n * 28.3465;
            case "mm": return n * 2.83465;
            case "in": return n * 72;
            case "pt": return n;
            case "pc": return n * 12;
            case "px": return n * 0.75;
            case "": return n;
            default: return null;
        }
    }

    static int repeat(Element e, String ns, String local) {
        String v = a(e, ns, local);
        if (v == null || v.isEmpty()) return 1;
        try {
            return Math.max(1, Integer.parseInt(v));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ абзацы

    static boolean isParagraph(Node n) {
        return n instanceof Element && TEXT.equals(n.getNamespaceURI())
                && ("p".equals(n.getLocalName()) || "h".equals(n.getLocalName()));
    }

    /** Все text:p и text:h в порядке документа (включая абзацы в таблицах, списках и текстовых блоках). */
    static List<Element> paragraphs(Node root) {
        List<Element> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<Element> out) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element)) continue;
            if (isParagraph(c)) out.add((Element) c);
            collect(c, out);
        }
    }

    /** Текст абзаца: пробелы text:s, табы и переносы строк превращаются в символы. */
    static String plainText(Element p) {
        StringBuilder sb = new StringBuilder();
        appendPlain(p, sb);
        return sb.toString();
    }

    private static void appendPlain(Node parent, StringBuilder sb) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                sb.append(n.getNodeValue());
            } else if (n instanceof Element) {
                Element e = (Element) n;
                if (!TEXT.equals(e.getNamespaceURI())) {
                    // кадры, аннотации и прочие вложенные объекты в текст абзаца не входят
                    continue;
                }
                String name = e.getLocalName();
                if ("s".equals(name)) {
                    int count = repeat(e, TEXT, "c");
                    for (int i = 0; i < count && i < 1000; i++) sb.append(' ');
                } else if ("tab".equals(name)) {
                    sb.append('\t');
                } else if ("line-break".equals(name)) {
                    sb.append('\n');
                } else if ("note".equals(name) || "bookmark".equals(name) || "bookmark-start".equals(name)
                        || "bookmark-end".equals(name) || "soft-page-break".equals(name)) {
                    continue;
                } else {
                    appendPlain(e, sb);
                }
            }
        }
    }

    private static boolean isTextContent(Node n) {
        if (n.getNodeType() == Node.TEXT_NODE) return true;
        if (!(n instanceof Element) || !TEXT.equals(n.getNamespaceURI())) return false;
        String name = n.getLocalName();
        return "span".equals(name) || "s".equals(name) || "tab".equals(name) || "line-break".equals(name) || "a".equals(name);
    }

    /**
     * Заменяет текст абзаца. Вложенные объекты (картинки, закладки) остаются на месте;
     * если абзац начинался с оформленного фрагмента (text:span), новый текст получает то же оформление.
     */
    static void setText(Document doc, Element p, String text) {
        List<Node> removable = new ArrayList<>();
        Node first = null;
        String spanStyle = null;
        boolean spanDecided = false;
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!isTextContent(n)) continue;
            removable.add(n);
            if (first == null) first = n;
            if (!spanDecided) {
                if (n instanceof Element && "span".equals(n.getLocalName())) {
                    spanStyle = a((Element) n, TEXT, "style-name");
                    spanDecided = true;
                } else if (n.getNodeType() == Node.TEXT_NODE && n.getNodeValue().trim().isEmpty()) {
                    // пробельный узел не решает
                } else {
                    spanDecided = true;
                }
            }
        }
        List<Node> built = build(doc, text);
        Node anchor = first;
        Element wrapper = null;
        if (spanStyle != null && !built.isEmpty()) {
            wrapper = doc.createElementNS(TEXT, "text:span");
            wrapper.setAttributeNS(TEXT, "text:style-name", spanStyle);
            for (Node n : built) wrapper.appendChild(n);
            built = new ArrayList<>();
            built.add(wrapper);
        }
        for (Node n : built) {
            p.insertBefore(n, anchor);
        }
        for (Node n : removable) {
            p.removeChild(n);
        }
    }

    private static List<Node> build(Document doc, String text) {
        List<Node> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        boolean atStart = true;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ') {
                int j = i;
                while (j < text.length() && text.charAt(j) == ' ') j++;
                int count = j - i;
                if (atStart) {
                    // пробелы в начале абзаца и после перевода строки ODF схлопывает — пишем их элементом text:s
                    flush(doc, buf, out);
                    out.add(spaces(doc, count));
                } else {
                    buf.append(' ');
                    if (count > 1) {
                        flush(doc, buf, out);
                        out.add(spaces(doc, count - 1));
                    }
                }
                i = j;
                atStart = false;
                continue;
            }
            atStart = false;
            if (c == '\t') {
                flush(doc, buf, out);
                out.add(doc.createElementNS(TEXT, "text:tab"));
            } else if (c == '\n' || c == '\r') {
                flush(doc, buf, out);
                out.add(doc.createElementNS(TEXT, "text:line-break"));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                atStart = true;
            } else {
                buf.append(c);
            }
            i++;
        }
        flush(doc, buf, out);
        return out;
    }

    private static Element spaces(Document doc, int count) {
        Element s = doc.createElementNS(TEXT, "text:s");
        s.setAttributeNS(TEXT, "text:c", Integer.toString(count));
        return s;
    }

    private static void flush(Document doc, StringBuilder buf, List<Node> out) {
        if (buf.length() > 0) {
            out.add(doc.createTextNode(buf.toString()));
            buf.setLength(0);
        }
    }
}
