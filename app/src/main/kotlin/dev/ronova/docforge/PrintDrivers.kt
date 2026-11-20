package dev.ronova.docforge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.ronova.docforge.core.DocException
import dev.ronova.docforge.print.DriverSpec
import dev.ronova.docforge.print.FilterProcess
import dev.ronova.docforge.print.Pnm
import dev.ronova.docforge.print.PostScriptStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

/** Способ печати на USB-принтер: получает готовый PDF и отдаёт принтеру байты через [write]. */
interface PrintDriver {
    fun print(
        file: File,
        options: PrintOptions,
        copies: Int,
        write: (ByteArray, Int) -> Unit,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    )
}

/** Подбор реализации по описанию из каталога. Фильтры foo2* есть только в сборках, где их собрал скрипт scripts/build-filters.sh. */
object Drivers {
    fun binary(context: Context, spec: DriverSpec): File? {
        val name = spec.binary ?: return null
        val file = File(context.applicationInfo.nativeLibraryDir, "lib$name.so")
        return if (file.exists() && file.canExecute()) file else null
    }

    fun isAvailable(context: Context, spec: DriverSpec): Boolean =
        if (spec.kind == DriverSpec.Kind.FILTER) binary(context, spec) != null else true

    fun create(context: Context, spec: DriverSpec): PrintDriver? = when (spec.kind) {
        DriverSpec.Kind.PCL5 -> PclPrinter
        DriverSpec.Kind.POSTSCRIPT -> PostScriptPrinter
        DriverSpec.Kind.PDF -> PdfDirectPrinter
        DriverSpec.Kind.FILTER -> binary(context, spec)?.let { FilterPrinter(spec, it) }
    }

    /** Прошивка (.dl), которую пользователь выбрал для конкретной модели принтера. Ключ — VID-PID. */
    fun firmwareFile(context: Context, key: String): File = File(File(context.filesDir, "firmware"), "$key.dl")
}

/** OutputStream поверх функции записи в принтер. */
private class SinkStream(private val sink: (ByteArray, Int) -> Unit) : OutputStream() {
    override fun write(b: Int) {
        sink(byteArrayOf(b.toByte()), 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        sink(if (off == 0) b else b.copyOfRange(off, off + len), len)
    }
}

/** Общая часть растровых способов: страница PDF рисуется полосами, чтобы не держать в памяти весь лист. */
internal object PageBands {
    const val BAND_ROWS = 256

    /** Матрица Байера 8×8 для упорядоченного растрирования полутонов (та же, что в PclPrinter). */
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

    fun gray(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /** Рисует страницу полосами по [BAND_ROWS] строк; для каждой вызывает [onBand] (число строк, номер верхней строки). */
    fun render(
        page: PdfRenderer.Page,
        rasterW: Int,
        rasterH: Int,
        margin: Int,
        band: Bitmap,
        pixels: IntArray,
        cancelled: () -> Boolean,
        onBand: (Int, Int) -> Unit,
    ) {
        val pw = page.width.toFloat()
        val ph = page.height.toFloat()
        val availW = (rasterW - 2 * margin).toFloat()
        val availH = (rasterH - 2 * margin).toFloat()
        val scale = min(availW / pw, availH / ph)
        val offsetX = margin + (availW - pw * scale) / 2f
        val offsetY = margin + (availH - ph * scale) / 2f
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
            onBand(rows, top)
            top += rows
        }
    }

    /** Строки полосы -> 1 бит на пиксель (1 = чёрный), строки по rasterW/8 байт. rasterW кратна 8. */
    fun toBits(pixels: IntArray, rasterW: Int, rows: Int, top: Int, out: ByteArrayOutputStream) {
        for (y in 0 until rows) {
            val dither = ((top + y) and 7) * 8
            val base = y * rasterW
            var acc = 0
            var bits = 0
            for (x in 0 until rasterW) {
                val boosted = ((gray(pixels[base + x]) - 24) * 255 / 207).coerceIn(0, 255)
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
    }

    fun text(write: (ByteArray, Int) -> Unit, s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        write(bytes, bytes.size)
    }
}

private const val UEL = "\u001B%-12345X"

/** Принтеры с PostScript: каждая страница уходит растром 200 dpi, оттенки серого, принтер сам делает полутона. */
object PostScriptPrinter : PrintDriver {
    private const val DPI = 200

    override fun print(
        file: File,
        options: PrintOptions,
        copies: Int,
        write: (ByteArray, Int) -> Unit,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val paperW = (options.paper.mmWidth / 25.4 * DPI).roundToInt()
        val paperH = (options.paper.mmHeight / 25.4 * DPI).roundToInt()
        val ptW = (options.paper.mmWidth / 25.4 * 72).roundToInt()
        val ptH = (options.paper.mmHeight / 25.4 * 72).roundToInt()
        val margin = (options.marginMils * DPI / 1000.0).roundToInt()
        val out = BufferedOutputStream(SinkStream(write), 32 * 1024)
        if (options.pjl) out.write("$UEL@PJL ENTER LANGUAGE=POSTSCRIPT\r\n".toByteArray(Charsets.ISO_8859_1))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = PdfRenderer(fd)
            val band = Bitmap.createBitmap(paperW, PageBands.BAND_ROWS, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(paperW * PageBands.BAND_ROWS)
            val gray = ByteArray(paperW * PageBands.BAND_ROWS)
            try {
                val ps = PostScriptStream(out, "DocForge")
                val total = renderer.pageCount
                val all = total * copies
                var done = 0
                for (copy in 0 until copies) {
                    for (i in 0 until total) {
                        val page = renderer.openPage(i)
                        try {
                            ps.beginPage(paperW, paperH, ptW, ptH)
                            PageBands.render(page, paperW, paperH, margin, band, pixels, cancelled) { rows, _ ->
                                for (k in 0 until rows * paperW) gray[k] = PageBands.gray(pixels[k]).toByte()
                                ps.rows(gray, 0, rows)
                            }
                            ps.endPage()
                        } finally {
                            page.close()
                        }
                        done++
                        progress(done, all)
                    }
                }
                ps.finish()
            } finally {
                band.recycle()
                renderer.close()
            }
        }
        if (options.pjl) out.write(UEL.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}

/** Принтеры, которые сами читают PDF: файл уходит как есть. */
object PdfDirectPrinter : PrintDriver {
    override fun print(
        file: File,
        options: PrintOptions,
        copies: Int,
        write: (ByteArray, Int) -> Unit,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(16 * 1024)
        for (copy in 0 until copies) {
            if (options.pjl) PageBands.text(write, "$UEL@PJL ENTER LANGUAGE=PDF\r\n")
            file.inputStream().use { input ->
                while (true) {
                    if (cancelled()) throw CancellationException("Печать отменена")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) write(buffer, n)
                }
            }
            if (options.pjl) PageBands.text(write, UEL)
            progress(copy + 1, copies)
        }
    }
}

/**
 * Печать через внешний фильтр (foo2zjs и родственные): страницы рисуются чёрно-белым растром PBM
 * в разрешении принтера и подаются на stdin фильтра, а его вывод идёт прямо в USB.
 */
class FilterPrinter(private val spec: DriverSpec, private val binary: File) : PrintDriver {

    override fun print(
        file: File,
        options: PrintOptions,
        copies: Int,
        write: (ByteArray, Int) -> Unit,
        progress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val dpi = spec.dpi
        val paperW = (options.paper.mmWidth / 25.4 * dpi).roundToInt()
        val paperH = (options.paper.mmHeight / 25.4 * dpi).roundToInt()
        val rasterW = ((paperW + 7) / 8) * 8
        val margin = (options.marginMils * dpi / 1000.0).roundToInt()
        val command = ArrayList<String>()
        command.add(binary.path)
        command.addAll(spec.filterArgs(rasterW, paperH, paperCode(options.paper), copies))

        val process = try {
            FilterProcess.start(command, FilterProcess.Sink { data, length -> write(data, length) })
        } catch (e: IOException) {
            throw DocException("Не удалось запустить драйвер ${spec.binary}: ${e.message}", e)
        }
        try {
            val stdin = BufferedOutputStream(process.stdin(), 64 * 1024)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val renderer = PdfRenderer(fd)
                val band = Bitmap.createBitmap(rasterW, PageBands.BAND_ROWS, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(rasterW * PageBands.BAND_ROWS)
                try {
                    val total = renderer.pageCount
                    for (i in 0 until total) {
                        val page = renderer.openPage(i)
                        try {
                            stdin.write(Pnm.pbmHeader(rasterW, paperH))
                            PageBands.render(page, rasterW, paperH, margin, band, pixels, cancelled) { rows, top ->
                                val bits = ByteArrayOutputStream(rows * rasterW / 8)
                                PageBands.toBits(pixels, rasterW, rows, top, bits)
                                stdin.write(bits.toByteArray())
                            }
                        } finally {
                            page.close()
                        }
                        progress(i + 1, total)
                    }
                } finally {
                    band.recycle()
                    renderer.close()
                }
            }
            stdin.flush()
        } catch (e: CancellationException) {
            process.abort()
            throw e
        } catch (e: IOException) {
            // чаще всего фильтр завершился сам: его сообщение об ошибке полезнее, чем «Broken pipe»
            try {
                process.finish()
            } catch (inner: IOException) {
                throw DocException(inner.message ?: "Драйвер ${spec.binary} завершился с ошибкой", inner)
            }
            throw DocException("Не удалось передать страницу драйверу: ${e.message}", e)
        } catch (e: Throwable) {
            process.abort()
            throw e
        }
        try {
            process.finish()
        } catch (e: IOException) {
            throw DocException(e.message ?: "Драйвер ${spec.binary} завершился с ошибкой", e)
        }
    }

    /** Коды бумаги Windows DMPAPER, которыми пользуются фильтры foo2*. */
    private fun paperCode(paper: PaperSize): Int = when (paper) {
        PaperSize.A4 -> 9
        PaperSize.A5 -> 11
        PaperSize.LETTER -> 1
        PaperSize.LEGAL -> 5
    }
}
