package dev.ronova.docforge.util

internal data class TextMatch(val start: Int, val end: Int, val replacement: String)

/** Результат переписывания «ранов»: новые тексты для каждого рана и число замен. */
internal class RunRewrite(val texts: List<String>, val count: Int)

/**
 * Набор правил «ключ -> значение».
 *
 * Главная сложность Word/PowerPoint: Office любит дробить текст на «раны» (runs) с разным форматированием,
 * например «{{na» + «me}}». Поэтому поиск идёт по ПОЛНОМУ тексту абзаца, а результат раскладывается обратно по ранам:
 * замена записывается в ран, где началось совпадение, а из следующих ранов вырезается хвост совпадения.
 * Форматирование первого рана сохраняется.
 */
internal class ReplacementRules(private val replacements: Map<String, String>) {
    init {
        require(replacements.isNotEmpty()) { "Replacements must not be empty" }
        require(replacements.keys.none { it.isEmpty() }) { "Search keys must not be empty" }
    }

    // Длинные ключи — первыми, чтобы «{{name}}» побеждал «{{n»
    private val regex = Regex(
        replacements.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    )

    fun findMatches(text: String): List<TextMatch> =
        regex.findAll(text)
            .map { TextMatch(it.range.first, it.range.last + 1, replacements.getValue(it.value)) }
            .toList()

    /** Замена в простой строке (ячейки Excel). Возвращает новый текст и число замен. */
    fun replaceIn(text: String): Pair<String, Int> {
        val count = findMatches(text).size
        if (count == 0) return text to 0
        return regex.replace(text) { replacements.getValue(it.value) } to count
    }

    /** Замена в последовательности ранов одного абзаца. null — совпадений нет. */
    fun rewriteRuns(runTexts: List<String>): RunRewrite? {
        val matches = findMatches(runTexts.joinToString(""))
        if (matches.isEmpty()) return null

        var runStart = 0
        val newTexts = runTexts.map { text ->
            val runEnd = runStart + text.length
            val sb = StringBuilder()
            var cursor = runStart
            for (m in matches) {
                if (m.end <= cursor) continue      // совпадение уже обработано
                if (m.start >= runEnd) break       // совпадение в следующих ранах
                if (m.start > cursor) sb.append(text, cursor - runStart, m.start - runStart)
                if (m.start >= runStart) sb.append(m.replacement) // совпадение началось в этом ране
                cursor = minOf(m.end, runEnd)
            }
            if (cursor < runEnd) sb.append(text, cursor - runStart, runEnd - runStart)
            runStart = runEnd
            sb.toString()
        }
        return RunRewrite(newTexts, matches.size)
    }
}
