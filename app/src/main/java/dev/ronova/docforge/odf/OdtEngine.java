package dev.ronova.docforge.odf;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.core.DocFormat;
import dev.ronova.docforge.core.OfficeEngine;
import dev.ronova.docforge.html.Html;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.util.IdentityHashMap;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Просмотр и правка .odt (LibreOffice Writer): абзацы, списки, таблицы, картинки. */
public final class OdtEngine implements OfficeEngine {

    @Override
    public DocFormat format() {
        return DocFormat.ODT;
    }

    @Override
    public String renderHtml(byte[] source) {
        ZipPackage pkg = ZipPackage.read(source);
        Document content = pkg.xml("content.xml");
        if (content == null) {
            throw new DocException("В файле нет content.xml — это не документ OpenDocument");
        }
        OdfStyles styles = new OdfStyles(content, pkg.xml("styles.xml"));
        List<Element> bodies = Xml.descendants(content, Odf.OFFICE, "text");
        if (bodies.isEmpty()) {
            throw new DocException("Это не текстовый документ OpenDocument");
        }
        IdentityHashMap<Element, Integer> ids = new IdentityHashMap<>();
        List<Element> all = Odf.paragraphs(bodies.get(0));
        for (int i = 0; i < all.size(); i++) ids.put(all.get(i), i);

        StringBuilder out = new StringBuilder("<div class=\"page\"");
        String base = styles.baseTextCss();
        if (!base.isEmpty()) out.append(" style=\"").append(Html.esc(base)).append('"');
        out.append('>');
        new OdfBlocks(pkg, styles, ids).blocks(bodies.get(0), out);
        out.append("</div>");
        return Html.page(DocFormat.Kind.TEXT, "", out.toString());
    }

    /** Абзац по id вида p<N>. */
    static Element paragraph(Document content, String id) {
        if (id == null || !id.startsWith("p")) return null;
        try {
            int index = Integer.parseInt(id.substring(1));
            List<Element> bodies = Xml.descendants(content, Odf.OFFICE, "text");
            if (bodies.isEmpty()) {
                bodies = Xml.descendants(content, Odf.OFFICE, "presentation");
            }
            if (bodies.isEmpty()) return null;
            List<Element> all = Odf.paragraphs(bodies.get(0));
            return index >= 0 && index < all.size() ? all.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String textAt(byte[] source, String id) {
        Document content = ZipPackage.read(source).xml("content.xml");
        Element p = content == null ? null : paragraph(content, id);
        return p == null ? null : Odf.plainText(p);
    }

    @Override
    public String displayAt(byte[] source, String id) {
        return textAt(source, id);
    }

    @Override
    public byte[] editText(byte[] source, String id, String newText) {
        ZipPackage pkg = ZipPackage.read(source);
        Document content = pkg.xml("content.xml");
        Element p = content == null ? null : paragraph(content, id);
        if (p == null) {
            throw new DocException("Абзац не найден — возможно, документ изменился");
        }
        Odf.setText(content, p, newText);
        pkg.putXml("content.xml", content);
        return pkg.toBytes();
    }
}
