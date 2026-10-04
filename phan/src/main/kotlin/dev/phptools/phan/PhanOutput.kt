package dev.phptools.phan

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

data class PhanIssue(
    /** Имя проверки: `PhanUndeclaredMethod`. */
    val checkName: String,
    /** Текст без префикса `<Category> <check_name> `. */
    val message: String,
    /** 0 — low, 5 — normal, 10 — critical. */
    val severity: Int,
    /** Путь относительно рабочей папки Phan (так же и в Docker). */
    val path: String,
    val lineBegin: Int,
    val lineEnd: Int,
    /** 1-based; есть только у некоторых проблем (например, `PhanSyntaxError`). */
    val column: Int?,
) {
    val isLow: Boolean get() = severity <= 0
}

sealed interface PhanResult {
    data class Ok(val issues: List<PhanIssue>) : PhanResult

    /** Нет расширения php-ast, а `--allow-polyfill-parser` не передан. */
    data object PhpAstMissing : PhanResult

    data class Failed(val details: String) : PhanResult
}

/** Разбор вывода `phan -m json`. Чистая логика без платформы. Проверено на Phan 6.0.7. */
object PhanOutput {

    /** Коды выхода: 0 — проблем нет, 1 — есть проблемы; ошибка конфига — тоже 1, но без JSON. */
    fun parse(exitCode: Int, stdout: String, stderr: String): PhanResult {
        if ("php-ast extension must be loaded" in stderr || "php-ast extension must be loaded" in stdout) return PhanResult.PhpAstMissing
        if (exitCode != 0 && exitCode != 1) return PhanResult.Failed(failure(stdout, stderr))

        val start = stdout.indexOf('[')
        val end = stdout.lastIndexOf(']')
        if (start < 0 || end <= start) {
            // Файл вне directory_list: код 0 и пустой stdout.
            return if (exitCode == 0) PhanResult.Ok(emptyList()) else PhanResult.Failed(failure(stdout, stderr))
        }
        val array = try {
            JsonParser.parseString(stdout.substring(start, end + 1)).takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return PhanResult.Failed(failure(stdout, stderr))

        val issues = array.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val location = o.obj("location") ?: return@mapNotNull null
            val lines = location.obj("lines")
            val begin = lines?.int("begin") ?: return@mapNotNull null
            val checkName = o.string("check_name").orEmpty()
            PhanIssue(
                checkName = checkName,
                message = stripPrefix(o.string("description").orEmpty(), checkName),
                severity = o.int("severity") ?: 5,
                path = location.string("path").orEmpty(),
                lineBegin = begin,
                lineEnd = lines.int("end") ?: begin,
                column = lines.int("begin_column"),
            )
        }
        return PhanResult.Ok(issues)
    }

    /** `"TypeError PhanTypeMismatchReturnReal Returning …"` → `"Returning …"`. */
    fun stripPrefix(description: String, checkName: String): String {
        if (checkName.isEmpty()) return description
        val idx = description.indexOf("$checkName ")
        return if (idx in 0..40) description.substring(idx + checkName.length + 1) else description
    }

    /** Проблемы одного файла: `path` совпадает с [relativePath] (относительно рабочей папки); один файл в выводе — он. */
    fun issuesFor(issues: List<PhanIssue>, relativePath: String): List<PhanIssue> {
        val target = normalize(relativePath)
        val matching = issues.filter { normalize(it.path) == target || normalize(it.path).endsWith("/$target") }
        if (matching.isNotEmpty()) return matching
        return if (issues.map { normalize(it.path) }.distinct().size == 1) issues else emptyList()
    }

    /** Документация по проверке: заголовки `## PhanXxx` в `internal/Issue-Types-Caught-by-Phan.md`. */
    fun docUrl(checkName: String): String? =
        checkName.takeIf { it.startsWith("Phan") && it.all(Char::isLetterOrDigit) }
            ?.let { "https://github.com/phan/phan/blob/v6/internal/Issue-Types-Caught-by-Phan.md#${it.lowercase()}" }

    private fun normalize(path: String) = path.replace('\\', '/').removePrefix("./")

    private fun failure(stdout: String, stderr: String) = stderr.ifBlank { stdout }.trim()

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
}

/** Сборка аргументов Phan. */
object PhanCommand {
    /**
     * @param files пути относительно рабочей папки (`-I`); пустой список — весь `directory_list` из конфига.
     * @param processes `-j`, только для панели (в редакторе — 1, не передаётся).
     */
    fun args(
        files: List<String>,
        allowPolyfillParser: Boolean,
        memoryLimit: String?,
        config: String?,
        extraArgs: List<String>,
        processes: Int = 1,
    ): List<String> = buildList {
        addAll(listOf("-m", "json", "--no-progress-bar"))
        if (allowPolyfillParser) add("--allow-polyfill-parser")
        memoryLimit?.trim()?.takeIf { it.isNotEmpty() }?.let { addAll(listOf("--memory-limit", it)) }
        config?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-k", it)) }
        if (processes > 1) addAll(listOf("-j", processes.toString()))
        addAll(extraArgs)
        if (files.isNotEmpty()) addAll(listOf("-I", files.joinToString(",")))
    }
}

/** Вставка текста [text] по смещению [offset]. */
data class TextInsert(val offset: Int, val text: String)

/**
 * Правка для `// @phan-suppress-next-line <Check>`. Чистая логика без платформы.
 * Несколько проверок через запятую (проверено на Phan 6.0.7).
 */
object PhanSuppress {
    private val EXISTING = Regex("""^(\s*//\s*@phan-suppress-next-line\s+)(\w+(?:\s*,\s*\w+)*)""")

    /** @param line 1-based строка проблемы. `null` — правка не нужна или невозможна. */
    fun edit(text: CharSequence, line: Int, checkName: String): TextInsert? {
        val starts = lineStarts(text)
        if (line < 1 || line > starts.size || checkName.isBlank()) return null
        val lineStart = starts[line - 1]

        if (line >= 2) {
            val prevStart = starts[line - 2]
            val prev = text.substring(prevStart, lineStart).trimEnd('\n', '\r')
            EXISTING.find(prev)?.let { match ->
                val names = match.groupValues[2].split(',').map { it.trim() }
                if (checkName in names) return null
                return TextInsert(prevStart + match.range.last + 1, ", $checkName")
            }
        }

        val indent = text.drop(lineStart).takeWhile { it == ' ' || it == '\t' }
        return TextInsert(lineStart, "$indent// @phan-suppress-next-line $checkName\n")
    }

    private fun lineStarts(text: CharSequence): List<Int> = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.let { starts -> if (starts.size > 1 && starts.last() == text.length) starts.dropLast(1) else starts }
}
