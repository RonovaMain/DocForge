package dev.ronova.docforge.ooxml

import dev.ronova.docforge.core.ImageData
import dev.ronova.docforge.util.detectImageExtension
import dev.ronova.docforge.util.escapeXml
import dev.ronova.docforge.util.imageSizeOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Общие заготовки для создания новых пакетов Open XML. */
internal const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

internal object RelType {
    private const val BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val OFFICE_DOCUMENT = "$BASE/officeDocument"
    const val CORE_PROPS = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
    const val IMAGE = "$BASE/image"
    const val STYLES = "$BASE/styles"
    const val WORKSHEET = "$BASE/worksheet"
    const val DRAWING = "$BASE/drawing"
    const val SLIDE = "$BASE/slide"
    const val SLIDE_MASTER = "$BASE/slideMaster"
    const val SLIDE_LAYOUT = "$BASE/slideLayout"
    const val THEME = "$BASE/theme"
}

internal object ContentType {
    const val CORE_PROPS = "application/vnd.openxmlformats-package.core-properties+xml"
    const val DRAWING_ML = "application/vnd.openxmlformats-officedocument.drawing+xml"
    const val THEME = "application/vnd.openxmlformats-officedocument.theme+xml"
    const val WORD_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    const val EXCEL_MAIN = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    const val EXCEL_SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
    const val EXCEL_STYLES = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"
    const val PPT_MAIN = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    const val PPT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    const val PPT_MASTER = "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    const val PPT_LAYOUT = "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
}

/** [Content_Types].xml: расширения картинок объявлены всегда, остальное — через [overrides] (имя части -> тип). */
internal fun contentTypesXml(overrides: List<Pair<String, String>>): String = buildString {
    append(XML_DECL)
    append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
    append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
    append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
    append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
    append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
    append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>")
    for ((part, type) in overrides) append("<Override PartName=\"/$part\" ContentType=\"$type\"/>")
    append("</Types>")
}

/** Файл связей: список (id, тип, цель). */
internal fun relationshipsXml(rels: List<Triple<String, String, String>>): String = buildString {
    append(XML_DECL)
    append("<Relationships xmlns=\"${Ns.PKG_REL}\">")
    for ((id, type, target) in rels) append("<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>")
    append("</Relationships>")
}

/** Корневые связи пакета: основной документ + core-свойства. */
internal fun rootRelsXml(mainPart: String): String = relationshipsXml(
    listOf(
        Triple("rId1", RelType.OFFICE_DOCUMENT, mainPart),
        Triple("rId2", RelType.CORE_PROPS, "docProps/core.xml"),
    )
)

internal fun corePropsXml(title: String, author: String): String {
    val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    return XML_DECL +
        "<cp:coreProperties xmlns:cp=\"${Ns.CP}\" xmlns:dc=\"${Ns.DC}\" xmlns:dcterms=\"${Ns.DCTERMS}\" " +
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
        "<dc:title>${escapeXml(title)}</dc:title>" +
        "<dc:creator>${escapeXml(author)}</dc:creator>" +
        "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
        "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified>" +
        "</cp:coreProperties>"
}

/** Картинка, подготовленная к вставке: размер в пикселях определён, формат проверен. */
internal class EmbeddedImage(
    val index: Int,
    val name: String,
    val extension: String,
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    val fileName: String get() = "image$index.$extension"
}

/** Проверяет, что картинки — PNG / JPEG / GIF, и нумерует их с 1. */
internal fun prepareImages(images: List<ImageData>): List<EmbeddedImage> =
    images.mapIndexed { i, image ->
        val ext = image.bytes.detectImageExtension()
        require(ext == "png" || ext == "jpg" || ext == "gif") {
            "Вставлять можно только PNG, JPEG и GIF: ${image.name}"
        }
        val (w, h) = image.bytes.imageSizeOrNull() ?: (300 to 200)
        EmbeddedImage(i + 1, image.name, ext, image.bytes, w, h)
    }

internal const val EMU_PER_POINT = 12700
