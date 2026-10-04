package dev.phptools.psalm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

data class PsalmIssue(
    val severity: String,
    val type: String,
    val message: String,
    /** Путь на стороне инструмента: абсолютный (в Docker — путь контейнера). */
    val filePath: String,
    /** Путь относительно корня конфигурации. */
    val fileName: String,
    val lineFrom: Int,
    val lineTo: Int,
    /** 1-based, **в байтах UTF-8**. */
    val columnFrom: Int?,
    /** 1-based, в байтах, не включительно. */
    val columnTo: Int?,
    val link: String?,
) {
    val isInfo: Boolean get() = severity.equals("info", ignoreCase = true)
}

sealed interface PsalmResult {
    data class Ok(val issues: List<PsalmIssue>) : PsalmResult
    data class Failed(val details: String) : PsalmResult
}

/** Разбор вывода `psalm --output-format=json`. Чистая логика без платформы. */
object PsalmOutput {

    /** Коды выхода Psalm 6: 0 — проблем нет, 2 — есть проблемы, 1 — ошибка конфигурации. */
    fun parse(exitCode: Int, stdout: String, stderr: String): PsalmResult {
        if (exitCode != 0 && exitCode != 2) return PsalmResult.Failed(failureText(stdout, stderr))
        val start = stdout.indexOf('[')
        val end = stdout.lastIndexOf(']')
        if (start < 0 || end <= start) {
            return if (exitCode == 0 && stdout.isBlank()) PsalmResult.Ok(emptyList()) else PsalmResult.Failed(failureText(stdout, stderr))
        }
        val array = try {
            JsonParser.parseString(stdout.substring(start, end + 1)).takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return PsalmResult.Failed(failureText(stdout, stderr))

        val issues = array.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val line = o.int("line_from") ?: return@mapNotNull null
            PsalmIssue(
                severity = o.string("severity") ?: "error",
                type = o.string("type") ?: "",
                message = o.string("message") ?: return@mapNotNull null,
                filePath = o.string("file_path").orEmpty(),
                fileName = o.string("file_name").orEmpty(),
                lineFrom = line,
                lineTo = o.int("line_to") ?: line,
                columnFrom = o.int("column_from"),
                columnTo = o.int("column_to"),
                link = o.string("link"),
            )
        }
        return PsalmResult.Ok(issues)
    }

    /**
     * Проблемы одного файла: `file_path` оканчивается на [relativePath] или `file_name` с ним совпадает;
     * если ничего не совпало, но файл в выводе один — берётся он.
     */
    fun issuesFor(issues: List<PsalmIssue>, relativePath: String): List<PsalmIssue> {
        val suffix = relativePath.replace('\\', '/').trimStart('/')
        val matching = issues.filter {
            val path = it.filePath.replace('\\', '/')
            path == suffix || path.endsWith("/$suffix") || it.fileName.replace('\\', '/') == suffix
        }
        if (matching.isNotEmpty()) return matching
        return if (issues.map { it.filePath }.distinct().size == 1) issues else emptyList()
    }

    private fun failureText(stdout: String, stderr: String) = stderr.ifBlank { stdout }.trim()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}

/** Сборка аргументов Psalm. */
object PsalmCommand {
    fun args(
        targets: List<String>,
        threads: Int,
        showInfo: Boolean,
        config: String?,
        extraArgs: List<String>,
    ): List<String> = buildList {
        addAll(listOf("--output-format=json", "--no-progress", "--monochrome"))
        add("--threads=${threads.coerceAtLeast(1)}")
        add("--show-info=$showInfo")
        config?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-c", it)) }
        addAll(extraArgs)
        addAll(targets)
    }
}

/** Вставка текста [text] по смещению [offset]. */
data class TextInsert(val offset: Int, val text: String)

/**
 * Правка для `@psalm-suppress`. Чистая логика без платформы.
 *
 * - Предыдущая строка — однострочный докблок `/** @psalm-suppress A, B */` → дописать `, <type>` в конец списка.
 *   Несколько типов — только через запятую: через пробел Psalm считает второй тип описанием (проверено на 6.19.1).
 * - Иначе — вставить над строкой проблемы `/** @psalm-suppress <type> */` с тем же отступом.
 * - Многострочные докблоки не трогаются.
 */
object PsalmSuppress {
    private val EXISTING = Regex("""^(\s*/\*\*\s*@psalm-suppress\s+)([\w\\]+(?:\s*,\s*[\w\\]+)*)(\s*\*/\s*)$""")

    /** @param line 1-based строка проблемы. `null` — правка не нужна или невозможна. */
    fun edit(text: CharSequence, line: Int, type: String): TextInsert? {
        val starts = lineStarts(text)
        if (line < 1 || line > starts.size || type.isBlank()) return null
        val lineStart = starts[line - 1]

        if (line >= 2) {
            val prevStart = starts[line - 2]
            val prev = text.substring(prevStart, lineStart).trimEnd('\n', '\r')
            EXISTING.find(prev)?.let { match ->
                val types = match.groupValues[2].split(',').map { it.trim() }
                if (type in types) return null
                return TextInsert(prevStart + match.groups[2]!!.range.last + 1, ", $type")
            }
        }

        val indent = text.drop(lineStart).takeWhile { it == ' ' || it == '\t' }
        return TextInsert(lineStart, "$indent/** @psalm-suppress $type */\n")
    }

    private fun lineStarts(text: CharSequence): List<Int> = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.let { starts -> if (starts.size > 1 && starts.last() == text.length) starts.dropLast(1) else starts }
}
