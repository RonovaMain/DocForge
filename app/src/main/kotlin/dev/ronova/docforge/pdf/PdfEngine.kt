package dev.ronova.docforge.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentCreator
import dev.ronova.docforge.core.DocumentEngine
import dev.ronova.docforge.core.DocumentFormat
import dev.ronova.docforge.core.DocumentMetadata
import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.core.NewDocument
import dev.ronova.docforge.image.ImageCollector
import dev.ronova.docforge.util.blankToNull
import dev.ronova.docforge.util.toSafeFileName
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/** Параметры водяного знака. */
data class WatermarkOptions(
    val fontSize: Float = 64f,
    /** 0.0 (невидимый) .. 1.0 (непрозрачный). */
    val opacity: Float = 0.25f,
    val rotationDegrees: Double = 45.0,
    /** Серый по умолчанию; значения компонентов 0..255. */
    val red: Int = 128,
    val green: Int = 128,
    val blue: Int = 128,
)

/**
 * Работа с .pdf на базе pdfbox-android (Android-порт Apache PDFBox 2.x).
 *
 * Перед использованием нужно один раз вызвать `PDFBoxResourceLoader.init(context)` (делает DocForgeApp).
 *
 * Заменять текст в PDF «по месту» нельзя: PDF хранит позиционированные глифы, а не абзацы.
 * Поэтому здесь нет [dev.ronova.docforge.core.TextReplacer]; вместо этого есть водяные знаки.
 *
 * Кириллица: [fontProvider] отдаёт юникодный TTF (DejaVu Sans из assets). Если шрифта нет,
 * используется Helvetica, а неподдерживаемые символы заменяются на «?».
 */
class PdfEngine(private val fontProvider: () -> InputStream?) : DocumentEngine, DocumentCreator {

    override val format = DocumentFormat.PDF

    // ------------------------------------------------------------------ чтение

    override fun readText(source: ByteArray): String = load(source).use { doc ->
        PDFTextStripper().apply {
            sortByPosition = true
            startPage = 1
            endPage = doc.numberOfPages
        }.getText(doc).replace("\r\n", "\n").trim()
    }

    override fun readMetadata(source: ByteArray): DocumentMetadata = load(source).use { doc ->
        val info = doc.documentInformation
        val extra = LinkedHashMap<String, String>()
        extra["pages"] = doc.numberOfPages.toString()
        extra["pdfVersion"] = doc.version.toString()
        extra["encrypted"] = doc.isEncrypted.toString()
        info.producer.blankToNull()?.let { extra["producer"] = it }
        info.creator.blankToNull()?.let { extra["creator"] = it }
        DocumentMetadata(
            title = info.title.blankToNull(),
            author = info.author.blankToNull(),
            subject = info.subject.blankToNull(),
            keywords = info.keywords.blankToNull(),
            created = info.creationDate?.let { formatDate(it) },
            modified = info.modificationDate?.let { formatDate(it) },
            extra = extra,
        )
    }

    private fun formatDate(calendar: java.util.Calendar): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(calendar.time)
    }

    /** Картинки страниц (включая вложенные Form XObject) декодируются и сохраняются в PNG без потерь. */
    override fun extractImages(source: ByteArray, baseName: String): List<ExtractedImage> {
        val collector = ImageCollector(baseName.toSafeFileName())
        load(source).use { doc ->
            var pageNumber = 0
            for (page in doc.pages) {
                pageNumber++
                // Идентичный набор нужен, чтобы не уйти в бесконечную рекурсию при циклических Form XObject
                val visited: MutableSet<COSBase> = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                collectImages(page.resources, "page $pageNumber", visited, collector)
            }
        }
        return collector.images
    }

    private fun collectImages(
        resources: PDResources?,
        origin: String,
        visited: MutableSet<COSBase>,
        collector: ImageCollector,
    ) {
        if (resources == null) return
        for (name in resources.xObjectNames) {
            val xObject = try {
                resources.getXObject(name)
            } catch (e: Exception) {
                null
            } ?: continue
            if (!visited.add(xObject.cosObject)) continue

            if (xObject is PDImageXObject) {
                val png = try {
                    encodePng(xObject.image)
                } catch (e: Exception) {
                    null
                }
                if (png != null) collector.add(png, "png", origin)
            } else if (xObject is PDFormXObject) {
                // Картинки часто лежат внутри форм (вложенные «страницы»), поэтому идём вглубь
                collectImages(xObject.resources, origin, visited, collector)
            }
        }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Не удалось закодировать картинку в PNG" }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ редактирование

    /** Диагональный полупрозрачный водяной знак по центру каждой страницы. Возвращает новый PDF. */
    fun addWatermark(source: ByteArray, text: String, options: WatermarkOptions = WatermarkOptions()): ByteArray =
        load(source).use { doc ->
            val font = loadFont(doc)
            val safeText = font.sanitize(text.replace('\n', ' '))
            val alpha = PDExtendedGraphicsState()
            alpha.setStrokingAlphaConstant(options.opacity)
            alpha.setNonStrokingAlphaConstant(options.opacity)
            val angle = Math.toRadians(options.rotationDegrees)
            val textWidth = font.getStringWidth(safeText) / 1000f * options.fontSize

            for (page in doc.pages) {
                val box = page.mediaBox
                val centerX = box.lowerLeftX + box.width / 2
                val centerY = box.lowerLeftY + box.height / 2
                // Сдвигаем начало строки назад на половину её длины вдоль направления поворота,
                // чтобы середина текста попала в центр страницы
                val x = centerX - (textWidth / 2) * cos(angle).toFloat()
                val y = centerY - (textWidth / 2) * sin(angle).toFloat() - options.fontSize / 4

                // APPEND — дописываем поверх существующего содержимого, resetContext — изолируем графическое состояние
                val stream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                try {
                    stream.setGraphicsStateParameters(alpha)
                    stream.setNonStrokingColor(options.red, options.green, options.blue)
                    stream.beginText()
                    stream.setFont(font, options.fontSize)
                    stream.setTextMatrix(Matrix.getRotateInstance(angle, x, y))
                    stream.showText(safeText)
                    stream.endText()
                } finally {
                    stream.close()
                }
            }
            save(doc)
        }

    // ------------------------------------------------------------------ создание

    /** Создаёт PDF формата A4: заголовок, абзацы (с переносом строк), таблица текстом, картинки. */
    override fun create(content: NewDocument): ByteArray = PDDocument().use { doc ->
        doc.documentInformation.apply {
            title = content.title
            author = content.author
        }
        val writer = PageWriter(doc, loadFont(doc))
        writer.text(content.title, TITLE_SIZE)
        content.paragraphs.forEach { writer.text(it, BODY_SIZE) }
        content.table.forEach { row -> writer.text(row.joinToString("  |  "), BODY_SIZE) }
        content.images.forEach { writer.image(PDImageXObject.createFromByteArray(doc, it.bytes, it.name)) }
        writer.finish()
        save(doc)
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun load(source: ByteArray): PDDocument =
        try {
            PDDocument.load(source)
        } catch (e: java.io.IOException) {
            throw DocForgeException("Не удалось открыть PDF (файл повреждён или защищён паролем): ${e.message}", e)
        }

    private fun save(doc: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        doc.save(out)
        return out.toByteArray()
    }

    /** Юникодный TTF (встраивается в PDF подмножеством) либо Helvetica как запасной вариант. */
    private fun loadFont(doc: PDDocument): PDFont {
        val stream = try {
            fontProvider()
        } catch (e: Exception) {
            null
        }
        if (stream != null) {
            try {
                return stream.use { PDType0Font.load(doc, it) }
            } catch (e: Exception) {
                // падаем на Helvetica ниже
            }
        }
        return PDType1Font.HELVETICA
    }

    /** Простая вёрстка: перенос по словам, автоматическое добавление страниц. */
    private class PageWriter(private val doc: PDDocument, private val font: PDFont) {
        private val pageSize = PDRectangle.A4
        private var stream: PDPageContentStream? = null
        private var y = 0f

        fun text(text: String, fontSize: Float) {
            val leading = fontSize * 1.4f
            for (line in wrap(font.sanitize(text), fontSize, pageSize.width - 2 * MARGIN)) {
                val cs = ensureSpace(leading)
                y -= leading
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset(MARGIN, y)
                cs.showText(line)
                cs.endText()
            }
            y -= fontSize * 0.6f // отступ между абзацами
        }

        fun image(image: PDImageXObject) {
            val maxWidth = pageSize.width - 2 * MARGIN
            val scale = minOf(1f, maxWidth / image.width, (pageSize.height - 2 * MARGIN) / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val cs = ensureSpace(h)
            y -= h
            cs.drawImage(image, MARGIN, y, w, h)
            y -= 12f
        }

        fun finish() {
            stream?.close()
            stream = null
        }

        /** Гарантирует место под [height] пунктов; при нехватке открывает новую страницу. */
        private fun ensureSpace(height: Float): PDPageContentStream {
            val current = stream
            if (current != null && y - height >= MARGIN) return current
            current?.close()
            val page = PDPage(pageSize)
            doc.addPage(page)
            y = pageSize.height - MARGIN
            val created = PDPageContentStream(doc, page)
            stream = created
            return created
        }

        private fun wrap(text: String, fontSize: Float, maxWidth: Float): List<String> {
            fun width(s: String) = font.getStringWidth(s) / 1000f * fontSize
            val lines = ArrayList<String>()
            for (paragraph in text.split('\n')) {
                var current = ""
                for (word in paragraph.split(' ')) {
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (current.isEmpty() || width(candidate) <= maxWidth) {
                        current = candidate
                    } else {
                        lines.add(current)
                        current = word
                    }
                }
                lines.add(current)
            }
            return lines
        }

        private companion object {
            const val MARGIN = 56f
        }
    }

    private companion object {
        const val TITLE_SIZE = 20f
        const val BODY_SIZE = 12f
    }
}

/** Проверяет, умеет ли шрифт закодировать строку (иначе PDFBox бросает IllegalArgumentException). */
private fun PDFont.canEncode(text: String): Boolean =
    try {
        getStringWidth(text)
        true
    } catch (e: Exception) {
        false
    }

/** Подготавливает текст к выводу: убирает табы/CR и заменяет неподдерживаемые шрифтом символы на «?». */
private fun PDFont.sanitize(text: String): String {
    val normalized = text.replace("\t", "    ").replace("\r", "")
    if (canEncode(normalized.replace("\n", ""))) return normalized
    return buildString {
        for (ch in normalized) append(if (ch == '\n' || canEncode(ch.toString())) ch else '?')
    }
}
