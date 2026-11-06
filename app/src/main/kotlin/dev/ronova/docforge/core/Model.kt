package dev.ronova.docforge.core

const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
const val MIME_PDF = "application/pdf"
const val MIME_ZIP = "application/zip"

/** Поддерживаемые форматы документов. */
enum class DocumentFormat(val extension: String, val mime: String, val title: String) {
    WORD("docx", MIME_DOCX, "Word"),
    EXCEL("xlsx", MIME_XLSX, "Excel"),
    POWERPOINT("pptx", MIME_PPTX, "PowerPoint"),
    PDF("pdf", MIME_PDF, "PDF");

    companion object {
        /** Определяет формат по расширению имени файла или бросает [UnsupportedFormatException]. */
        fun fromFileName(name: String): DocumentFormat {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
                ?: throw UnsupportedFormatException(
                    "Неподдерживаемый тип файла «.$ext» ($name). Поддерживаются: .docx, .xlsx, .pptx, .pdf"
                )
        }
    }
}

/** Метаданные документа (общие для всех форматов). Форматоспецифичное лежит в [extra]. */
data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val description: String? = null,
    val lastModifiedBy: String? = null,
    /** Даты — в виде строк ISO-8601 так, как они записаны в файле. */
    val created: String? = null,
    val modified: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    /** Человекочитаемый вид (пустые поля пропускаются). */
    fun describe(): String = buildList {
        title?.let { add("Название: $it") }
        author?.let { add("Автор: $it") }
        subject?.let { add("Тема: $it") }
        keywords?.let { add("Ключевые слова: $it") }
        description?.let { add("Описание: $it") }
        lastModifiedBy?.let { add("Последний редактор: $it") }
        created?.let { add("Создан: $it") }
        modified?.let { add("Изменён: $it") }
        extra.forEach { (key, value) -> add("$key: $value") }
    }.joinToString("\n").ifEmpty { "Метаданных нет" }
}

/** Результат извлечения одной картинки. */
class ExtractedImage(
    /** Имя файла для сохранения, например `report_img001.png`. */
    val name: String,
    /** Расширение/формат: png, jpg, emf ... */
    val format: String,
    val bytes: ByteArray,
    /** Где найдена: «slide 3», «page 2», «word/media/image1.png» ... */
    val origin: String,
    val width: Int?,
    val height: Int?,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    fun describe(): String {
        val dimensions = if (width != null && height != null) "${width}x$height" else "?"
        return "$name  ${format.uppercase()} $dimensions  $sizeBytes Б  ($origin)"
    }
}

/** Картинка для вставки в создаваемый документ (PNG / JPEG / GIF). */
class ImageData(val name: String, val bytes: ByteArray)

/** Простое описание нового документа; каждый движок отображает его в свой формат. */
data class NewDocument(
    val title: String,
    val paragraphs: List<String> = emptyList(),
    /** Таблица: первая строка считается заголовком. */
    val table: List<List<String>> = emptyList(),
    val images: List<ImageData> = emptyList(),
    val author: String = "DocForge",
)

/** Лист Excel: строки значений (как они записаны в файле). */
data class SheetData(val name: String, val rows: List<List<String>>)

/** Содержимое слайда PowerPoint. */
data class SlideContent(
    val number: Int,
    val title: String?,
    val textBlocks: List<String>,
    val notes: List<String>,
)

open class DocForgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedFormatException(message: String) : DocForgeException(message)
