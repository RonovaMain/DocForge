package dev.ronova.docforge.word

import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentCreator
import dev.ronova.docforge.core.DocumentEngine
import dev.ronova.docforge.core.DocumentFormat
import dev.ronova.docforge.core.DocumentMetadata
import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.core.ReplaceResult
import dev.ronova.docforge.core.TextReplacer
import dev.ronova.docforge.image.ImageCollector
import dev.ronova.docforge.ooxml.ContentType
import dev.ronova.docforge.ooxml.EMU_PER_POINT
import dev.ronova.docforge.ooxml.EmbeddedImage
import dev.ronova.docforge.ooxml.Ns
import dev.ronova.docforge.ooxml.OoxmlPackage
import dev.ronova.docforge.ooxml.RelType
import dev.ronova.docforge.ooxml.XML_DECL
import dev.ronova.docforge.ooxml.child
import dev.ronova.docforge.ooxml.children
import dev.ronova.docforge.ooxml.contentTypesXml
import dev.ronova.docforge.ooxml.corePropsXml
import dev.ronova.docforge.ooxml.elements
import dev.ronova.docforge.ooxml.isNamed
import dev.ronova.docforge.ooxml.prepareImages
import dev.ronova.docforge.ooxml.relationshipsXml
import dev.ronova.docforge.ooxml.rewriteTextElements
import dev.ronova.docforge.ooxml.rootRelsXml
import dev.ronova.docforge.util.ReplacementRules
import dev.ronova.docforge.util.escapeXml
import dev.ronova.docforge.util.toSafeFileName
import org.w3c.dom.Element

/**
 * Работа с .docx напрямую через Open XML (ZIP + DOM), без Apache POI — POI не работает на Android.
 *
 * Замена текста обрабатывает тело, таблицы, колонтитулы, сноски, гиперссылки и текстовые поля:
 * ищется текст абзаца целиком, поэтому ключ, разбитый Word на несколько «ранов», тоже находится.
 */
class WordEngine : DocumentEngine, TextReplacer, DocumentCreator {

    override val format = DocumentFormat.WORD

    // ------------------------------------------------------------------ чтение

    override fun readText(source: ByteArray): String {
        val pkg = OoxmlPackage.read(source)
        val doc = pkg.xml(MAIN_PART) ?: throw DocForgeException("В файле нет word/document.xml — это не документ Word")
        val body = doc.elements(Ns.W, "body").firstOrNull() ?: return ""
        return blockLines(body).joinToString("\n").trim()
    }

    override fun readMetadata(source: ByteArray): DocumentMetadata {
        val pkg = OoxmlPackage.read(source)
        val doc = pkg.xml(MAIN_PART)
        val extra = LinkedHashMap<String, String>()
        extra["paragraphs"] = (doc?.elements(Ns.W, "p")?.size ?: 0).toString()
        extra["tables"] = (doc?.elements(Ns.W, "tbl")?.size ?: 0).toString()
        extra["images"] = pkg.parts.keys.count { it.startsWith(MEDIA_PREFIX) }.toString()
        return pkg.coreMetadata(extra)
    }

    override fun extractImages(source: ByteArray, baseName: String): List<ExtractedImage> {
        val pkg = OoxmlPackage.read(source)
        return pkg.extractMedia(MEDIA_PREFIX, baseName.toSafeFileName()) { it }
    }

    /** Абзац -> строка; строка таблицы -> ячейки через табуляцию. */
    private fun blockLines(container: Element): List<String> {
        val out = ArrayList<String>()
        for (child in container.children()) {
            if (child.isNamed(Ns.W, "p")) {
                out.add(paragraphText(child))
            } else if (child.isNamed(Ns.W, "tbl")) {
                for (row in child.children(Ns.W, "tr")) {
                    val cells = row.children(Ns.W, "tc").map { cell -> blockLines(cell).joinToString(" ") }
                    out.add(cells.joinToString("\t"))
                }
            } else if (child.isNamed(Ns.W, "sdt")) {
                val content = child.child(Ns.W, "sdtContent")
                if (content != null) out.addAll(blockLines(content))
            }
        }
        return out
    }

    private fun paragraphText(paragraph: Element): String {
        val sb = StringBuilder()
        appendText(paragraph, sb)
        return sb.toString()
    }

    private fun appendText(node: Element, sb: StringBuilder) {
        for (c in node.children()) {
            when {
                c.isNamed(Ns.W, "t") -> sb.append(c.textContent)
                c.isNamed(Ns.W, "tab") -> sb.append('\t')
                c.isNamed(Ns.W, "br") || c.isNamed(Ns.W, "cr") -> sb.append('\n')
                c.isNamed(Ns.W, "noBreakHyphen") -> sb.append('-')
                // свойства (pPr/rPr содержат w:tab как позиции табуляции) и вложенные абзацы (текстовые поля) пропускаем
                c.isNamed(Ns.W, "pPr") || c.isNamed(Ns.W, "rPr") || c.isNamed(Ns.W, "p") -> Unit
                else -> appendText(c, sb)
            }
        }
    }

    // ------------------------------------------------------------------ замена текста

    override fun replaceText(source: ByteArray, replacements: Map<String, String>): ReplaceResult {
        val rules = ReplacementRules(replacements)
        val pkg = OoxmlPackage.read(source)
        var total = 0
        for (name in pkg.names(TEXT_PARTS)) {
            val doc = pkg.xml(name) ?: continue
            var count = 0
            for (paragraph in doc.elements(Ns.W, "p")) {
                count += rewriteTextElements(textRuns(paragraph), rules, preserveSpace = true)
            }
            if (count > 0) {
                pkg.putXml(name, doc)
                total += count
            }
        }
        return ReplaceResult(if (total > 0) pkg.toByteArray() else source, total)
    }

    /** Элементы w:t абзаца, не заходя во вложенные абзацы (их обработает внешний цикл). */
    private fun textRuns(paragraph: Element): List<Element> {
        val out = ArrayList<Element>()
        collectRuns(paragraph, out)
        return out
    }

    private fun collectRuns(node: Element, out: MutableList<Element>) {
        for (c in node.children()) {
            if (c.isNamed(Ns.W, "p")) continue
            if (c.isNamed(Ns.W, "t")) out.add(c) else collectRuns(c, out)
        }
    }

    // ------------------------------------------------------------------ создание

    override fun create(content: NewDocument): ByteArray {
        val images = prepareImages(content.images)
        val pkg = OoxmlPackage.create()

        pkg.put(
            "[Content_Types].xml",
            contentTypesXml(
                listOf(
                    MAIN_PART to ContentType.WORD_MAIN,
                    "docProps/core.xml" to ContentType.CORE_PROPS,
                )
            ),
        )
        pkg.put("_rels/.rels", rootRelsXml(MAIN_PART))
        pkg.put("docProps/core.xml", corePropsXml(content.title, content.author))

        val body = StringBuilder()
        body.append(paragraph(content.title, bold = true, sizeHalfPoints = 40))
        content.paragraphs.forEach { body.append(paragraph(it)) }
        if (content.table.isNotEmpty()) {
            body.append(table(content.table))
            body.append("<w:p/>") // после таблицы Word требует абзац
        }
        images.forEach { body.append(imageParagraph(it)) }

        val document = XML_DECL +
            "<w:document xmlns:w=\"${Ns.W}\" xmlns:r=\"${Ns.R}\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"${Ns.A}\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
        pkg.put(MAIN_PART, document)

        if (images.isNotEmpty()) {
            pkg.put(
                "word/_rels/document.xml.rels",
                relationshipsXml(images.map { Triple("rIdImg${it.index}", RelType.IMAGE, "media/${it.fileName}") }),
            )
            images.forEach { pkg.put("$MEDIA_PREFIX${it.fileName}", it.bytes) }
        }
        return pkg.toByteArray()
    }

    private fun paragraph(text: String, bold: Boolean = false, sizeHalfPoints: Int? = null): String {
        val props = StringBuilder()
        if (bold) props.append("<w:b/>")
        if (sizeHalfPoints != null) props.append("<w:sz w:val=\"$sizeHalfPoints\"/>")
        val rPr = if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        return "<w:p><w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"
    }

    private fun table(rows: List<List<String>>): String {
        val columns = rows.maxOf { it.size }.coerceAtLeast(1)
        val cellWidth = 9000 / columns
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
        repeat(columns) { sb.append("<w:gridCol w:w=\"$cellWidth\"/>") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { r, values ->
            sb.append("<w:tr>")
            for (c in 0 until columns) {
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$cellWidth\" w:type=\"dxa\"/></w:tcPr>")
                sb.append(paragraph(values.getOrElse(c) { "" }, bold = r == 0)) // первая строка — заголовок
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun imageParagraph(image: EmbeddedImage): String {
        val scale = minOf(1.0, MAX_IMAGE_WIDTH_PT / image.widthPx)
        val cx = (image.widthPx * scale * EMU_PER_POINT).toLong()
        val cy = (image.heightPx * scale * EMU_PER_POINT).toLong()
        val title = escapeXml(image.name)
        return "<w:p><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"${image.index}\" name=\"$title\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"${image.index}\" name=\"$title\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg${image.index}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private companion object {
        const val MAIN_PART = "word/document.xml"
        const val MEDIA_PREFIX = "word/media/"
        const val MAX_IMAGE_WIDTH_PT = 400.0
        val TEXT_PARTS = Regex("word/(document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml")
    }
}
