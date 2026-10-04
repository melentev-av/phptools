package dev.phptools.core.panel

import java.nio.file.Path

/** Ошибка в отчёте панели. */
data class ReportProblem(
    /** 1-based; `null` — ошибка уровня файла. */
    val line: Int?,
    val message: String,
    val identifier: String? = null,
    val tip: String? = null,
    /** Ссылка на документацию по идентификатору; открывает пользователь. */
    val docUrl: String? = null,
    /** Можно ли подавить ошибку quick-fix'ом инструмента. */
    val ignorable: Boolean = false,
)

data class ReportFile(
    val path: Path,
    /** Путь относительно корня проекта, `/` как разделитель. */
    val relativePath: String,
    val problems: List<ReportProblem>,
)

/** Результат одного запуска из панели. */
data class Report(
    val toolName: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    /** Сколько файлов/папок было передано; 0 — весь проект по конфигу. */
    val requestedTargets: Int,
    val files: List<ReportFile>,
    /** Ошибки запуска и конфигурации (не привязаны к файлам). */
    val errors: List<String>,
    /** Короткие факты о запуске для шапки: «уровень 6», «phpstan.neon», «Docker». */
    val details: List<String>,
) {
    val problemCount: Int get() = files.sumOf { it.problems.size }
}

enum class Grouping { BY_FILE, BY_IDENTIFIER }

/** Одна строка отчёта для группировки по идентификатору. */
data class ProblemRef(val file: ReportFile, val problem: ReportProblem)

/** Фильтрация, сортировка, группировка и экспорт отчёта. Чистая логика без платформы. */
object ReportView {

    /** Файлы с ошибками, подходящими под [query] (по сообщению, идентификатору, tip и пути); ошибки отсортированы по строке. */
    fun filter(report: Report, query: String): List<ReportFile> {
        val q = query.trim()
        return report.files
            .map { file ->
                val problems = if (q.isEmpty() || file.relativePath.contains(q, ignoreCase = true)) {
                    file.problems
                } else {
                    file.problems.filter { it.matches(q) }
                }
                file.copy(problems = sortProblems(problems))
            }
            .filter { it.problems.isNotEmpty() }
            .sortedBy { it.relativePath.lowercase() }
    }

    /** Группы «идентификатор → ошибки», самые частые сверху; без идентификатора — в группе `""` в конце. */
    fun groupByIdentifier(files: List<ReportFile>): List<Pair<String, List<ProblemRef>>> =
        files.flatMap { file -> file.problems.map { ProblemRef(file, it) } }
            .groupBy { it.problem.identifier.orEmpty() }
            .entries
            .sortedWith(compareBy<Map.Entry<String, List<ProblemRef>>>({ it.key.isEmpty() }, { -it.value.size }, { it.key }))
            .map { it.key to it.value }

    /** Ошибки уровня файла первыми, дальше по строке. */
    fun sortProblems(problems: List<ReportProblem>): List<ReportProblem> =
        problems.sortedWith(compareBy({ it.line != null }, { it.line ?: 0 }))

    fun toMarkdown(report: Report, files: List<ReportFile> = filter(report, "")): String = buildString {
        append("# ").append(report.toolName).append(" report\n\n")
        val total = files.sumOf { it.problems.size }
        append("- Errors: ").append(total).append(" in ").append(files.size).append(" file(s)\n")
        report.details.forEach { append("- ").append(it).append('\n') }
        append("- Duration: ").append(String.format(java.util.Locale.ROOT, "%.1f", report.durationMillis / 1000.0)).append(" s\n")
        if (report.errors.isNotEmpty()) {
            append("\n## Run errors\n\n```\n").append(report.errors.joinToString("\n")).append("\n```\n")
        }
        for (file in files) {
            append("\n## ").append(file.relativePath).append(" (").append(file.problems.size).append(")\n\n")
            for (p in file.problems) {
                append("- ").append(p.line?.let { "Line $it" } ?: "File").append(": ").append(p.message)
                p.identifier?.let { id ->
                    append(" — ")
                    if (p.docUrl != null) append("[`").append(id).append("`](").append(p.docUrl).append(')') else append('`').append(id).append('`')
                }
                append('\n')
                p.tip?.takeIf { it.isNotBlank() }?.let { append("  - Tip: ").append(it.trim()).append('\n') }
            }
        }
    }

    private fun ReportProblem.matches(q: String): Boolean =
        message.contains(q, ignoreCase = true) ||
            identifier?.contains(q, ignoreCase = true) == true ||
            tip?.contains(q, ignoreCase = true) == true
}
