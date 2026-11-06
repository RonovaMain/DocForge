package dev.ronova.docforge

import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentCreator
import dev.ronova.docforge.core.DocumentEngine
import dev.ronova.docforge.core.ImageData
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.core.TextReplacer
import dev.ronova.docforge.excel.ExcelEngine
import dev.ronova.docforge.ooxml.OoxmlPackage
import dev.ronova.docforge.pptx.PowerPointEngine
import dev.ronova.docforge.util.ReplacementRules
import dev.ronova.docforge.util.imageSizeOrNull
import dev.ronova.docforge.word.WordEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeRoundTripTest {

    // Генерируем минимальные валидные заголовки картинок вручную байт в байт, чтобы не зависеть от java.awt
    private fun image(format: String, width: Int = 60, height: Int = 40): ByteArray {
        return when (format.lowercase()) {
            "png" -> {
                // Сигнатура PNG + заголовок IHDR (где на позициях 16-23 лежат Width и Height)
                val bytes = ByteArray(33)
                System.arraycopy(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), 0, bytes, 0, 8)
                System.arraycopy(byteArrayOf(0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52), 0, bytes, 8, 8)
                bytes[16] = (width shr 24).toByte(); bytes[17] = (width shr 16).toByte()
                bytes[18] = (width shr 8).toByte();  bytes[19] = width.toByte()
                bytes[20] = (height shr 24).toByte(); bytes[21] = (height shr 16).toByte()
                bytes[22] = (height shr 8).toByte();  bytes[23] = height.toByte()
                bytes
            }
            "gif" -> {
                // Сигнатура GIF89a + логический экран (Ширина и Высота в Little-Endian на позициях 6-9)
                val bytes = ByteArray(10)
                System.arraycopy(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61), 0, bytes, 0, 6)
                bytes[6] = width.toByte();  bytes[7] = (width shr 8).toByte()
                bytes[8] = height.toByte(); bytes[9] = (height shr 8).toByte()
                bytes
            }
            "jpg", "jpeg" -> {
                // Маркер SOI + маркер SOF0 (размеры на позициях 5-8 в Big-Endian: сначала высота, потом ширина)
                val bytes = ByteArray(9)
                System.arraycopy(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x09, 0x08), 0, bytes, 0, 7)
                bytes[5] = (height shr 8).toByte(); bytes[6] = height.toByte()
                bytes[7] = (width shr 8).toByte();  bytes[8] = width.toByte()
                bytes
            }
            else -> ByteArray(0)
        }
    }

    private val template = NewDocument(
        title = "Report {{month}}",
        paragraphs = listOf("Client: {{client}}", "Fish & <chips>"),
        table = listOf(listOf("Item", "Qty"), listOf("A", "2")),
        images = listOf(ImageData("logo.png", image("png"))),
    )
    private val replacements = mapOf("{{month}}" to "November", "{{client}}" to "ACME")

    private fun <E> roundTrip(engine: E) where E : DocumentEngine, E : TextReplacer, E : DocumentCreator {
        val created = engine.create(template)
        val text = engine.readText(created)
        assertTrue(text, text.contains("{{client}}"))
        assertTrue(text, text.contains("Fish & <chips>"))

        val result = engine.replaceText(created, replacements)
        assertEquals(2, result.count)
        val edited = engine.readText(result.bytes)
        assertTrue(edited, edited.contains("Client: ACME"))
        assertTrue(edited, edited.contains("Report November"))

        val images = engine.extractImages(result.bytes, "report")
        assertEquals(1, images.size)
        assertEquals("report_img001.png", images.single().name)
        assertEquals(60, images.single().width)
        assertEquals(40, images.single().height)

        assertNotNull(engine.readMetadata(created).title)
    }

    @Test fun `docx - create, read, replace, extract`() = roundTrip(WordEngine())

    @Test fun `xlsx - create, read, replace, extract`() = roundTrip(ExcelEngine())

    @Test fun `pptx - create, read, replace, extract`() = roundTrip(PowerPointEngine())

    @Test fun `nothing replaced returns the original bytes`() {
        val engine = WordEngine()
        val created = engine.create(template)
        val result = engine.replaceText(created, mapOf("нет такого" to "x"))
        assertEquals(0, result.count)
        assertTrue(created === result.bytes)
    }

    @Test fun `docx - placeholder split across runs is replaced`() {
        val pkg = OoxmlPackage.create()
        pkg.put(
            "word/document.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Hel</w:t></w:r><w:r><w:t>lo {{na</w:t></w:r><w:r><w:t>me}} end</w:t></w:r></w:p>
            </w:body></w:document>""".trimIndent(),
        )
        val engine = WordEngine()
        val result = engine.replaceText(pkg.toByteArray(), mapOf("{{name}}" to "Bob"))
        assertEquals(1, result.count)
        assertEquals("Hello Bob end", engine.readText(result.bytes))
    }

    @Test fun `docx - tab stops in paragraph properties are not text`() {
        val pkg = OoxmlPackage.create()
        pkg.put(
            "word/document.xml",
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>one</w:t></w:r><w:r><w:tab/><w:t>two</w:t></w:r></w:p>
            </w:body></w:document>""".trimIndent(),
        )
        assertEquals("one\ttwo", WordEngine().readText(pkg.toByteArray()))
    }

    @Test fun `xlsx - shared strings are counted per cell`() {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val pkg = OoxmlPackage.create()
        pkg.put("xl/workbook.xml", """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        pkg.put(
            "xl/_rels/workbook.xml.rels",
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>""",
        )
        pkg.put("xl/sharedStrings.xml", """<sst xmlns="$ns"><si><t>Hello {{name}}</t></si><si><r><t>Plain</t></r></si></sst>""")
        pkg.put(
            "xl/worksheets/sheet1.xml",
            """<worksheet xmlns="$ns"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
            <row r="3"><c r="B3" t="s"><v>0</v></c><c r="C3"><v>42</v></c><c r="D3" t="b"><v>1</v></c></row>
            </sheetData></worksheet>""",
        )
        val engine = ExcelEngine()
        val before = engine.readSheets(pkg.toByteArray()).single()
        assertEquals(listOf("Hello {{name}}", "", "Plain"), before.rows[0])
        assertEquals(listOf("", "Hello {{name}}", "42", "TRUE"), before.rows[2])

        val result = engine.replaceText(pkg.toByteArray(), mapOf("{{name}}" to "Bob"))
        assertEquals(2, result.count) 
        assertEquals("Hello Bob", engine.readSheets(result.bytes).single().rows[0][0])
    }

    @Test fun `not an office file gives a readable error`() {
        try {
            WordEngine().readText("just text".toByteArray())
            throw AssertionError("expected DocForgeException")
        } catch (e: DocForgeException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test fun `replacement rules - longest key wins and empty map is rejected`() {
        val rules = ReplacementRules(mapOf("{{n" to "X", "{{name}}" to "Bob"))
        assertEquals("Bob", rules.replaceIn("{{name}}").first)
        try {
            ReplacementRules(emptyMap())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test fun `image size is read from png jpeg and gif headers`() {
        assertEquals(60 to 40, image("png").imageSizeOrNull())
        assertEquals(33 to 17, image("jpg", 33, 17).imageSizeOrNull())
        assertEquals(20 to 10, image("gif", 20, 10).imageSizeOrNull())
    }
}
