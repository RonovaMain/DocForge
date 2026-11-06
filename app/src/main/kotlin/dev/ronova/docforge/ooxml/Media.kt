package dev.ronova.docforge.ooxml

import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.image.ImageCollector

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "emf", "wmf", "svg", "webp")

/**
 * Достаёт картинки из папки [prefix] (`word/media/`, `xl/media/`, `ppt/media/`) «как есть», байт в байт.
 * Дубликаты (по SHA-256) сохраняются один раз; аудио и видео из той же папки пропускаются.
 * [originOf] описывает, где найдена картинка.
 */
internal fun OoxmlPackage.extractMedia(
    prefix: String,
    baseName: String,
    originOf: (String) -> String,
): List<ExtractedImage> {
    val collector = ImageCollector(baseName)
    val names = parts.keys.filter { it.startsWith(prefix) }.sortedWith(compareBy({ it.length }, { it }))
    for (name in names) {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS) {
            collector.add(parts.getValue(name), extension, originOf(name))
        }
    }
    return collector.images
}
