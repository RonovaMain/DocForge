package dev.ronova.docforge.xml;

import dev.ronova.docforge.core.DocException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Вспомогательные функции для DOM: разбор, сериализация, обход потомков. */
public final class Xml {
    private Xml() {
    }

    public static Document parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // На Android часть настроек не поддерживается — это не страшно
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // ok
            }
            try {
                factory.setExpandEntityReferences(false);
            } catch (Exception ignored) {
                // ok
            }
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new DocException("Не удалось прочитать XML внутри документа: " + e.getMessage(), e);
        }
    }

    public static byte[] toBytes(Document doc) {
        try {
            try {
                doc.setXmlStandalone(true);
            } catch (Exception ignored) {
                // ok
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new DocException("Не удалось сохранить XML: " + e.getMessage(), e);
        }
    }

    public static boolean is(Node n, String ns, String local) {
        return n instanceof Element && ns.equals(n.getNamespaceURI()) && local.equals(n.getLocalName());
    }

    /** Все потомки с данным именем в порядке документа. */
    public static List<Element> descendants(Node root, String ns, String local) {
        List<Element> out = new ArrayList<>();
        NodeList list = root instanceof Document
                ? ((Document) root).getElementsByTagNameNS(ns, local)
                : ((Element) root).getElementsByTagNameNS(ns, local);
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    /** Прямые дочерние элементы. */
    public static List<Element> children(Node parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static List<Element> children(Node parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, local)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static Element child(Node parent, String ns, String local) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (is(n, ns, local)) {
                return (Element) n;
            }
        }
        return null;
    }

    /** Значение атрибута без пространства имён или null, если его нет. */
    public static String attr(Element e, String name) {
        return e != null && e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    /** Значение атрибута с пространством имён или null. */
    public static String attrNs(Element e, String ns, String local) {
        return e != null && e.hasAttributeNS(ns, local) ? e.getAttributeNS(ns, local) : null;
    }

    public static Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new DocException("XML недоступен: " + e.getMessage(), e);
        }
    }
}
