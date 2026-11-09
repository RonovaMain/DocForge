package dev.ronova.docforge

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
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
