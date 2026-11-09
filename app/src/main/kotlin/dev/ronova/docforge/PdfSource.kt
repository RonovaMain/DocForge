package dev.ronova.docforge

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/**
 * Страницы PDF через встроенный в Android PdfRenderer (без сторонних библиотек).
 * PdfRenderer не потокобезопасен — все обращения идут под общей блокировкой.
 */
class PdfSource(val file: File) : Closeable {
    private val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }

    val pageCount: Int get() = renderer.pageCount

    /** Размеры страниц в пунктах — по ним браузер резервирует место до загрузки картинки. */
    fun pageSizes(): List<IntArray> = synchronized(renderer) {
        val sizes = ArrayList<IntArray>(renderer.pageCount)
        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            try {
                sizes.add(intArrayOf(page.width, page.height))
            } finally {
                page.close()
            }
        }
        sizes
    }

    /** Страница как JPEG шириной [targetWidth] пикселей (ограничено по числу пикселей, чтобы не исчерпать память). */
    fun renderJpeg(index: Int, targetWidth: Int): ByteArray? = synchronized(renderer) {
        if (index < 0 || index >= renderer.pageCount) return@synchronized null
        val page = renderer.openPage(index)
        try {
            var width = targetWidth
            var height = (page.height.toFloat() * width / page.width).toInt().coerceAtLeast(1)
            val pixels = width.toLong() * height
            if (pixels > MAX_PIXELS) {
                val k = Math.sqrt(MAX_PIXELS.toDouble() / pixels)
                width = (width * k).toInt().coerceAtLeast(1)
                height = (height * k).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    override fun close() {
        synchronized(renderer) {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
        runCatching { file.delete() }
    }

    private companion object {
        const val MAX_PIXELS = 12_000_000L
    }
}
