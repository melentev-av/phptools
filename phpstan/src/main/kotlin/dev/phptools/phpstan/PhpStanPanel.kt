package dev.phptools.phpstan

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.exec.CommandChunks
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.panel.BatchOutcome
import dev.phptools.core.panel.CheckWithToolAction
import dev.phptools.core.panel.ReportProblem
import dev.phptools.core.panel.ToolBatchAnalyzer
import dev.phptools.core.panel.ToolPanelFactory
import dev.phptools.core.panel.ToolReportService
import dev.phptools.phpstan.PhpStanBundle.message
import java.nio.file.Path

/** Пакетный запуск PHPStan для панели: несколько файлов/папок одним процессом (с разбиением на пачки) или весь проект. */
object PhpStanBatchAnalyzer : ToolBatchAnalyzer {
    override val spec = PhpStanTool.SPEC

    override fun state(project: Project): PhpStanState = PhpStanSettings.getInstance(project).state

    override fun analyze(project: Project, tool: PreparedTool, targets: List<Path>): BatchOutcome {
        val state = state(project)
        val config = PhpStanLevels.configTarget(project, tool, state)
        val level = PhpStanLevels.levelArgument(project, tool, state, config)
        val extra = ParametersListUtil.parse(state.extraArgs.orEmpty())
        val details = details(project, tool, state, config)

        fun args(chunk: List<String>) = PhpStanCommand.batchArgs(chunk, state.memoryLimit, config, level, extra)

        val chunks = if (targets.isEmpty()) {
            listOf(emptyList())
        } else {
            val limit = if (SystemInfo.isWindows) CommandChunks.WINDOWS_LIMIT else CommandChunks.UNIX_LIMIT
            CommandChunks.split(tool.commandLength(args(emptyList())), targets.map(tool::toTarget), limit)
        }

        val problems = linkedMapOf<Path, MutableList<ReportProblem>>()
        val errors = mutableListOf<String>()
        for (chunk in chunks) {
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) break
            val output = tool.run(args(chunk))
            if (output.cancelled) break
            if (output.timedOut) return BatchOutcome.Failed(message("timeout.title", ToolReportService.batchTimeoutMs(state) / 1000), details)

            when (val result = PhpStanOutput.parse(output.exitCode, output.stdout, output.stderr)) {
                is PhpStanResult.Ok -> {
                    result.files.forEach { (toolPath, messages) ->
                        problems.getOrPut(tool.fromTarget(toolPath)) { mutableListOf() } += messages.map(::toReportProblem)
                    }
                    errors += result.errors
                }
                PhpStanResult.NoFiles, PhpStanResult.TmpFileUnsupported -> Unit
                is PhpStanResult.Failed -> {
                    val text = if ("No rules detected" in result.details) message("no.level.hint") + "\n\n" + result.details else result.details
                    return BatchOutcome.Failed(text, details)
                }
            }
        }
        return BatchOutcome.Done(problems, errors, details)
    }

    override fun ignoreFix(problem: ReportProblem): IntentionAction? {
        val line = problem.line ?: return null
        val identifier = problem.identifier ?: return null
        return if (problem.ignorable) PhpStanIgnoreFix(line, identifier) else null
    }

    private fun toReportProblem(m: PhpStanMessage) = ReportProblem(
        line = m.line,
        message = m.message,
        identifier = m.identifier,
        tip = m.tip,
        docUrl = m.identifier?.let(PhpStanAnnotator::identifierUrl),
        ignorable = m.ignorable,
    )

    /** Факты для шапки отчёта: уровень, конфиг, Docker. */
    private fun details(project: Project, tool: PreparedTool, state: PhpStanState, config: String?): List<String> = buildList {
        PhpStanLevels.effectiveLevel(project, tool, state, config)?.let { add(message("report.level", it)) }
        state.configPath.orEmpty().trim().takeIf { it.isNotEmpty() }?.let { add(Path.of(it).fileName.toString()) }
        if (tool.isDocker) add("Docker")
    }
}

class PhpStanToolWindowFactory : ToolPanelFactory(PhpStanBatchAnalyzer)

class PhpStanCheckAction : CheckWithToolAction(PhpStanBatchAnalyzer)
