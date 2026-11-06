package dev.ronova.docforge.core

import dev.ronova.docforge.excel.ExcelEngine
import dev.ronova.docforge.pdf.PdfEngine
import dev.ronova.docforge.pptx.PowerPointEngine
import dev.ronova.docforge.word.WordEngine
import java.io.InputStream

/**
 * Базовые операции, которые поддерживают ВСЕ форматы: чтение текста, метаданных и извлечение картинок.
 *
 * Движки работают с байтами файла, а не с путями: на Android файлы приходят как content:// Uri,
 * поэтому чтение и запись потоков остаются на стороне интерфейса. Движки не хранят состояние
 * и потокобезопасны.
 */
interface DocumentEngine {
    val format: DocumentFormat

    /** Весь текст документа (для xlsx — по листам, для pptx — по слайдам, для pdf — по страницам). */
    fun readText(source: ByteArray): String

    fun readMetadata(source: ByteArray): DocumentMetadata

    /** Все картинки документа; [baseName] — префикс имён файлов. */
    fun extractImages(source: ByteArray, baseName: String): List<ExtractedImage>
}

/** Результат замены текста: новый файл и число выполненных замен. */
class ReplaceResult(val bytes: ByteArray, val count: Int)

/**
 * Поиск и замена текста. Вынесено в отдельный интерфейс (Interface Segregation):
 * PDF не умеет надёжно заменять текст, поэтому его не реализует.
 */
interface TextReplacer {
    /** Заменяет каждое вхождение ключа из [replacements] на значение. Если замен не было, возвращает исходные байты. */
    fun replaceText(source: ByteArray, replacements: Map<String, String>): ReplaceResult
}

/** Создание нового документа с нуля. */
interface DocumentCreator {
    fun create(content: NewDocument): ByteArray
}

/**
 * Composition root: единственное место, где домен связывается с конкретными реализациями.
 * [fontProvider] отдаёт TTF с кириллицей для PDF (на Android — из assets).
 */
class DocumentEngines(fontProvider: () -> InputStream?) {
    val word = WordEngine()
    val excel = ExcelEngine()
    val powerPoint = PowerPointEngine()
    val pdf = PdfEngine(fontProvider)

    fun forFormat(format: DocumentFormat): DocumentEngine = when (format) {
        DocumentFormat.WORD -> word
        DocumentFormat.EXCEL -> excel
        DocumentFormat.POWERPOINT -> powerPoint
        DocumentFormat.PDF -> pdf
    }

    /** Движок с заменой текста (docx, xlsx, pptx). Для PDF бросает [UnsupportedOperationException]. */
    fun textReplacer(format: DocumentFormat): TextReplacer =
        forFormat(format) as? TextReplacer
            ?: throw UnsupportedOperationException("Замена текста не поддерживается для ${format.title}")

    fun creator(format: DocumentFormat): DocumentCreator =
        forFormat(format) as? DocumentCreator
            ?: throw UnsupportedOperationException("Создание файлов не поддерживается для ${format.title}")
}
