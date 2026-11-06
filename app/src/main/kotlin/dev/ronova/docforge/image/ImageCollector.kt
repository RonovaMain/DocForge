package dev.ronova.docforge.image

import dev.ronova.docforge.core.ExtractedImage
import dev.ronova.docforge.util.detectImageExtension
import dev.ronova.docforge.util.imageSizeOrNull
import dev.ronova.docforge.util.sha256

/**
 * Накапливает найденные картинки: ведёт нумерацию (`<имя>_img001.png`) и отсеивает дубликаты по SHA-256.
 */
internal class ImageCollector(private val baseName: String) {
    private val seenHashes = HashSet<String>()
    private val collected = ArrayList<ExtractedImage>()

    val images: List<ExtractedImage> get() = collected

    fun add(bytes: ByteArray, extension: String?, origin: String): ExtractedImage? {
        if (bytes.isEmpty()) return null
        if (!seenHashes.add(bytes.sha256())) return null

        val ext = normalize(extension) ?: bytes.detectImageExtension() ?: "bin"
        val size = bytes.imageSizeOrNull()
        val number = (collected.size + 1).toString().padStart(3, '0')
        val image = ExtractedImage(
            name = "${baseName}_img$number.$ext",
            format = ext,
            bytes = bytes,
            origin = origin,
            width = size?.first,
            height = size?.second,
        )
        collected += image
        return image
    }

    private fun normalize(ext: String?): String? =
        ext?.lowercase()?.removePrefix(".")?.takeIf { it.isNotBlank() }?.let { if (it == "jpeg") "jpg" else it }
}
