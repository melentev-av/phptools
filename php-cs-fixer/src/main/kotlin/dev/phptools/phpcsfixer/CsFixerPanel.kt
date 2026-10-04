package dev.phptools.phpcsfixer

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import dev.phptools.core.exec.CommandChunks
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.panel.BatchOutcome
import dev.phptools.core.panel.CheckWithToolAction
import dev.phptools.core.panel.ReportProblem
import dev.phptools.core.panel.ToolBatchAnalyzer
import dev.phptools.core.panel.ToolPanelFactory
import dev.phptools.core.panel.ToolReportService
import dev.phptools.core.settings.ToolConfigurable
import dev.phptools.phpcsfixer.CsFixerBundle.message
import java.nio.file.Path

/** Пакетная проверка для панели: какие файлы и строки поменяет PHP-CS-Fixer. «Исправить» — реальный `fix`. */
object CsFixerBatchAnalyzer : ToolBatchAnalyzer {
    override val spec = PhpCsFixerTool.SPEC

    override fun state(project: Project): CsFixerState = CsFixerSettings.getInstance(project).state

    override fun analyze(project: Project, tool: PreparedTool, targets: List<Path>): BatchOutcome {
        val state = state(project)
        val config = CsFixerRun.configTarget(project, tool, state)
        val extra = CsFixerRun.extraArgs(state)
        val details = buildList {
            if (state.allowRisky) add(message("report.risky"))
            tool.runtimeLabel?.let(::add)
        }

        fun args(chunk: List<String>) = CsFixerCommand.dryRunArgs(chunk, config, state.allowRisky, extra)

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
            when (val result = CsFixerOutput.parse(output.exitCode, output.stdout, output.stderr)) {
                is CsFixerResult.Ok -> result.files.forEach { file ->
                    problems.getOrPut(localPath(tool, file.name)) { mutableListOf() } += toProblems(file)
                }
                CsFixerResult.SyntaxError -> Unit
                is CsFixerResult.Failed -> return BatchOutcome.Failed(result.details, details)
            }
        }
        return BatchOutcome.Done(problems, emptyList(), details)
    }

    override val fileActionText: String get() = message("report.fix.file")

    override fun runFileAction(project: Project, files: List<Path>) = CsFixerFixRunner.fixPaths(project, files)

    /** `name` — относительно рабочей папки инструмента (в Docker тоже) или абсолютный путь. */
    private fun localPath(tool: PreparedTool, name: String): Path {
        val normalized = name.replace('\\', '/')
        return if (normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
            tool.fromTarget(name)
        } else {
            tool.plan.workDir.resolve(normalized).normalize()
        }
    }

    /** Одна строка отчёта на каждый изменяемый фрагмент: что было → что станет. */
    private fun toProblems(file: CsFixerFile): List<ReportProblem> {
        val fixers = file.fixers.joinToString(", ")
        return CsFixerDiff.parse(file.diff).flatMap(CsFixerDiff::changes).map { change ->
            val removed = change.removed?.trim()
            val added = change.added?.trim()
            ReportProblem(
                line = change.range.first,
                message = removed?.takeIf { it.isNotEmpty() } ?: message("report.insert"),
                tip = listOfNotNull(added?.let { "→ ${it.ifEmpty { message("report.blank.line") }}" }, fixers.ifEmpty { null })
                    .joinToString("   ·   "),
            )
        }
    }
}

class CsFixerToolWindowFactory : ToolPanelFactory(CsFixerBatchAnalyzer)

class CsFixerCheckAction : CheckWithToolAction(CsFixerBatchAnalyzer)

class CsFixerConfigurable(project: Project) : ToolConfigurable<CsFixerState>(project, PhpCsFixerTool.SPEC) {
    override fun storedState(): CsFixerState = CsFixerSettings.getInstance(project).state

    override fun createState() = CsFixerState()

    override fun afterApply() {
        CsFixerFinder.reset(project)
    }

    override fun Panel.toolSpecificSettings(state: CsFixerState) {
        group(message("settings.group")) {
            row {
                checkBox(message("settings.risky"))
                    .bindSelected({ state.allowRisky }, { state.allowRisky = it })
                    .comment(message("settings.risky.comment"))
            }
        }
    }
}
