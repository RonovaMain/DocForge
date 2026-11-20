package dev.ronova.docforge

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Печать без драйверов: страницы PDF рисуются в чёрно-белый растр и отправляются принтеру на языке PCL 5.
 * Его понимают многие лазерные принтеры (HP LaserJet и совместимые, Brother, Kyocera, Lexmark, OKI, Ricoh…).
 * Принтеры только с «хост-драйвером» (GDI, SPL, CAPT, ZJS) PCL не знают — для них этот способ не подходит.
 */
object PclPrinter : PrintDriver {
    private const val ESC = "\u001B"
    private const val BAND_ROWS = 256

    /** Ординарная матрица Байера 8×8 для упорядоченного растрирования полутонов. */
    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * Печатает все страницы [file] (готовый PDF) [copies] раз. Данные отдаются порциями в [write];
     * [progress] получает (напечатано, всего); [cancelled] позволяет прервать печать между полосами.
     */
    override fun print(
        file: File,
        options: PrintOptions,
        copies: Int,
        write: (ByteArray, Int) -> Unit,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val dpi = options.dpi
        val paperW = (options.paper.mmWidth / 25.4 * dpi).roundToInt()
        val paperH = (options.paper.mmHeight / 25.4 * dpi).roundToInt()
        // принтеры не печатают у самого края листа (около 4 мм) — растр занимает только печатаемую область
        val inset = (dpi * 0.17).roundToInt()
        val rasterW = ((paperW - 2 * inset) / 8) * 8
        val rasterH = paperH - 2 * inset
        val margin = max(0, (options.marginMils * dpi / 1000.0).roundToInt() - inset)
        val rowBytes = rasterW / 8
        val rowHeader = "${ESC}*b${rowBytes}W".toByteArray(Charsets.ISO_8859_1)

        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            val band = Bitmap.createBitmap(rasterW, BAND_ROWS, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(rasterW * BAND_ROWS)
            try {
                val total = renderer.pageCount
                val all = total * copies
                var done = 0
                if (options.pjl) send(write, "$ESC%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n")
                send(write, "${ESC}E")
                for (copy in 0 until copies) {
                    for (i in 0 until total) {
                        if (cancelled()) throw CancellationException("Печать отменена")
                        val page = renderer.openPage(i)
                        try {
                            printPage(
                                page, options, rasterW, rasterH, margin, rowHeader, band, pixels, write, cancelled,
                            )
                        } finally {
                            page.close()
                        }
                        done++
                        progress(done, all)
                    }
                }
                send(write, "${ESC}E")
                if (options.pjl) send(write, "$ESC%-12345X")
            } finally {
                band.recycle()
                renderer.close()
            }
        }
    }

    private fun printPage(
        page: PdfRenderer.Page,
        o: PrintOptions,
        rasterW: Int,
        rasterH: Int,
        margin: Int,
        rowHeader: ByteArray,
        band: Bitmap,
        pixels: IntArray,
        write: (ByteArray, Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        val availW = (rasterW - 2 * margin).toFloat()
        val availH = (rasterH - 2 * margin).toFloat()
        val scale = min(availW / pw, availH / ph)
        val offsetX = margin + (availW - pw * scale) / 2f
        val offsetY = margin + (availH - ph * scale) / 2f

        send(write, "$ESC&l${o.paper.pclCode}A")
        send(write, "$ESC&l0O")
        send(write, "$ESC*p0X$ESC*p0Y")
        send(write, "$ESC*t${o.dpi}R")
        send(write, "$ESC*r0F")
        send(write, "$ESC*r${rasterW}S")
        send(write, "$ESC*r1A")

        val rowBytes = rasterW / 8
        val matrix = Matrix()
        var top = 0
        while (top < rasterH) {
            if (cancelled()) throw CancellationException("Печать отменена")
            val rows = min(BAND_ROWS, rasterH - top)
            band.eraseColor(Color.WHITE)
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(offsetX, offsetY - top)
            page.render(band, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            band.getPixels(pixels, 0, rasterW, 0, 0, rasterW, rows)

            val out = ByteArrayOutputStream(rows * (rowBytes + rowHeader.size))
            for (y in 0 until rows) {
                out.write(rowHeader, 0, rowHeader.size)
                val dither = ((top + y) and 7) * 8
                val base = y * rasterW
                var acc = 0
                var bits = 0
                for (x in 0 until rasterW) {
                    val c = pixels[base + x]
                    val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    // чуть усиливаем контраст: светло-серый становится белым, тёмный — чёрным
                    val boosted = ((lum - 24) * 255 / 207).coerceIn(0, 255)
                    val black = boosted < BAYER[dither + (x and 7)] * 4 + 2
                    acc = (acc shl 1) or (if (black) 1 else 0)
                    bits++
                    if (bits == 8) {
                        out.write(acc)
                        acc = 0
                        bits = 0
                    }
                }
            }
            val data = out.toByteArray()
            write(data, data.size)
            top += rows
        }
        send(write, "$ESC*rB")
        send(write, "\u000C")
    }

    private fun send(write: (ByteArray, Int) -> Unit, text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        write(bytes, bytes.size)
    }
}
