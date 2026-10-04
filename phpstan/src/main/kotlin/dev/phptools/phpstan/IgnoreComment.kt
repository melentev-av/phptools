package dev.phptools.phpstan

/** Вставка текста [text] по смещению [offset]. */
data class TextInsert(val offset: Int, val text: String)

/**
 * Правка для `// @phpstan-ignore <identifier>`. Чистая логика без платформы.
 *
 * - Если предыдущая строка (без ведущих пробелов) начинается с `// @phpstan-ignore ` — дописать `, <identifier>`
 *   в конец списка идентификаторов (до пояснения в скобках, если оно есть).
 * - Иначе — вставить над строкой ошибки новую строку с тем же отступом.
 */
object IgnoreComment {
    private val EXISTING = Regex("""^(\s*//\s*@phpstan-ignore\s+)([\w.\-]+(?:\s*,\s*[\w.\-]+)*)""")

    /** @param line 1-based строка ошибки. `null` — правка не нужна или невозможна. */
    fun edit(text: CharSequence, line: Int, identifier: String): TextInsert? {
        val starts = lineStarts(text)
        if (line < 1 || line > starts.size) return null
        val lineStart = starts[line - 1]

        if (line >= 2) {
            val prevStart = starts[line - 2]
            val prev = text.substring(prevStart, lineStart).trimEnd('\n', '\r')
            EXISTING.find(prev)?.let { match ->
                val ids = match.groupValues[2].split(',').map { it.trim() }
                if (identifier in ids) return null
                return TextInsert(prevStart + match.range.last + 1, ", $identifier")
            }
        }

        val indent = text.drop(lineStart).takeWhile { it == ' ' || it == '\t' }
        return TextInsert(lineStart, "$indent// @phpstan-ignore $identifier\n")
    }

    private fun lineStarts(text: CharSequence): List<Int> = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n' && i + 1 <= text.length) add(i + 1) }
    }.let { starts -> if (starts.size > 1 && starts.last() == text.length) starts.dropLast(1) else starts }
}
