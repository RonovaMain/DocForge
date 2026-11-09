package dev.ronova.docforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.ronova.docforge.core.DocException;
import dev.ronova.docforge.core.DocFormat;
import dev.ronova.docforge.core.Engines;
import dev.ronova.docforge.core.OfficeEngine;
import dev.ronova.docforge.xml.Xml;
import dev.ronova.docforge.zip.ZipPackage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Просмотр и правка на маленьких файлах, собранных прямо в тесте. */
public class OfficeEditTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ DOCX

    private static byte[] docx(String bodyXml) {
        ZipPackage pkg = ZipPackage.create();
        pkg.put("word/document.xml", utf8("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"" + W
                + "\"><w:body>" + bodyXml + "</w:body></w:document>"));
        return pkg.toBytes();
    }

    private static List<String> runTexts(byte[] docx) {
        Document doc = ZipPackage.read(docx).xml("word/document.xml");
        List<String> out = new ArrayList<>();
        for (Element t : Xml.descendants(doc, W, "t")) out.add(t.getTextContent());
        return out;
    }

    private static final String THREE_RUNS = "<w:p><w:r><w:t xml:space=\"preserve\">Hello </w:t></w:r>"
            + "<w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r><w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p>";

    @Test
    public void docxInsertionKeepsFormattingOfTheRunItLandsIn() {
        OfficeEngine e = Engines.forFormat(DocFormat.DOCX);
        byte[] result = e.editText(docx(THREE_RUNS), "p0", "Hello bolder world");
        assertEquals("Hello bolder world", e.textAt(result, "p0"));
        List<String> runs = runTexts(result);
        assertEquals("Hello ", runs.get(0));
        assertEquals("bolder", runs.get(1));
        assertEquals(" world", runs.get(2));
        Document doc = ZipPackage.read(result).xml("word/document.xml");
        Element boldRun = (Element) Xml.descendants(doc, W, "t").get(1).getParentNode();
        assertNotNull(Xml.child(Xml.child(boldRun, W, "rPr"), W, "b"));
    }

    @Test
    public void docxDeletionAcrossRuns() {
        OfficeEngine e = Engines.forFormat(DocFormat.DOCX);
        byte[] result = e.editText(docx(THREE_RUNS), "p0", "Hello world");
        assertEquals("Hello world", e.textAt(result, "p0"));
        assertEquals("", runTexts(result).get(1));
    }

    @Test
    public void docxEmptyParagraphGetsNewRunAndNewlinesBecomeSpaces() {
        OfficeEngine e = Engines.forFormat(DocFormat.DOCX);
        byte[] result = e.editText(docx("<w:p/>"), "p0", "Первая\nвторая");
        assertEquals("Первая вторая", e.textAt(result, "p0"));
    }

    @Test
    public void docxTextIsEscapedInHtmlAndScriptsCannotRun() {
        String html = Engines.forFormat(DocFormat.DOCX).renderHtml(
                docx("<w:p><w:r><w:t>&lt;script&gt;alert(1)&lt;/script&gt; &amp; \"q\"</w:t></w:r></w:p>"));
        assertTrue(html, html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot;"));
        assertFalse(html.contains("<script>alert(1)"));
        assertTrue(html.contains("Content-Security-Policy"));
        assertTrue(html.contains("data-id=\"p0\""));
    }

    @Test
    public void docxUnknownParagraphIdGivesNullOrError() {
        OfficeEngine e = Engines.forFormat(DocFormat.DOCX);
        assertNull(e.textAt(docx(THREE_RUNS), "p7"));
        try {
            e.editText(docx(THREE_RUNS), "p7", "x");
            fail("expected DocException");
        } catch (DocException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    // ------------------------------------------------------------------ XLSX

    private static byte[] xlsx() {
        ZipPackage pkg = ZipPackage.create();
        pkg.put("[Content_Types].xml", utf8("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"x\"/>"
                + "<Override PartName=\"/xl/calcChain.xml\" ContentType=\"y\"/></Types>"));
        pkg.put("xl/workbook.xml", utf8("<workbook xmlns=\"" + S + "\" xmlns:r=\"" + R + "\"><sheets>"
                + "<sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
        pkg.put("xl/_rels/workbook.xml.rels", utf8("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"x/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"x/calcChain\" Target=\"calcChain.xml\"/></Relationships>"));
        pkg.put("xl/calcChain.xml", utf8("<calcChain xmlns=\"" + S + "\"><c r=\"A1\"/></calcChain>"));
        pkg.put("xl/sharedStrings.xml", utf8("<sst xmlns=\"" + S + "\"><si><t>Привет &lt;b&gt;</t></si></sst>"));
        pkg.put("xl/worksheets/sheet1.xml", utf8("<worksheet xmlns=\"" + S + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"D1\"><v>3.5</v></c></row>"
                + "<row r=\"4\"><c r=\"A4\"><f>A1*2</f><v>7</v></c></row></sheetData></worksheet>"));
        return pkg.toBytes();
    }

    @Test
    public void xlsxEditInsertsCellsInOrderAndTypesValues() {
        OfficeEngine e = Engines.forFormat(DocFormat.XLSX);
        byte[] b = xlsx();
        assertEquals("Привет <b>", e.textAt(b, "s0:A1"));
        assertEquals("3.5", e.textAt(b, "s0:D1"));
        assertEquals("=A1*2", e.textAt(b, "s0:A4"));

        b = e.editText(b, "s0:B1", "Текст");          // между A1 и D1
        b = e.editText(b, "s0:C2", "12,5");           // новая строка между 1 и 4, число
        b = e.editText(b, "s0:A4", "=A1+1");          // формула заменена другой формулой
        b = e.editText(b, "s0:Z9", "далеко");         // строка после последней

        assertEquals("Текст", e.textAt(b, "s0:B1"));
        assertEquals("12.5", e.textAt(b, "s0:C2"));
        assertEquals("=A1+1", e.textAt(b, "s0:A4"));
        assertEquals("далеко", e.textAt(b, "s0:Z9"));

        Document sheet = ZipPackage.read(b).xml("xl/worksheets/sheet1.xml");
        List<String> refs = new ArrayList<>();
        for (Element c : Xml.descendants(sheet, S, "c")) refs.add(c.getAttribute("r"));
        assertEquals("[A1, B1, D1, C2, A4, Z9]", refs.toString());
        List<String> rows = new ArrayList<>();
        for (Element r : Xml.descendants(sheet, S, "row")) rows.add(r.getAttribute("r"));
        assertEquals("[1, 2, 4, 9]", rows.toString());
    }

    @Test
    public void xlsxEditDropsCalcChainAndForcesRecalculation() {
        OfficeEngine e = Engines.forFormat(DocFormat.XLSX);
        byte[] b = e.editText(xlsx(), "s0:A4", "5");
        ZipPackage pkg = ZipPackage.read(b);
        assertFalse(pkg.has("xl/calcChain.xml"));
        String types = new String(pkg.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        assertFalse(types, types.contains("calcChain"));
        String rels = new String(pkg.get("xl/_rels/workbook.xml.rels"), StandardCharsets.UTF_8);
        assertFalse(rels, rels.contains("calcChain"));
        Document wb = pkg.xml("xl/workbook.xml");
        assertEquals("1", Xml.descendants(wb, S, "calcPr").get(0).getAttribute("fullCalcOnLoad"));
    }

    @Test
    public void xlsxRendersSheetAsEscapedTableWithEditableCells() {
        String html = Engines.forFormat(DocFormat.XLSX).renderHtml(xlsx());
        assertTrue(html.contains("data-id=\"s0:A1\""));
        assertTrue(html.contains("Привет &lt;b&gt;"));
        assertTrue(html.contains("data-id=\"s0:F12\"")); // пустые ячейки тоже можно править
    }

    // ------------------------------------------------------------------ ODS

    private static final String ODF_NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
            + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
            + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" "
            + "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" "
            + "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\"";

    private static byte[] ods() {
        ZipPackage pkg = ZipPackage.create();
        pkg.put("mimetype", utf8("application/vnd.oasis.opendocument.spreadsheet"));
        pkg.put("content.xml", utf8("<office:document-content " + ODF_NS + "><office:body><office:spreadsheet>"
                + "<table:table table:name=\"T\"><table:table-column table:number-columns-repeated=\"5\"/>"
                + "<table:table-row table:number-rows-repeated=\"5\">"
                + "<table:table-cell office:value-type=\"string\"><text:p>a</text:p></table:table-cell>"
                + "<table:table-cell table:number-columns-repeated=\"4\"/></table:table-row>"
                + "</table:table></office:spreadsheet></office:body></office:document-content>"));
        return pkg.toBytes();
    }

    @Test
    public void odsEditSplitsRepeatedRowsAndCellsWithoutDisturbingNeighbours() {
        OfficeEngine e = Engines.forFormat(DocFormat.ODS);
        byte[] b = e.editText(ods(), "s0:2:3", "42,5");
        assertEquals("42.5", e.textAt(b, "s0:2:3"));
        assertEquals("a", e.textAt(b, "s0:2:0"));
        assertEquals("a", e.textAt(b, "s0:0:0"));
        assertEquals("a", e.textAt(b, "s0:4:0"));
        assertEquals("", e.textAt(b, "s0:2:2"));
        assertEquals("", e.textAt(b, "s0:3:3"));
        assertEquals("", e.textAt(b, "s0:2:4"));
    }

    @Test
    public void odsEditBeyondTheEndAppendsRowsAndCells() {
        OfficeEngine e = Engines.forFormat(DocFormat.ODS);
        byte[] b = e.editText(ods(), "s0:12:7", "далеко");
        assertEquals("далеко", e.textAt(b, "s0:12:7"));
        assertEquals("a", e.textAt(b, "s0:4:0"));
        assertEquals("", e.textAt(b, "s0:8:0"));
    }

    @Test
    public void odsMimetypeStaysFirstAndStored() {
        byte[] b = Engines.forFormat(DocFormat.ODS).editText(ods(), "s0:0:1", "x");
        assertEquals(DocFormat.ODS, DocFormat.detect("noext", b));
        // поле «method» первой записи ZIP: 0 = STORED
        assertEquals(0, (b[8] & 0xFF) | ((b[9] & 0xFF) << 8));
    }

    // ------------------------------------------------------------------ ODT

    private static byte[] odt(String body) {
        ZipPackage pkg = ZipPackage.create();
        pkg.put("mimetype", utf8("application/vnd.oasis.opendocument.text"));
        pkg.put("content.xml", utf8("<office:document-content " + ODF_NS + "><office:body><office:text>" + body
                + "</office:text></office:body></office:document-content>"));
        return pkg.toBytes();
    }

    @Test
    public void odtEditKeepsInlineObjectsAndSpacesRoundTrip() {
        OfficeEngine e = Engines.forFormat(DocFormat.ODT);
        byte[] b = odt("<text:p>Слово<text:s text:c=\"3\"/>конец<text:tab/>хвост</text:p>");
        assertEquals("Слово   конец\tхвост", e.textAt(b, "p0"));
        byte[] r = e.editText(b, "p0", "Новое  слово\nи табы\tтут");
        assertEquals("Новое  слово\nи табы\tтут", e.textAt(r, "p0"));
    }

    @Test
    public void odtEditKeepsStyleOfTheLeadingSpan() {
        OfficeEngine e = Engines.forFormat(DocFormat.ODT);
        byte[] b = odt("<text:p><text:span text:style-name=\"T1\">Жирный</text:span> обычный</text:p>");
        byte[] r = e.editText(b, "p0", "Другое");
        Document doc = ZipPackage.read(r).xml("content.xml");
        Element span = Xml.descendants(doc, "urn:oasis:names:tc:opendocument:xmlns:text:1.0", "span").get(0);
        assertEquals("T1", span.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:text:1.0", "style-name"));
        assertEquals("Другое", e.textAt(r, "p0"));
    }

    // ------------------------------------------------------------------ общее

    @Test
    public void pdfWrapperHasLazyPagesAndPageTracking() {
        List<int[]> sizes = new ArrayList<>();
        sizes.add(new int[]{595, 842});
        sizes.add(new int[]{842, 595});
        String html = dev.ronova.docforge.html.Html.pdfPages(sizes);
        assertTrue(html.contains("src=\"https://docforge.local/pdf/0\""));
        assertTrue(html.contains("src=\"https://docforge.local/pdf/1\""));
        assertTrue(html.contains("class=\"pdfpage\" loading=\"lazy\" width=\"595\" height=\"842\""));
        assertTrue(html.contains("window.gotoPage"));
        assertTrue(html.contains("window.setNight"));
    }

    @Test
    public void slideAndTextPagesCarryNightModeAndSlidesCarryPageTracking() {
        String text = Engines.forFormat(DocFormat.DOCX).renderHtml(docx("<w:p/>"));
        assertTrue(text.contains("window.setNight"));
        assertFalse(text.contains("window.gotoPage"));
    }

    @Test
    public void formatIsDetectedByContentThenByName() {
        assertEquals(DocFormat.DOCX, DocFormat.detect("whatever.bin", docx("<w:p/>")));
        assertEquals(DocFormat.XLSX, DocFormat.detect("x", xlsx()));
        assertEquals(DocFormat.ODS, DocFormat.detect("x", ods()));
        assertEquals(DocFormat.ODT, DocFormat.detect("x", odt("<text:p/>")));
        assertEquals(DocFormat.PDF, DocFormat.detect("x.dat", "%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(DocFormat.PPTX, DocFormat.detect("slides.PPTX", new byte[0]));
        try {
            DocFormat.detect("old.doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0x00});
            fail("expected DocException for legacy format");
        } catch (DocException expected) {
            assertTrue(expected.getMessage().contains("пересохраните") || expected.getMessage().contains("пересохранить")
                    || expected.getMessage().contains("DOCX"));
        }
    }
}
