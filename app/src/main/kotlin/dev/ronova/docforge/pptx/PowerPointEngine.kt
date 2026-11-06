package dev.ronova.docforge.pptx

import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentCreator
import dev.ronova.docforge.core.DocumentEngine
import dev.ronova.docforge.core.DocumentFormat
import dev.ronova.docforge.core.DocumentMetadata
import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.core.ReplaceResult
import dev.ronova.docforge.core.SlideContent
import dev.ronova.docforge.core.TextReplacer
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
import dev.ronova.docforge.ooxml.extractMedia
import dev.ronova.docforge.ooxml.isNamed
import dev.ronova.docforge.ooxml.prepareImages
import dev.ronova.docforge.ooxml.relationshipsXml
import dev.ronova.docforge.ooxml.rewriteTextElements
import dev.ronova.docforge.ooxml.rootRelsXml
import dev.ronova.docforge.util.ReplacementRules
import dev.ronova.docforge.util.blankToNull
import dev.ronova.docforge.util.escapeXml
import dev.ronova.docforge.util.toSafeFileName
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Работа с .pptx напрямую через Open XML (без Apache POI).
 * Замена текста затрагивает слайды (текстовые блоки, группы, таблицы) и заметки докладчика.
 */
class PowerPointEngine : DocumentEngine, TextReplacer, DocumentCreator {

    override val format = DocumentFormat.POWERPOINT

    // ------------------------------------------------------------------ чтение

    /** Структурированное содержимое слайдов: заголовок, текстовые блоки, заметки. */
    fun readSlides(source: ByteArray): List<SlideContent> {
        val pkg = OoxmlPackage.read(source)
        return slideParts(pkg).mapIndexed { index, part ->
            val doc = pkg.xml(part)
            val blocks = ArrayList<String>()
            var title: String? = null
            if (doc != null) {
                title = findTitle(doc)
                collectBlocks(doc, blocks)
            }
            val notesPart = pkg.relationships(part).firstOrNull { it.type.endsWith("/notesSlide") }?.target
            val notes = ArrayList<String>()
            val notesDoc = notesPart?.let { pkg.xml(it) }
            if (notesDoc != null) {
                val raw = ArrayList<String>()
                collectBlocks(notesDoc, raw)
                // номер слайда в заметках — не текст
                raw.filterNotTo(notes) { block -> block.all { it.isDigit() } }
            }
            SlideContent(number = index + 1, title = title, textBlocks = blocks, notes = notes)
        }
    }

    override fun readText(source: ByteArray): String = readSlides(source).joinToString("\n\n") { slide ->
        buildString {
            append("--- Slide ${slide.number}")
            slide.title?.let { append(": $it") }
            append(" ---")
            slide.textBlocks.forEach { append('\n').append(it) }
            if (slide.notes.isNotEmpty()) {
                append("\n[notes]")
                slide.notes.forEach { append('\n').append(it) }
            }
        }
    }

    override fun readMetadata(source: ByteArray): DocumentMetadata {
        val pkg = OoxmlPackage.read(source)
        val extra = LinkedHashMap<String, String>()
        extra["slides"] = slideParts(pkg).size.toString()
        extra["images"] = pkg.parts.keys.count { it.startsWith(MEDIA_PREFIX) }.toString()
        val size = pkg.xml(PRESENTATION)?.elements(Ns.P, "sldSz")?.firstOrNull()
        val cx = size?.getAttribute("cx")?.toLongOrNull()
        val cy = size?.getAttribute("cy")?.toLongOrNull()
        if (cx != null && cy != null) extra["pageSize"] = "${cx / EMU_PER_POINT}x${cy / EMU_PER_POINT}"
        return pkg.coreMetadata(extra)
    }

    override fun extractImages(source: ByteArray, baseName: String): List<ExtractedImage> {
        val pkg = OoxmlPackage.read(source)
        // Для каждой картинки пробуем найти слайд, который на неё ссылается
        val slideOfMedia = HashMap<String, Int>()
        slideParts(pkg).forEachIndexed { index, part ->
            for (relation in pkg.relationships(part)) {
                if (relation.type == RelType.IMAGE) slideOfMedia.putIfAbsent(relation.target, index + 1)
            }
        }
        return pkg.extractMedia(MEDIA_PREFIX, baseName.toSafeFileName()) { name ->
            slideOfMedia[name]?.let { "slide $it" } ?: "master/layout"
        }
    }

    /** Части слайдов в порядке показа (по sldIdLst); если список недоступен — по номеру в имени файла. */
    private fun slideParts(pkg: OoxmlPackage): List<String> {
        val presentation = pkg.xml(PRESENTATION)
            ?: throw DocForgeException("В файле нет ppt/presentation.xml — это не презентация PowerPoint")
        val relations = pkg.relationships(PRESENTATION).associateBy { it.id }
        val ordered = presentation.elements(Ns.P, "sldId")
            .mapNotNull { relations[it.getAttributeNS(Ns.R, "id")]?.target }
            .filter { it in pkg.parts }
        if (ordered.isNotEmpty()) return ordered
        return pkg.names(SLIDE_PARTS).sortedWith(compareBy({ it.length }, { it }))
    }

    private fun findTitle(doc: Document): String? {
        for (shape in doc.elements(Ns.P, "sp")) {
            val placeholder = shape.elements(Ns.P, "ph").firstOrNull() ?: continue
            val type = placeholder.getAttribute("type")
            if (type == "title" || type == "ctrTitle") {
                val paragraphs = shape.child(Ns.P, "txBody")?.children(Ns.A, "p") ?: continue
                val text = paragraphs.joinToString(" ") { paragraphText(it) }.blankToNull()
                if (text != null) return text
            }
        }
        return null
    }

    /** Текстовые блоки в порядке документа: фигуры, группы, таблицы (строка таблицы -> ячейки через табуляцию). */
    private fun collectBlocks(doc: Document, out: MutableList<String>) {
        val tree = doc.elements(Ns.P, "spTree").firstOrNull() ?: return
        walkShapes(tree, out)
    }

    private fun walkShapes(node: Element, out: MutableList<String>) {
        for (c in node.children()) {
            if (c.isNamed(Ns.P, "sp")) {
                val paragraphs = c.child(Ns.P, "txBody")?.children(Ns.A, "p")
                if (paragraphs != null) {
                    val text = paragraphs.joinToString("\n") { paragraphText(it) }.trim()
                    if (text.isNotEmpty()) out.add(text)
                }
            } else if (c.isNamed(Ns.P, "grpSp")) {
                walkShapes(c, out)
            } else if (c.isNamed(Ns.P, "graphicFrame")) {
                for (table in c.elements(Ns.A, "tbl")) {
                    for (row in table.children(Ns.A, "tr")) {
                        val cells = row.children(Ns.A, "tc").map { cell ->
                            cell.child(Ns.A, "txBody")?.children(Ns.A, "p")
                                ?.joinToString(" ") { paragraphText(it) }.orEmpty()
                        }
                        out.add(cells.joinToString("\t"))
                    }
                }
            }
        }
    }

    private fun paragraphText(paragraph: Element): String {
        val sb = StringBuilder()
        for (c in paragraph.children()) {
            if (c.isNamed(Ns.A, "r") || c.isNamed(Ns.A, "fld")) {
                val t = c.child(Ns.A, "t")
                if (t != null) sb.append(t.textContent)
            } else if (c.isNamed(Ns.A, "br")) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ замена текста

    override fun replaceText(source: ByteArray, replacements: Map<String, String>): ReplaceResult {
        val rules = ReplacementRules(replacements)
        val pkg = OoxmlPackage.read(source)
        var total = 0
        for (name in pkg.names(TEXT_PARTS)) {
            val doc = pkg.xml(name) ?: continue
            var count = 0
            for (paragraph in doc.elements(Ns.A, "p")) {
                count += rewriteTextElements(textRuns(paragraph), rules, preserveSpace = false)
            }
            if (count > 0) {
                pkg.putXml(name, doc)
                total += count
            }
        }
        return ReplaceResult(if (total > 0) pkg.toByteArray() else source, total)
    }

    private fun textRuns(paragraph: Element): List<Element> {
        val out = ArrayList<Element>()
        for (c in paragraph.children()) {
            if (c.isNamed(Ns.A, "r") || c.isNamed(Ns.A, "fld")) {
                val t = c.child(Ns.A, "t")
                if (t != null) out.add(t)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ создание

    /**
     * Структура: титульный слайд -> слайд с абзацами -> слайд с таблицей -> по слайду на каждую картинку.
     * Размер слайда 960x540 pt (16:9).
     */
    override fun create(content: NewDocument): ByteArray {
        val images = prepareImages(content.images)
        val slides = ArrayList<SlideSpec>()

        slides.add(
            SlideSpec(
                shapes = textBox(
                    id = 2, x = MARGIN, y = HEIGHT / 2 - 50, w = WIDTH - 2 * MARGIN, h = 100.0,
                    paragraphs = listOf(content.title), sizePt = 36, bold = true, center = true,
                ),
            )
        )
        if (content.paragraphs.isNotEmpty()) {
            slides.add(
                SlideSpec(
                    shapes = textBox(
                        id = 2, x = MARGIN, y = MARGIN, w = WIDTH - 2 * MARGIN, h = HEIGHT - 2 * MARGIN,
                        paragraphs = content.paragraphs, sizePt = 20, bold = false, center = false,
                    ),
                )
            )
        }
        if (content.table.isNotEmpty()) slides.add(SlideSpec(shapes = tableShape(content.table)))
        for (image in images) {
            val scale = minOf((WIDTH - 2 * MARGIN) / image.widthPx, (HEIGHT - 2 * MARGIN) / image.heightPx, 1.0)
            val w = image.widthPx * scale
            val h = image.heightPx * scale
            slides.add(SlideSpec(shapes = pictureShape(2, "rIdImg${image.index}", image.name, MARGIN, MARGIN, w, h), image = image))
        }

        val pkg = OoxmlPackage.create()

        val overrides = ArrayList<Pair<String, String>>()
        overrides.add(PRESENTATION to ContentType.PPT_MAIN)
        overrides.add("ppt/slideMasters/slideMaster1.xml" to ContentType.PPT_MASTER)
        overrides.add("ppt/slideLayouts/slideLayout1.xml" to ContentType.PPT_LAYOUT)
        overrides.add("ppt/theme/theme1.xml" to ContentType.THEME)
        overrides.add("docProps/core.xml" to ContentType.CORE_PROPS)
        slides.indices.forEach { overrides.add("ppt/slides/slide${it + 1}.xml" to ContentType.PPT_SLIDE) }

        pkg.put("[Content_Types].xml", contentTypesXml(overrides))
        pkg.put("_rels/.rels", rootRelsXml(PRESENTATION))
        pkg.put("docProps/core.xml", corePropsXml(content.title, content.author))

        // presentation.xml: rId1 = мастер, rId2.. = слайды, последний = тема
        val slideIds = slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>" }
        pkg.put(
            PRESENTATION,
            XML_DECL + "<p:presentation xmlns:a=\"${Ns.A}\" xmlns:r=\"${Ns.R}\" xmlns:p=\"${Ns.P}\">" +
                "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
                "<p:sldIdLst>$slideIds</p:sldIdLst>" +
                "<p:sldSz cx=\"${WIDTH.toLong() * EMU_PER_POINT}\" cy=\"${HEIGHT.toLong() * EMU_PER_POINT}\"/>" +
                "<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
        )
        val presentationRels = ArrayList<Triple<String, String, String>>()
        presentationRels.add(Triple("rId1", RelType.SLIDE_MASTER, "slideMasters/slideMaster1.xml"))
        slides.indices.forEach { presentationRels.add(Triple("rId${it + 2}", RelType.SLIDE, "slides/slide${it + 1}.xml")) }
        presentationRels.add(Triple("rId${slides.size + 2}", RelType.THEME, "theme/theme1.xml"))
        pkg.put("ppt/_rels/presentation.xml.rels", relationshipsXml(presentationRels))

        pkg.put("ppt/slideMasters/slideMaster1.xml", MASTER_XML)
        pkg.put(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            relationshipsXml(
                listOf(
                    Triple("rId1", RelType.SLIDE_LAYOUT, "../slideLayouts/slideLayout1.xml"),
                    Triple("rId2", RelType.THEME, "../theme/theme1.xml"),
                )
            ),
        )
        pkg.put("ppt/slideLayouts/slideLayout1.xml", LAYOUT_XML)
        pkg.put(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            relationshipsXml(listOf(Triple("rId1", RelType.SLIDE_MASTER, "../slideMasters/slideMaster1.xml"))),
        )
        pkg.put("ppt/theme/theme1.xml", THEME_XML)

        slides.forEachIndexed { i, slide ->
            val n = i + 1
            pkg.put(
                "ppt/slides/slide$n.xml",
                XML_DECL + "<p:sld xmlns:a=\"${Ns.A}\" xmlns:r=\"${Ns.R}\" xmlns:p=\"${Ns.P}\"><p:cSld><p:spTree>" +
                    GROUP_HEADER + slide.shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
            )
            val rels = ArrayList<Triple<String, String, String>>()
            rels.add(Triple("rId1", RelType.SLIDE_LAYOUT, "../slideLayouts/slideLayout1.xml"))
            slide.image?.let { rels.add(Triple("rIdImg${it.index}", RelType.IMAGE, "../media/${it.fileName}")) }
            pkg.put("ppt/slides/_rels/slide$n.xml.rels", relationshipsXml(rels))
        }
        images.forEach { pkg.put("$MEDIA_PREFIX${it.fileName}", it.bytes) }
        return pkg.toByteArray()
    }

    private class SlideSpec(val shapes: String, val image: EmbeddedImage? = null)

    private fun emu(points: Double): Long = (points * EMU_PER_POINT).toLong()

    private fun xfrm(tag: String, x: Double, y: Double, w: Double, h: Double): String =
        "<$tag><a:off x=\"${emu(x)}\" y=\"${emu(y)}\"/><a:ext cx=\"${emu(w)}\" cy=\"${emu(h)}\"/></$tag>"

    private fun textBox(
        id: Int, x: Double, y: Double, w: Double, h: Double,
        paragraphs: List<String>, sizePt: Int, bold: Boolean, center: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"TextBox $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
        sb.append("<p:spPr>").append(xfrm("a:xfrm", x, y, w, h))
        sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
        sb.append("<p:txBody><a:bodyPr wrap=\"square\" rtlCol=\"0\"/><a:lstStyle/>")
        for (text in paragraphs) {
            sb.append("<a:p>")
            if (center) sb.append("<a:pPr algn=\"ctr\"/>")
            sb.append("<a:r><a:rPr lang=\"ru-RU\" sz=\"${sizePt * 100}\"${if (bold) " b=\"1\"" else ""}/>")
            sb.append("<a:t>${escapeXml(text)}</a:t></a:r></a:p>")
        }
        sb.append("</p:txBody></p:sp>")
        return sb.toString()
    }

    private fun tableShape(rows: List<List<String>>): String {
        val columns = rows.maxOf { it.size }.coerceAtLeast(1)
        val columnWidth = (WIDTH - 2 * MARGIN) / columns
        val sb = StringBuilder()
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"2\" name=\"Table 2\"/>")
        sb.append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
        sb.append(xfrm("p:xfrm", MARGIN, MARGIN, WIDTH - 2 * MARGIN, ROW_HEIGHT * rows.size))
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">")
        sb.append("<a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid>")
        repeat(columns) { sb.append("<a:gridCol w=\"${emu(columnWidth)}\"/>") }
        sb.append("</a:tblGrid>")
        rows.forEachIndexed { r, values ->
            sb.append("<a:tr h=\"${emu(ROW_HEIGHT)}\">")
            for (c in 0 until columns) {
                val text = escapeXml(values.getOrElse(c) { "" })
                sb.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r>")
                sb.append("<a:rPr lang=\"ru-RU\" sz=\"1400\"${if (r == 0) " b=\"1\"" else ""}/><a:t>$text</a:t></a:r></a:p></a:txBody>")
                sb.append("<a:tcPr>")
                for (edge in listOf("lnL", "lnR", "lnT", "lnB")) {
                    sb.append("<a:$edge w=\"12700\"><a:solidFill><a:srgbClr val=\"808080\"/></a:solidFill></a:$edge>")
                }
                sb.append("</a:tcPr></a:tc>")
            }
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
        return sb.toString()
    }

    private fun pictureShape(id: Int, relId: String, name: String, x: Double, y: Double, w: Double, h: Double): String =
        "<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"${escapeXml(name)}\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr>" + xfrm("a:xfrm", x, y, w, h) + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"

    private companion object {
        const val PRESENTATION = "ppt/presentation.xml"
        const val MEDIA_PREFIX = "ppt/media/"
        const val WIDTH = 960.0
        const val HEIGHT = 540.0
        const val MARGIN = 40.0
        const val ROW_HEIGHT = 28.0
        val SLIDE_PARTS = Regex("ppt/slides/[^/]+\\.xml")
        val TEXT_PARTS = Regex("ppt/(slides|notesSlides)/[^/]+\\.xml")

        const val GROUP_HEADER =
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
                "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

        val MASTER_XML = XML_DECL +
            "<p:sldMaster xmlns:a=\"${Ns.A}\" xmlns:r=\"${Ns.R}\" xmlns:p=\"${Ns.P}\">" +
            "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
            "<p:spTree>" + GROUP_HEADER + "</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
            "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle>" +
            "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2800\"/></a:lvl1pPr></p:bodyStyle>" +
            "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles>" +
            "</p:sldMaster>"

        val LAYOUT_XML = XML_DECL +
            "<p:sldLayout xmlns:a=\"${Ns.A}\" xmlns:r=\"${Ns.R}\" xmlns:p=\"${Ns.P}\" type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\"><p:spTree>" + GROUP_HEADER + "</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

        private const val FILL = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        private fun line(width: Int) = "<a:ln w=\"$width\">$FILL</a:ln>"
        private const val FONTS = "<a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/>"
        private const val EFFECT = "<a:effectStyle><a:effectLst/></a:effectStyle>"

        val THEME_XML = XML_DECL +
            "<a:theme xmlns:a=\"${Ns.A}\" name=\"DocForge\"><a:themeElements>" +
            "<a:clrScheme name=\"DocForge\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
            "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"F58BB8\"/></a:accent1><a:accent2><a:srgbClr val=\"8EC5FF\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"B39DDB\"/></a:accent3><a:accent4><a:srgbClr val=\"FFE082\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"80CBC4\"/></a:accent5><a:accent6><a:srgbClr val=\"EF9A9A\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
            "</a:clrScheme>" +
            "<a:fontScheme name=\"DocForge\"><a:majorFont>$FONTS</a:majorFont><a:minorFont>$FONTS</a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"DocForge\">" +
            "<a:fillStyleLst>$FILL$FILL$FILL</a:fillStyleLst>" +
            "<a:lnStyleLst>${line(6350)}${line(12700)}${line(19050)}</a:lnStyleLst>" +
            "<a:effectStyleLst>$EFFECT$EFFECT$EFFECT</a:effectStyleLst>" +
            "<a:bgFillStyleLst>$FILL$FILL$FILL</a:bgFillStyleLst>" +
            "</a:fmtScheme></a:themeElements></a:theme>"
    }
}
