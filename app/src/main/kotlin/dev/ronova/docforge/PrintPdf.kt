package dev.ronova.docforge

import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileInputStream

object PrintPdf {

    fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
            }
        }

    /**
     * Собирает новый PDF из страниц [pages] (с единицы, в указанном порядке; 0 — пустая страница размером с соседнюю)
     * и при необходимости поворачивает страницы. Исходный файл не меняется.
     */
    fun subset(src: File, pages: List<Int>, rotate: RotateMode, out: File) {
        PDDocument.load(src).use { source ->
            PDDocument().use { result ->
                var blank: PDRectangle? = null
                // размер пустой страницы берём у первой настоящей (с учётом поворота)
                val firstReal = pages.firstOrNull { it > 0 }
                if (firstReal != null) blank = displayedSize(source.getPage(firstReal - 1), rotate)
                for (n in pages) {
                    if (n == 0) {
                        val size = blank ?: PDRectangle.A4
                        result.addPage(PDPage(PDRectangle(size.width, size.height)))
                        continue
                    }
                    val page = result.importPage(source.getPage(n - 1))
                    if (shouldRotate(page, rotate)) page.setRotation((page.rotation + 90) % 360)
                }
                result.save(out)
            }
        }
    }

    private fun isLandscape(page: PDPage): Boolean {
        val box = page.cropBox
        val turned = page.rotation % 180 != 0
        val w = if (turned) box.height else box.width
        val h = if (turned) box.width else box.height
        return w > h
    }

    private fun shouldRotate(page: PDPage, mode: RotateMode): Boolean = when (mode) {
        RotateMode.NONE -> false
        RotateMode.LANDSCAPE_PAGES -> isLandscape(page)
        RotateMode.ALL -> true
    }

    private fun displayedSize(page: PDPage, mode: RotateMode): PDRectangle {
        val box = page.cropBox
        var w = box.width
        var h = box.height
        if (page.rotation % 180 != 0) {
            val t = w
            w = h
            h = t
        }
        if (shouldRotate(page, mode)) {
            val t = w
            w = h
            h = t
        }
        return PDRectangle(w, h)
    }
}

/** Отдаёт системной печати готовый PDF-файл как есть. */
class FilePrintAdapter(
    private val file: File,
    private val jobName: String,
    private val pageCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
