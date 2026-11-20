package dev.ronova.docforge

import android.hardware.usb.UsbDevice
import android.print.PrintAttributes

enum class PaperSize(val label: String, val mmWidth: Int, val mmHeight: Int, val pclCode: Int, val media: PrintAttributes.MediaSize) {
    A4("A4", 210, 297, 26, PrintAttributes.MediaSize.ISO_A4),
    A5("A5", 148, 210, 25, PrintAttributes.MediaSize.ISO_A5),
    LETTER("Letter", 216, 279, 2, PrintAttributes.MediaSize.NA_LETTER),
    LEGAL("Legal", 216, 356, 3, PrintAttributes.MediaSize.NA_LEGAL),
}

enum class PageChoice { ALL, CURRENT, RANGE }

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

/** OFF — односторонняя; BATCH — сначала все лицевые, потом обратные (как в Word); SHEET — лист за листом с паузой на переворот. */
enum class DuplexMode { OFF, BATCH, SHEET }

/** Как поворачивать страницы PDF перед печатью по USB (бумага всегда подаётся книжной). */
enum class RotateMode { NONE, LANDSCAPE_PAGES, ALL }

class PrintOptions(
    val paper: PaperSize,
    val orientation: Orientation,
    /** Поля в тысячных долях дюйма (200 ≈ 5 мм). */
    val marginMils: Int,
    val pages: PageChoice,
    val rangeText: String,
    val copies: Int,
    val duplex: DuplexMode,
    val reverseBacks: Boolean,
    /** null — системная печать Android, иначе принтер, на который печатаем напрямую по USB (PCL). */
    val usbDevice: UsbDevice?,
    val dpi: Int,
    val pjl: Boolean,
) {
    /** Параметры бумаги для системной печати и для вёрстки Office-документов. */
    fun attributes(): PrintAttributes {
        val media = if (orientation == Orientation.LANDSCAPE) paper.media.asLandscape() else paper.media.asPortrait()
        return PrintAttributes.Builder()
            .setMediaSize(media)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins(marginMils, marginMils, marginMils, marginMils))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
    }

    fun rotateMode(): RotateMode = when (orientation) {
        Orientation.AUTO -> RotateMode.LANDSCAPE_PAGES
        Orientation.PORTRAIT -> RotateMode.NONE
        Orientation.LANDSCAPE -> RotateMode.ALL
    }
}

/** Чистая логика: какие страницы и в каком порядке печатать. */
object PrintPlan {

    /** «1-3, 5, 8-» → [1, 2, 3, 5, 8, …]. null — если запись неверна или страницы вне документа. */
    fun parseRange(text: String, total: Int): List<Int>? {
        val result = ArrayList<Int>()
        for (part in text.split(',', ';')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val dash = t.indexOf('-')
            if (dash < 0) {
                val n = t.toIntOrNull() ?: return null
                if (n < 1 || n > total) return null
                result.add(n)
            } else {
                val a = t.substring(0, dash).trim()
                val b = t.substring(dash + 1).trim()
                val from = if (a.isEmpty()) 1 else (a.toIntOrNull() ?: return null)
                val to = if (b.isEmpty()) total else (b.toIntOrNull() ?: return null)
                if (from < 1 || to > total || from > to) return null
                for (n in from..to) result.add(n)
            }
        }
        return if (result.isEmpty()) null else result
    }

    /** Проходы печати. 0 в списке — пустая страница (держит порядок листов при обратной раскладке). */
    fun passes(pages: List<Int>, mode: DuplexMode, reverseBacks: Boolean): List<List<Int>> = when (mode) {
        DuplexMode.OFF -> listOf(pages)
        DuplexMode.SHEET -> pages.map { listOf(it) }
        DuplexMode.BATCH -> batch(pages, reverseBacks)
    }

    private fun batch(pages: List<Int>, reverseBacks: Boolean): List<List<Int>> {
        val fronts = pages.filterIndexed { i, _ -> i % 2 == 0 }
        var backs = pages.filterIndexed { i, _ -> i % 2 == 1 }
        if (backs.isEmpty()) return listOf(fronts)
        if (reverseBacks) {
            backs = backs.reversed()
            // нечётное число страниц: у последнего листа обратная сторона пустая, в обратном порядке он идёт первым
            if (pages.size % 2 == 1) backs = listOf(0) + backs
        }
        return listOf(fronts, backs)
    }
}
