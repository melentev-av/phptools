package dev.phptools.phpcsfixer

/** Один кусок unified diff. Строки 1-based. */
data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    /** Строки нового варианта куска (контекст + добавленные), без переводов строк. */
    val newLines: List<String>,
    /** Диапазоны старых строк, которые реально меняются (для подсветки). */
    val changedOldLines: List<IntRange>,
    /** Сырой текст куска (с заголовком `@@`) — для подсказки. */
    val text: String,
    /** После последней новой строки нет перевода строки (`\ No newline at end of file`). */
    val newNoEol: Boolean = false,
)

/** Одно изменение внутри куска: старые строки [range] и первые удалённая/добавленная строки. */
data class Change(val range: IntRange, val removed: String?, val added: String?)

/** Замена текста `[start, end)` на [text]. */
data class TextReplace(val start: Int, val end: Int, val text: String)

/** Разбор и применение unified diff PHP-CS-Fixer. Чистая логика без платформы. */
object CsFixerDiff {
    private val HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    fun parse(diff: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        val lines = diff.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }

        var i = 0
        while (i < lines.size) {
            val header = HEADER.find(lines[i])
            if (header == null) {
                i++ // `---`/`+++` и всё до первого `@@`
                continue
            }
            val oldStart = header.groupValues[1].toInt()
            val oldCount = header.groupValues[2].ifEmpty { "1" }.toInt()
            val text = StringBuilder(lines[i]).append('\n')
            val newLines = mutableListOf<String>()
            val changed = mutableListOf<IntRange>()
            var oldLine = oldStart
            var removalStart = -1
            var lastKind = ' '
            var newNoEol = false
            i++

            fun closeRemoval() {
                if (removalStart >= 0) {
                    changed += removalStart until oldLine
                    removalStart = -1
                }
            }

            while (i < lines.size && !lines[i].startsWith("@@")) {
                val line = lines[i]
                text.append(line).append('\n')
                when (line.firstOrNull()) {
                    ' ' -> {
                        closeRemoval()
                        newLines += line.substring(1)
                        oldLine++
                        lastKind = ' '
                    }
                    '-' -> {
                        if (removalStart < 0) removalStart = oldLine
                        oldLine++
                        lastKind = '-'
                    }
                    '+' -> {
                        if (removalStart < 0 && lastKind != '-') {
                            // Чистая вставка: к предыдущей старой строке (или к первой строке куска).
                            val anchor = (oldLine - 1).coerceAtLeast(maxOf(oldStart, 1))
                            changed += anchor..anchor
                        }
                        newLines += line.substring(1)
                        lastKind = '+'
                    }
                    '\\' -> if (lastKind == '+' || lastKind == ' ') newNoEol = true
                    null -> { // пустая строка контекста без пробела (бывает при обрезке хвостовых пробелов)
                        closeRemoval()
                        newLines += ""
                        oldLine++
                        lastKind = ' '
                    }
                    else -> Unit
                }
                i++
            }
            closeRemoval()
            hunks += Hunk(oldStart, oldCount, newLines, merge(changed), text.toString().trimEnd('\n'), newNoEol)
        }
        return hunks
    }

    /** Изменения куска по диапазонам [Hunk.changedOldLines]: что было → что станет (для отчёта). */
    fun changes(hunk: Hunk): List<Change> {
        val body = hunk.text.lines().drop(1)
        return hunk.changedOldLines.map { range ->
            var oldLine = hunk.oldStart
            var removed: String? = null
            var added: String? = null
            var inRange = false
            for (line in body) {
                when (line.firstOrNull()) {
                    '-' -> {
                        inRange = oldLine in range
                        if (inRange && removed == null) removed = line.substring(1)
                        oldLine++
                    }
                    '+' -> {
                        // Добавленная строка относится к диапазону, если идёт сразу после его удалений
                        // или это чистая вставка после его последней строки.
                        if ((inRange || oldLine - 1 == range.last) && added == null) added = line.substring(1)
                    }
                    '\\' -> Unit
                    else -> {
                        inRange = false
                        oldLine++
                    }
                }
                if (oldLine > range.last + 1 && !inRange && (removed != null || added != null)) break
            }
            Change(range, removed, added)
        }
    }

    /** Замена в [text], соответствующая куску [hunk]. `null` — кусок не ложится на текст. */
    fun replacementFor(text: CharSequence, hunk: Hunk): TextReplace? {
        val starts = lineStarts(text)
        val lineCount = starts.size
        val endsWithEol = text.isNotEmpty() && text[text.length - 1] == '\n'

        if (hunk.oldCount == 0) {
            // Вставка после строки oldStart (0 — в начало файла).
            if (hunk.oldStart > lineCount) return null
            val offset = if (hunk.oldStart == 0) 0 else lineEndWithEol(text, starts, hunk.oldStart - 1)
            return TextReplace(offset, offset, hunk.newLines.joinToString("") { "$it\n" })
        }

        val first = hunk.oldStart - 1
        val last = first + hunk.oldCount - 1
        if (first < 0 || last >= lineCount) return null

        val start = starts[first]
        var end = lineEnd(text, starts, last)
        var replacement = hunk.newLines.joinToString("\n")
        if (last == lineCount - 1) {
            // Кусок доходит до конца файла: перевод строки в конце — как в новом варианте.
            val wantEol = !hunk.newNoEol && hunk.newLines.isNotEmpty()
            if (wantEol && !endsWithEol) replacement += "\n"
            if (!wantEol && endsWithEol) end = text.length
        }
        return TextReplace(start, end, replacement)
    }

    /** Применить все куски (с конца к началу, чтобы номера строк не съезжали). `null` — какой-то кусок не лёг. */
    fun applyAll(text: String, hunks: List<Hunk>): String? {
        var result = text
        for (hunk in hunks.sortedByDescending { it.oldStart }) {
            val r = replacementFor(result, hunk) ?: return null
            result = result.substring(0, r.start) + r.text + result.substring(r.end)
        }
        return result
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && r.first <= prev.last + 1) out[out.size - 1] = prev.first..maxOf(prev.last, r.last) else out += r
        }
        return out
    }

    /** Начала строк; завершающий перевод строки не порождает лишнюю пустую строку. */
    private fun lineStarts(text: CharSequence): List<Int> = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n' && i + 1 < text.length) add(i + 1) }
    }

    private fun lineEnd(text: CharSequence, starts: List<Int>, line: Int): Int {
        val next = if (line + 1 < starts.size) starts[line + 1] else text.length
        return if (next > starts[line] && text[next - 1] == '\n') next - 1 else next
    }

    private fun lineEndWithEol(text: CharSequence, starts: List<Int>, line: Int): Int =
        if (line + 1 < starts.size) starts[line + 1] else text.length
}
