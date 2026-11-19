package dev.ronova.docforge

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Картинка, найденная в документе: имя файла для сохранения и данные. */
class NamedImage(val name: String, val bytes: ByteArray)

/**
 * Текст и встроенные картинки PDF на базе pdfbox-android. Вызывается только из фонового потока;
 * извлечённый текст запоминается, поэтому повторные поиски быстрые.
 */
class PdfTools(private val file: File) {
    /** Совпадение: номер страницы (с 1) и фрагмент текста вокруг. */
    class Hit(val page: Int, val snippet: String)

    private var pageTexts: List<String>? = null

    @Synchronized
    private fun texts(): List<String> {
        pageTexts?.let { return it }
        val result = ArrayList<String>()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                result.add(stripper.getText(doc).replace(Regex("\\s+"), " "))
            }
        }
        pageTexts = result
        return result
    }

    /** Есть ли в PDF вообще текст (у сканов его нет, и искать нечего). */
    fun hasText(): Boolean = texts().any { it.isNotBlank() }

    @Synchronized
    fun search(query: String, limit: Int = 500): List<Hit> {
        val needle = query.trim().replace(Regex("\\s+"), " ").lowercase()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        val pages = texts()
        for ((index, text) in pages.withIndex()) {
            val haystack = text.lowercase()
            var from = 0
            while (hits.size < limit) {
                val at = haystack.indexOf(needle, from)
                if (at < 0) break
                val start = (at - 25).coerceAtLeast(0)
                val end = (at + needle.length + 35).coerceAtMost(text.length)
                val snippet = (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
                hits.add(Hit(index + 1, snippet))
                from = at + needle.length
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    /** Встроенные картинки одной страницы ([pageIndex] с нуля). */
    @Synchronized
    fun imagesOnPage(pageIndex: Int): List<NamedImage> {
        val result = ArrayList<NamedImage>()
        PDDocument.load(file).use { doc ->
            if (pageIndex in 0 until doc.numberOfPages) collectPage(doc, pageIndex, HashSet(), result)
        }
        return result
    }

    /** Все встроенные картинки документа без повторов. */
    @Synchronized
    fun allImages(): List<NamedImage> {
        val result = ArrayList<NamedImage>()
        val seen = HashSet<String>()
        PDDocument.load(file).use { doc ->
            for (i in 0 until doc.numberOfPages) collectPage(doc, i, seen, result)
        }
        return result
    }

    /**
     * Вшивает пометки (рисунки пальцем) в копию PDF и возвращает байты нового файла. [pages]: номер страницы с нуля →
     * штрихи в долях страницы, как её видит человек (с учётом поворота и обрезки). Исходный файл не меняется.
     */
    @Synchronized
    fun bakeInk(pages: Map<Int, List<InkView.Stroke>>): ByteArray {
        return PDDocument.load(file).use { doc ->
            for ((index, strokes) in pages) {
                if (strokes.isEmpty() || index < 0 || index >= doc.numberOfPages) continue
                val page = doc.getPage(index)
                val box = page.cropBox
                val rotation = ((page.rotation % 360) + 360) % 360
                // Ширина страницы «как на экране»: при повороте на 90/270 она равна высоте рамки
                val shownWidth = if (rotation == 90 || rotation == 270) box.height else box.width
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (stroke in strokes) drawStroke(cs, stroke, box, rotation, shownWidth)
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
    }

    private fun drawStroke(cs: PDPageContentStream, stroke: InkView.Stroke, box: PDRectangle, rotation: Int, shownWidth: Float) {
        val pts = stroke.points
        if (pts.size < 2) return
        val alpha = Color.alpha(stroke.color) / 255f
        val state = PDExtendedGraphicsState()
        state.setStrokingAlphaConstant(alpha)
        state.setNonStrokingAlphaConstant(alpha)
        cs.setGraphicsStateParameters(state)
        cs.setStrokingColor(Color.red(stroke.color) / 255f, Color.green(stroke.color) / 255f, Color.blue(stroke.color) / 255f)
        cs.setLineWidth(Math.max(stroke.width * shownWidth, 0.5f))
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        var first = true
        var i = 0
        while (i + 1 < pts.size) {
            val xy = toUserSpace(box, rotation, pts[i], pts[i + 1])
            if (first) {
                cs.moveTo(xy[0], xy[1])
                first = false
            }
            cs.lineTo(xy[0], xy[1])
            i += 2
        }
        cs.stroke()
    }

    /** Точка страницы в долях (u слева направо, v сверху вниз, как на экране) → координаты PDF. */
    private fun toUserSpace(box: PDRectangle, rotation: Int, u: Float, v: Float): FloatArray {
        val w = box.width
        val h = box.height
        val x: Float
        val y: Float
        when (rotation) {
            90 -> {
                x = v * w
                y = u * h
            }
            180 -> {
                x = (1f - u) * w
                y = v * h
            }
            270 -> {
                x = (1f - v) * w
                y = (1f - u) * h
            }
            else -> {
                x = u * w
                y = (1f - v) * h
            }
        }
        return floatArrayOf(box.lowerLeftX + x, box.lowerLeftY + y)
    }

    private fun collectPage(doc: PDDocument, pageIndex: Int, seen: MutableSet<String>, out: MutableList<NamedImage>) {
        val visited: MutableSet<COSBase> = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val bitmaps = ArrayList<Bitmap>()
        collect(doc.getPage(pageIndex).resources, visited, bitmaps)
        var number = 0
        for (bitmap in bitmaps) {
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
            if (!seen.add(hash)) continue
            number++
            out.add(NamedImage("page${pageIndex + 1}_image$number.png", png))
        }
    }

    /** Картинки страницы, включая вложенные Form XObject; значки меньше 24 пикселей пропускаются. */
    private fun collect(resources: PDResources?, visited: MutableSet<COSBase>, out: MutableList<Bitmap>) {
        if (resources == null) return
        for (name in resources.xObjectNames) {
            val xObject = try {
                resources.getXObject(name)
            } catch (e: Exception) {
                null
            } ?: continue
            if (!visited.add(xObject.cosObject)) continue
            if (xObject is PDImageXObject) {
                val bitmap = try {
                    xObject.image
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null && bitmap.width >= 24 && bitmap.height >= 24) out.add(bitmap)
            } else if (xObject is PDFormXObject) {
                collect(xObject.resources, visited, out)
            }
        }
    }
}
