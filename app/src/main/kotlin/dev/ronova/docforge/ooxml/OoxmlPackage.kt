package dev.ronova.docforge.ooxml

import dev.ronova.docforge.core.DocForgeException
import dev.ronova.docforge.core.DocumentMetadata
import dev.ronova.docforge.util.blankToNull
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Пространства имён OOXML. */
internal object Ns {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val DCTERMS = "http://purl.org/dc/terms/"
}

/** Связь (relationship) между частями пакета; [target] уже приведён к полному имени части. */
internal class Relationship(val id: String, val type: String, val target: String)

/**
 * Пакет Open XML (docx / xlsx / pptx) = ZIP из частей. Работает целиком в памяти и на чистой JVM-библиотеке
 * (java.util.zip + javax.xml), поэтому одинаково запускается на Android и в юнит-тестах.
 * Порядок частей сохраняется (`[Content_Types].xml` должен идти первым — для новых пакетов это обеспечивает вызывающий код).
 */
internal class OoxmlPackage private constructor(val parts: LinkedHashMap<String, ByteArray>) {

    fun xml(name: String): org.w3c.dom.Document? = parts[name]?.let { parseXml(it) }

    fun putXml(name: String, doc: Document) {
        parts[name] = doc.toBytes()
    }

    fun put(name: String, text: String) {
        parts[name] = text.toByteArray(Charsets.UTF_8)
    }

    fun put(name: String, data: ByteArray) {
        parts[name] = data
    }

    fun names(regex: Regex): List<String> = parts.keys.filter { regex.matches(it) }

    /** Связи части [partName], например `xl/workbook.xml` -> `xl/_rels/workbook.xml.rels`. */
    fun relationships(partName: String): List<Relationship> {
        val dir = partName.substringBeforeLast('/', "")
        val relsName = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + partName.substringAfterLast('/') + ".rels"
        val doc = xml(relsName) ?: return emptyList()
        return doc.elements(Ns.PKG_REL, "Relationship").map {
            Relationship(it.getAttribute("Id"), it.getAttribute("Type"), resolveTarget(dir, it.getAttribute("Target")))
        }
    }

    fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Core-свойства (docProps/core.xml) — одинаковы для docx / xlsx / pptx. */
    fun coreMetadata(extra: Map<String, String>): DocumentMetadata {
        val doc = xml("docProps/core.xml")
        fun text(ns: String, name: String): String? =
            doc?.elements(ns, name)?.firstOrNull()?.textContent.blankToNull()
        return DocumentMetadata(
            title = text(Ns.DC, "title"),
            author = text(Ns.DC, "creator"),
            subject = text(Ns.DC, "subject"),
            keywords = text(Ns.CP, "keywords"),
            description = text(Ns.DC, "description"),
            lastModifiedBy = text(Ns.CP, "lastModifiedBy"),
            created = text(Ns.DCTERMS, "created"),
            modified = text(Ns.DCTERMS, "modified"),
            extra = extra,
        )
    }

    companion object {
        fun create(): OoxmlPackage = OoxmlPackage(LinkedHashMap())

        fun read(bytes: ByteArray): OoxmlPackage {
            val parts = LinkedHashMap<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
                        entry = zip.nextEntry
                    }
                }
            } catch (e: java.io.IOException) {
                throw DocForgeException("Файл повреждён или не является документом Office: ${e.message}", e)
            }
            if (parts.isEmpty()) throw DocForgeException("Файл не является документом Office (пустой ZIP-архив)")
            return OoxmlPackage(parts)
        }
    }
}

/** Приводит относительный Target связи к полному имени части: `../media/a.png` + `ppt/slides` -> `ppt/media/a.png`. */
internal fun resolveTarget(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val stack = ArrayList<String>()
    if (baseDir.isNotEmpty()) stack.addAll(baseDir.split('/'))
    for (segment in target.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            else -> stack.add(segment)
        }
    }
    return stack.joinToString("/")
}

/**
 * Общая часть замены текста для Word / PowerPoint / Excel: [runs] — элементы с текстом одного абзаца
 * (w:t, a:t, t). Совпадение ищется по тексту абзаца целиком, результат раскладывается обратно по «ранам».
 */
internal fun rewriteTextElements(
    runs: List<org.w3c.dom.Element>,
    rules: dev.ronova.docforge.util.ReplacementRules,
    preserveSpace: Boolean,
): Int {
    if (runs.isEmpty()) return 0
    val oldTexts = runs.map { it.textContent ?: "" }
    val rewrite = rules.rewriteRuns(oldTexts) ?: return 0
    runs.forEachIndexed { i, element ->
        // Трогаем только те раны, текст которых реально изменился — остальные сохраняют разметку как есть
        if (rewrite.texts[i] != oldTexts[i]) {
            element.textContent = rewrite.texts[i]
            if (preserveSpace) element.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
        }
    }
    return rewrite.count
}
