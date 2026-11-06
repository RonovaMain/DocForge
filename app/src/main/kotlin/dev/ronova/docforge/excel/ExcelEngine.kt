package dev.ronova.docforge.excel

import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentCreator
import dev.ronova.docforge.core.DocumentEngine
import dev.ronova.docforge.core.DocumentFormat
import dev.ronova.docforge.core.DocumentMetadata
import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.core.ReplaceResult
import dev.ronova.docforge.core.SheetData
import dev.ronova.docforge.core.TextReplacer
import dev.ronova.docforge.ooxml.ContentType
import dev.ronova.docforge.ooxml.EMU_PER_POINT
import dev.ronova.docforge.ooxml.Ns
import dev.ronova.docforge.ooxml.OoxmlPackage
import dev.ronova.docforge.ooxml.RelType
import dev.ronova.docforge.ooxml.XML_DECL
import dev.ronova.docforge.ooxml.child
import dev.ronova.docforge.ooxml.children
import dev.ronova.docforge.ooxml.contentTypesXml
import dev.ronova.docforge.ooxml.corePropsXml
import dev.ronova.docforge.ooxml.elements
import dev.ronova.docforge.ooxml.extractMedia
import dev.ronova.docforge.ooxml.isNamed
import dev.ronova.docforge.ooxml.prepareImages
import dev.ronova.docforge.ooxml.relationshipsXml
import dev.ronova.docforge.ooxml.rewriteTextElements
import dev.ronova.docforge.ooxml.rootRelsXml
import dev.ronova.docforge.util.ReplacementRules
import dev.ronova.docforge.util.escapeXml
import dev.ronova.docforge.util.toSafeFileName
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Работа с .xlsx напрямую через Open XML (без Apache POI).
 *
 * Значения читаются так, как записаны в файле: формулы — по сохранённому результату,
 * даты — числом-серийником (формат ячеек не применяется). Заменяется текст в общих и встроенных строках.
 */
class ExcelEngine : DocumentEngine, TextReplacer, DocumentCreator {

    override val format = DocumentFormat.EXCEL

    // ------------------------------------------------------------------ чтение

    /** Все листы; значения — как в файле (кэшированный результат формул). */
    fun readSheets(source: ByteArray): List<SheetData> {
        val pkg = OoxmlPackage.read(source)
        val shared = sharedStrings(pkg)
        return sheetRefs(pkg).map { (name, part) -> SheetData(name, parseSheet(pkg.xml(part), shared)) }
    }

    override fun readText(source: ByteArray): String = readSheets(source).joinToString("\n\n") { sheet ->
        "# ${sheet.name}\n" + sheet.rows.joinToString("\n") { it.joinToString("\t") }
    }.trim()

    override fun readMetadata(source: ByteArray): DocumentMetadata {
        val pkg = OoxmlPackage.read(source)
        val extra = LinkedHashMap<String, String>()
        val sheets = sheetRefs(pkg)
        extra["sheets"] = sheets.size.toString()
        extra["sheetNames"] = sheets.joinToString(", ") { it.first }
        extra["images"] = pkg.parts.keys.count { it.startsWith(MEDIA_PREFIX) }.toString()
        return pkg.coreMetadata(extra)
    }

    override fun extractImages(source: ByteArray, baseName: String): List<ExtractedImage> {
        val pkg = OoxmlPackage.read(source)
        return pkg.extractMedia(MEDIA_PREFIX, baseName.toSafeFileName()) { it }
    }

    /** (имя листа, имя части) в порядке следования листов. */
    private fun sheetRefs(pkg: OoxmlPackage): List<Pair<String, String>> {
        val workbook = pkg.xml(WORKBOOK) ?: throw DocForgeException("В файле нет xl/workbook.xml — это не книга Excel")
        val relations = pkg.relationships(WORKBOOK).associateBy { it.id }
        val result = ArrayList<Pair<String, String>>()
        for (sheet in workbook.elements(Ns.S, "sheet")) {
            val target = relations[sheet.getAttributeNS(Ns.R, "id")]?.target ?: continue
            result.add(sheet.getAttribute("name") to target)
        }
        return result
    }

    private fun sharedStrings(pkg: OoxmlPackage): List<String> =
        pkg.xml(SHARED_STRINGS)?.elements(Ns.S, "si")?.map { stringItemText(it) }.orEmpty()

    /** Текст элемента si / is: обычный t или набор r/t (rPh — фонетические подсказки — пропускаем). */
    private fun stringItemText(item: Element): String {
        val sb = StringBuilder()
        for (c in item.children()) {
            if (c.isNamed(Ns.S, "t")) {
                sb.append(c.textContent)
            } else if (c.isNamed(Ns.S, "r")) {
                val t = c.child(Ns.S, "t")
                if (t != null) sb.append(t.textContent)
            }
        }
        return sb.toString()
    }

    private fun parseSheet(doc: Document?, shared: List<String>): List<List<String>> {
        if (doc == null) return emptyList()
        val rows = ArrayList<MutableList<String>>()
        for (rowElement in doc.elements(Ns.S, "row")) {
            val rowIndex = rowElement.getAttribute("r").toIntOrNull()?.minus(1) ?: rows.size
            if (rowIndex < 0 || rowIndex >= MAX_ROWS) continue
            while (rows.size <= rowIndex) rows.add(ArrayList())
            val cells = rows[rowIndex]
            var nextColumn = 0
            for (cell in rowElement.children(Ns.S, "c")) {
                val column = columnIndex(cell.getAttribute("r")) ?: nextColumn
                nextColumn = column + 1
                if (column >= MAX_COLUMNS) continue
                while (cells.size <= column) cells.add("")
                cells[column] = cellValue(cell, shared)
            }
        }
        return rows
    }

    private fun cellValue(cell: Element, shared: List<String>): String {
        val value = cell.child(Ns.S, "v")?.textContent.orEmpty()
        return when (cell.getAttribute("t")) {
            "s" -> shared.getOrElse(value.trim().toIntOrNull() ?: -1) { "" }
            "inlineStr" -> cell.child(Ns.S, "is")?.let { stringItemText(it) }.orEmpty()
            "b" -> if (value.trim() == "1") "TRUE" else "FALSE"
            else -> value
        }
    }

    /** "AB12" -> 27 (нумерация с нуля); null, если в адресе нет букв. */
    private fun columnIndex(reference: String): Int? {
        var n = 0
        var letters = 0
        for (ch in reference) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
                letters++
            } else {
                break
            }
        }
        return if (letters == 0) null else n - 1
    }

    // ------------------------------------------------------------------ замена текста

    override fun replaceText(source: ByteArray, replacements: Map<String, String>): ReplaceResult {
        val rules = ReplacementRules(replacements)
        val pkg = OoxmlPackage.read(source)
        var total = 0

        // Сколько ячеек ссылается на каждую общую строку — чтобы считать замены по ячейкам, как в Excel
        val usage = HashMap<Int, Int>()
        for (name in pkg.names(SHEET_PARTS)) {
            val doc = pkg.xml(name) ?: continue
            var changed = false
            for (cell in doc.elements(Ns.S, "c")) {
                if (cell.getAttribute("t") == "s") {
                    val index = cell.child(Ns.S, "v")?.textContent?.trim()?.toIntOrNull()
                    if (index != null) usage[index] = (usage[index] ?: 0) + 1
                }
            }
            for (inline in doc.elements(Ns.S, "is")) {
                val n = rewriteTextElements(stringRuns(inline), rules, preserveSpace = true)
                if (n > 0) {
                    total += n
                    changed = true
                }
            }
            if (changed) pkg.putXml(name, doc)
        }

        val sharedDoc = pkg.xml(SHARED_STRINGS)
        if (sharedDoc != null) {
            var changed = false
            sharedDoc.elements(Ns.S, "si").forEachIndexed { index, item ->
                val n = rewriteTextElements(stringRuns(item), rules, preserveSpace = true)
                if (n > 0) {
                    total += n * (usage[index] ?: 1)
                    changed = true
                }
            }
            if (changed) pkg.putXml(SHARED_STRINGS, sharedDoc)
        }
        return ReplaceResult(if (total > 0) pkg.toByteArray() else source, total)
    }

    private fun stringRuns(item: Element): List<Element> {
        val out = ArrayList<Element>()
        for (c in item.children()) {
            if (c.isNamed(Ns.S, "t")) {
                out.add(c)
            } else if (c.isNamed(Ns.S, "r")) {
                val t = c.child(Ns.S, "t")
                if (t != null) out.add(t)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ создание

    override fun create(content: NewDocument): ByteArray {
        val images = prepareImages(content.images)
        val pkg = OoxmlPackage.create()

        val overrides = ArrayList<Pair<String, String>>()
        overrides.add(WORKBOOK to ContentType.EXCEL_MAIN)
        overrides.add("xl/worksheets/sheet1.xml" to ContentType.EXCEL_SHEET)
        overrides.add("xl/styles.xml" to ContentType.EXCEL_STYLES)
        overrides.add("docProps/core.xml" to ContentType.CORE_PROPS)
        if (images.isNotEmpty()) overrides.add("xl/drawings/drawing1.xml" to ContentType.DRAWING_ML)

        pkg.put("[Content_Types].xml", contentTypesXml(overrides))
        pkg.put("_rels/.rels", rootRelsXml(WORKBOOK))
        pkg.put("docProps/core.xml", corePropsXml(content.title, content.author))
        pkg.put(
            WORKBOOK,
            XML_DECL + "<workbook xmlns=\"${Ns.S}\" xmlns:r=\"${Ns.R}\">" +
                "<sheets><sheet name=\"$DEFAULT_SHEET\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        )
        pkg.put(
            "xl/_rels/workbook.xml.rels",
            relationshipsXml(
                listOf(
                    Triple("rId1", RelType.WORKSHEET, "worksheets/sheet1.xml"),
                    Triple("rId2", RelType.STYLES, "styles.xml"),
                )
            ),
        )
        pkg.put("xl/styles.xml", STYLES_XML)

        // Лист: заголовок (жирный), абзацы, пустая строка, таблица
        val rows = StringBuilder()
        var rowIndex = 1
        rows.append(row(rowIndex++, listOf(content.title), boldRow = true))
        content.paragraphs.forEach { rows.append(row(rowIndex++, listOf(it), boldRow = false)) }
        if (content.table.isNotEmpty()) rowIndex++
        content.table.forEachIndexed { r, values -> rows.append(row(rowIndex++, values, boldRow = r == 0)) }

        val columns = content.table.maxOfOrNull { it.size } ?: 1
        val drawingTag = if (images.isEmpty()) "" else "<drawing r:id=\"rId1\"/>"
        pkg.put(
            "xl/worksheets/sheet1.xml",
            XML_DECL + "<worksheet xmlns=\"${Ns.S}\" xmlns:r=\"${Ns.R}\">" +
                "<cols><col min=\"1\" max=\"${columns.coerceAtLeast(1)}\" width=\"28\" customWidth=\"1\"/></cols>" +
                "<sheetData>$rows</sheetData>$drawingTag</worksheet>",
        )

        if (images.isNotEmpty()) {
            pkg.put(
                "xl/worksheets/_rels/sheet1.xml.rels",
                relationshipsXml(listOf(Triple("rId1", RelType.DRAWING, "../drawings/drawing1.xml"))),
            )
            pkg.put("xl/drawings/drawing1.xml", drawingXml(images, columns))
            pkg.put(
                "xl/drawings/_rels/drawing1.xml.rels",
                relationshipsXml(images.map { Triple("rIdImg${it.index}", RelType.IMAGE, "../media/${it.fileName}") }),
            )
            images.forEach { pkg.put("$MEDIA_PREFIX${it.fileName}", it.bytes) }
        }
        return pkg.toByteArray()
    }

    /** Строка листа со встроенными строками (inlineStr) и числами; жирный стиль — s="1". */
    private fun row(index: Int, values: List<String>, boldRow: Boolean): String {
        val sb = StringBuilder("<row r=\"$index\">")
        values.forEachIndexed { c, text ->
            val ref = columnName(c) + index
            val style = if (boldRow) " s=\"1\"" else ""
            val number = text.toDoubleOrNull()
            if (number != null && text.trim().isNotEmpty() && !boldRow) {
                sb.append("<c r=\"$ref\"$style><v>${text.trim()}</v></c>")
            } else {
                sb.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${escapeXml(text)}</t></is></c>")
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun drawingXml(images: List<dev.ronova.docforge.ooxml.EmbeddedImage>, columns: Int): String {
        val sb = StringBuilder()
        sb.append(XML_DECL)
        sb.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" ")
        sb.append("xmlns:a=\"${Ns.A}\" xmlns:r=\"${Ns.R}\">")
        var anchorRow = 1
        for (image in images) {
            val cx = (image.widthPx * EMU_PER_POINT * 0.75).toLong()
            val cy = (image.heightPx * EMU_PER_POINT * 0.75).toLong()
            sb.append("<xdr:oneCellAnchor><xdr:from><xdr:col>${columns + 1}</xdr:col><xdr:colOff>0</xdr:colOff>")
            sb.append("<xdr:row>$anchorRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
            sb.append("<xdr:ext cx=\"$cx\" cy=\"$cy\"/>")
            sb.append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${image.index + 1}\" name=\"${escapeXml(image.name)}\"/>")
            sb.append("<xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
            sb.append("<xdr:blipFill><a:blip r:embed=\"rIdImg${image.index}\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
            sb.append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
            anchorRow += IMAGE_ROW_STEP
        }
        sb.append("</xdr:wsDr>")
        return sb.toString()
    }

    private companion object {
        const val WORKBOOK = "xl/workbook.xml"
        const val SHARED_STRINGS = "xl/sharedStrings.xml"
        const val MEDIA_PREFIX = "xl/media/"
        const val DEFAULT_SHEET = "Sheet1"
        const val IMAGE_ROW_STEP = 10
        const val MAX_ROWS = 50_000
        const val MAX_COLUMNS = 1_000
        val SHEET_PARTS = Regex("xl/worksheets/[^/]+\\.xml")

        val STYLES_XML = XML_DECL +
            "<styleSheet xmlns=\"${Ns.S}\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
    }
}
