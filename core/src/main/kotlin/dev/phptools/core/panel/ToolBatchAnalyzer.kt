package dev.phptools.core.panel

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.project.Project
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.settings.ToolState
import java.nio.file.Path

/** Результат пакетного запуска инструмента. */
sealed interface BatchOutcome {
    data class Done(
        /** Ключ — локальный путь файла (уже смаппленный из контейнера). */
        val problems: Map<Path, List<ReportProblem>>,
        val errors: List<String> = emptyList(),
        val details: List<String> = emptyList(),
    ) : BatchOutcome

    data class Failed(val message: String, val details: List<String> = emptyList()) : BatchOutcome
}

/** То, что плагин инструмента даёт панели и отчёту из `core`. */
interface ToolBatchAnalyzer {
    val spec: ToolSpec

    fun state(project: Project): ToolState

    /**
     * Проверить [targets] (локальные пути файлов и папок) одним или несколькими запусками.
     * Пустой список — весь проект по конфигурации инструмента. Вызывается в фоне, без read action.
     */
    fun analyze(project: Project, tool: PreparedTool, targets: List<Path>): BatchOutcome

    /** Quick-fix «игнорировать» для ошибки из отчёта или `null`. */
    fun ignoreFix(problem: ReportProblem): IntentionAction? = null

    /**
     * Необязательное действие над файлами из отчёта (например, «Исправить файл» у PHP-CS-Fixer).
     * `null` — действия нет. Вызывается на EDT; долгую работу выполнять в фоне.
     */
    val fileActionText: String? get() = null

    fun runFileAction(project: Project, files: List<Path>) {}
}
