package dev.ronova.docforge.util

import java.security.MessageDigest
import kotlin.math.abs

internal fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/** Делает строку пригодной для имени файла. */
internal fun String.toSafeFileName(): String =
    replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.').ifEmpty { "file" }.take(80)

internal fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

/** Экранирует текст для вставки в XML и убирает символы, запрещённые в XML 1.0. */
internal fun escapeXml(text: String): String = buildString(text.length + 16) {
    for (ch in text) {
        when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\'' -> append("&apos;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
            else -> append(ch)
        }
    }
}

// ------------------------------------------------------------------ картинки

private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
private fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
private fun ByteArray.be32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
private fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.le32(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)

/** Определяет формат картинки по «магическим» байтам; null — если формат неизвестен. */
internal fun ByteArray.detectImageExtension(): String? {
    fun starts(vararg sig: Int) = size >= sig.size && sig.indices.all { this[it] == sig[it].toByte() }
    return when {
        starts(0x89, 0x50, 0x4E, 0x47) -> "png"
        starts(0xFF, 0xD8, 0xFF) -> "jpg"
        starts(0x47, 0x49, 0x46, 0x38) -> "gif"
        starts(0x42, 0x4D) -> "bmp"
        starts(0x49, 0x49, 0x2A, 0x00) || starts(0x4D, 0x4D, 0x00, 0x2A) -> "tiff"
        starts(0xD7, 0xCD, 0xC6, 0x9A) -> "wmf"
        starts(0x01, 0x00, 0x00, 0x00) -> "emf"
        size > 12 && starts(0x52, 0x49, 0x46, 0x46) &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() -> "webp"
        else -> null
    }
}

/**
 * Размеры картинки (ширина, высота), прочитанные из заголовка без декодирования.
 * Работает для PNG, JPEG, GIF и BMP; для остальных форматов (EMF/WMF/WebP...) возвращает null.
 * Не зависит от Android, поэтому проверяется обычными JVM-тестами.
 */
internal fun ByteArray.imageSizeOrNull(): Pair<Int, Int>? {
    val dimensions: Pair<Int, Int>? = when (detectImageExtension()) {
        "png" -> if (size >= 24) be32(16) to be32(20) else null
        "gif" -> if (size >= 10) le16(6) to le16(8) else null
        "bmp" -> if (size >= 26) le32(18) to abs(le32(22)) else null
        "jpg" -> jpegSize()
        else -> null
    }
    return dimensions?.takeIf { it.first > 0 && it.second > 0 }
}

private fun ByteArray.jpegSize(): Pair<Int, Int>? {
    var i = 2
    while (i + 9 < size) {
        if (u8(i) != 0xFF) {
            i++
            continue
        }
        val marker = u8(i + 1)
        when {
            marker == 0xFF -> i++                               // байты-заполнители
            marker == 0x01 || marker in 0xD0..0xD9 -> i += 2   // маркеры без длины
            else -> {
                val length = be16(i + 2)
                if (length < 2) return null
                // SOF0..SOF15, кроме DHT (C4), JPG (C8) и DAC (CC)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return be16(i + 7) to be16(i + 5)
                }
                i += 2 + length
            }
        }
    }
    return null
}
