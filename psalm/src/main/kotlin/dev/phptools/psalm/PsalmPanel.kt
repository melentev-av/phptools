package dev.phptools.psalm

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.exec.CommandChunks
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.panel.BatchOutcome
import dev.phptools.core.panel.CheckWithToolAction
import dev.phptools.core.panel.ReportProblem
import dev.phptools.core.panel.ToolBatchAnalyzer
import dev.phptools.core.panel.ToolPanelFactory
import dev.phptools.core.panel.ToolReportService
import dev.phptools.core.settings.ToolConfigurable
import dev.phptools.psalm.PsalmBundle.message
import java.nio.file.Path

/** Пакетный запуск Psalm для панели. */
object PsalmBatchAnalyzer : ToolBatchAnalyzer {
    override val spec = PsalmTool.SPEC

    override fun state(project: Project): PsalmState = PsalmSettings.getInstance(project).state

    override fun analyze(project: Project, tool: PreparedTool, targets: List<Path>): BatchOutcome {
        val state = state(project)
        val config = PsalmSettings.configTarget(project, tool, state)
        val extra = ParametersListUtil.parse(state.extraArgs.orEmpty())
        val details = buildList {
            if (state.showInfo) add(message("report.show.info"))
            tool.runtimeLabel?.let(::add)
        }

        fun args(chunk: List<String>) = PsalmCommand.args(chunk, state.threads, state.showInfo, config, extra)

        val chunks = if (targets.isEmpty()) {
            listOf(emptyList())
        } else {
            val limit = if (SystemInfo.isWindows) CommandChunks.WINDOWS_LIMIT else CommandChunks.UNIX_LIMIT
            CommandChunks.split(tool.commandLength(args(emptyList())), targets.map(tool::toTarget), limit)
        }

        val problems = linkedMapOf<Path, MutableList<ReportProblem>>()
        for (chunk in chunks) {
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) break
            val output = tool.run(args(chunk))
            if (output.cancelled) break
            if (output.timedOut) return BatchOutcome.Failed(message("timeout.title", ToolReportService.batchTimeoutMs(state) / 1000), details)
            when (val result = PsalmOutput.parse(output.exitCode, output.stdout, output.stderr)) {
                is PsalmResult.Ok -> result.issues.forEach { issue ->
                    problems.getOrPut(tool.fromTarget(issue.filePath)) { mutableListOf() } += ReportProblem(
                        line = issue.lineFrom,
                        message = issue.message,
                        identifier = issue.type.ifBlank { null },
                        tip = if (issue.isInfo) message("report.info") else null,
                        docUrl = issue.link,
                        ignorable = issue.type.isNotBlank(),
                    )
                }
                is PsalmResult.Failed -> return BatchOutcome.Failed(result.details, details)
            }
        }
        return BatchOutcome.Done(problems, emptyList(), details)
    }

    override fun ignoreFix(problem: ReportProblem): IntentionAction? {
        val line = problem.line ?: return null
        val type = problem.identifier ?: return null
        return PsalmSuppressFix(line, type)
    }
}

class PsalmToolWindowFactory : ToolPanelFactory(PsalmBatchAnalyzer)

class PsalmCheckAction : CheckWithToolAction(PsalmBatchAnalyzer)

class PsalmConfigurable(project: Project) : ToolConfigurable<PsalmState>(project, PsalmTool.SPEC) {
    override fun storedState(): PsalmState = PsalmSettings.getInstance(project).state

    override fun createState() = PsalmState()

    override fun Panel.toolSpecificSettings(state: PsalmState) {
        group(message("settings.group")) {
            row {
                checkBox(message("settings.show.info"))
                    .bindSelected({ state.showInfo }, { state.showInfo = it })
            }
            row(message("settings.threads")) {
                intTextField(1..64)
                    .bindIntText({ state.threads }, { state.threads = it })
                    .comment(message("settings.threads.comment"))
            }
        }
    }
}
