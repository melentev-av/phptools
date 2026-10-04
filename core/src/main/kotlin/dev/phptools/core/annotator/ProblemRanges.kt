package dev.phptools.core.annotator

/**
 * Перевод строк/колонок инструмента в смещения текста. Чистая логика без платформы.
 *
 * - строки клампятся к границам текста;
 * - без колонок — от первого непробельного символа до конца строки;
 * - пустой диапазон расширяется на всю строку;
 * - колонки могут быть в байтах UTF-8 (`byteColumns`, например у Psalm) — тогда они переводятся в символы по тексту строки.
 */
class ProblemRanges(private val text: CharSequence) {
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    val lineCount: Int get() = lineStarts.size

    /** @return полуоткрытый диапазон смещений `[start, end)`. */
    fun compute(
        line: Int,
        endLine: Int = line,
        column: Int? = null,
        endColumn: Int? = null,
        byteColumns: Boolean = false,
    ): IntRange {
        val startLine = clampLine(line)
        val lastLine = clampLine(endLine).coerceAtLeast(startLine)

        fun charColumn(line: Int, col: Int) = if (byteColumns) charColumnFromBytes(line, col) else col
        val start = if (column != null) offsetIn(startLine, charColumn(startLine, column)) else firstNonBlank(startLine)
        val end = if (endColumn != null) offsetIn(lastLine, charColumn(lastLine, endColumn)) else lineEnd(lastLine)

        return if (end > start) start until end else lineStart(startLine) until lineEnd(startLine)
    }

    private fun clampLine(line: Int) = (line - 1).coerceIn(0, lineCount - 1)

    private fun lineStart(line: Int) = lineStarts[line]

    /** Конец строки без `\n` и `\r`. */
    private fun lineEnd(line: Int): Int {
        var end = if (line + 1 < lineCount) lineStarts[line + 1] - 1 else text.length
        while (end > lineStart(line) && (text[end - 1] == '\r' || text[end - 1] == '\n')) end--
        return end
    }

    private fun offsetIn(line: Int, column: Int) = (lineStart(line) + column - 1).coerceIn(lineStart(line), lineEnd(line))

    /** 1-based колонка в байтах UTF-8 → 1-based колонка в символах (UTF-16) строки [line]. */
    private fun charColumnFromBytes(line: Int, byteColumn: Int): Int {
        val start = lineStart(line)
        val end = lineEnd(line)
        val targetBytes = byteColumn - 1
        var bytes = 0
        var i = start
        while (i < end && bytes < targetBytes) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) {
                bytes += 4
                i += 2
                continue
            }
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            i++
        }
        // Колонка за концом строки: сохраняем «хвост» в байтах, дальше её всё равно обрежет offsetIn.
        return i - start + 1 + (targetBytes - bytes).coerceAtLeast(0)
    }

    private fun firstNonBlank(line: Int): Int {
        val end = lineEnd(line)
        var i = lineStart(line)
        while (i < end && text[i].isWhitespace()) i++
        return i
    }
}
