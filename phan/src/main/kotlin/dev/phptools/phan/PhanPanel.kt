package dev.phptools.phan

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.exec.CommandChunks
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.panel.BatchOutcome
import dev.phptools.core.panel.ChangedFiles
import dev.phptools.core.panel.CheckWithToolAction
import dev.phptools.core.panel.ReportProblem
import dev.phptools.core.panel.ToolBatchAnalyzer
import dev.phptools.core.panel.ToolPanelFactory
import dev.phptools.core.panel.ToolReportService
import dev.phptools.core.settings.ToolConfigurable
import dev.phptools.phan.PhanBundle.message
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** Пакетный запуск Phan для панели: `-I a.php,b.php` (папки раскрываются в PHP-файлы) или весь `directory_list`. */
object PhanBatchAnalyzer : ToolBatchAnalyzer {
    override val spec = PhanTool.SPEC

    override fun state(project: Project): PhanState = PhanSettings.getInstance(project).state

    override fun analyze(project: Project, tool: PreparedTool, targets: List<Path>): BatchOutcome {
        val state = state(project)
        val config = PhanSettings.configTarget(project, tool, state)
        val extra = ParametersListUtil.parse(state.extraArgs.orEmpty())
        val details = buildList {
            if (state.allowPolyfillParser) add(message("report.polyfill"))
            if (tool.isDocker) add("Docker")
        }

        fun args(files: List<String>) = PhanCommand.args(files, state.allowPolyfillParser, state.memoryLimit, config, extra, state.processes)

        val files = targets.flatMap(::phpFiles).mapNotNull { PhanSettings.relativeToWorkDir(tool, it) }.distinct()
        if (targets.isNotEmpty() && files.isEmpty()) return BatchOutcome.Done(emptyMap(), emptyList(), details)
        val chunks = if (files.isEmpty()) {
            listOf(emptyList())
        } else {
            val limit = if (SystemInfo.isWindows) CommandChunks.WINDOWS_LIMIT else CommandChunks.UNIX_LIMIT
            CommandChunks.split(tool.commandLength(args(emptyList())) + 3, files, limit)
        }

        val problems = linkedMapOf<Path, MutableList<ReportProblem>>()
        for (chunk in chunks) {
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) break
            val output = tool.run(args(chunk))
            if (output.cancelled) break
            if (output.timedOut) return BatchOutcome.Failed(message("timeout.title", ToolReportService.batchTimeoutMs(state) / 1000), details)
            when (val result = PhanOutput.parse(output.exitCode, output.stdout, output.stderr)) {
                is PhanResult.Ok -> result.issues.forEach { issue ->
                    problems.getOrPut(localPath(tool, issue.path)) { mutableListOf() } += ReportProblem(
                        line = issue.lineBegin,
                        message = issue.message,
                        identifier = issue.checkName.ifBlank { null },
                        tip = PhanAnnotator.severityText(issue.severity),
                        docUrl = PhanOutput.docUrl(issue.checkName),
                        ignorable = issue.checkName.isNotBlank(),
                    )
                }
                PhanResult.PhpAstMissing -> return BatchOutcome.Failed(message("php.ast.details"), details)
                is PhanResult.Failed -> return BatchOutcome.Failed(result.details, details)
            }
        }
        return BatchOutcome.Done(problems, emptyList(), details)
    }

    override fun ignoreFix(problem: ReportProblem): IntentionAction? {
        val line = problem.line ?: return null
        val check = problem.identifier ?: return null
        return PhanSuppressFix(line, check)
    }

    /** Папка → её PHP-файлы (без vendor/); файл → он сам. `-I` принимает только файлы. */
    private fun phpFiles(path: Path): List<Path> {
        if (!Files.isDirectory(path)) return listOf(path)
        return Files.walk(path).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) && ChangedFiles.isCandidate(it.toString()) }
                .toList()
        }
    }

    /** `location.path` — относительно рабочей папки (в Docker тоже) или абсолютный. */
    private fun localPath(tool: PreparedTool, path: String): Path {
        val normalized = path.replace('\\', '/')
        return if (normalized.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(normalized)) {
            tool.fromTarget(path)
        } else {
            tool.plan.workDir.resolve(normalized.removePrefix("./")).normalize()
        }
    }
}

class PhanToolWindowFactory : ToolPanelFactory(PhanBatchAnalyzer)

class PhanCheckAction : CheckWithToolAction(PhanBatchAnalyzer)

class PhanConfigurable(project: Project) : ToolConfigurable<PhanState>(project, PhanTool.SPEC) {
    override fun storedState(): PhanState = PhanSettings.getInstance(project).state

    override fun createState() = PhanState()

    override fun Panel.toolSpecificSettings(state: PhanState) {
        group(message("settings.group")) {
            row {
                checkBox(message("settings.polyfill"))
                    .bindSelected({ state.allowPolyfillParser }, { state.allowPolyfillParser = it })
                    .comment(message("settings.polyfill.comment"))
            }
            row(message("settings.memory")) {
                textField()
                    .bindText({ state.memoryLimit.orEmpty() }, { state.memoryLimit = it })
                    .comment(message("settings.memory.comment"))
            }
            row(message("settings.processes")) {
                intTextField(1..64)
                    .bindIntText({ state.processes }, { state.processes = it })
                    .comment(message("settings.processes.comment"))
            }
        }
    }
}
