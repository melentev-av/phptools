package dev.phptools.core.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.settings.ToolState
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Запуски инструмента из панели и последний отчёт. Сервис проекта; у каждого плагина своя копия `core`,
 * а значит и свой экземпляр.
 */
@Service(Service.Level.PROJECT)
class ToolReportService(private val project: Project) : Disposable {

    enum class Event { STARTED, FINISHED }

    var report: Report? = null
        private set

    val isRunning: Boolean get() = current != null

    /** Можно ли повторить последний запуск. */
    val canRerun: Boolean get() = lastRun != null && !isRunning

    private var lastRun: Pair<ToolBatchAnalyzer, List<VirtualFile>>? = null
    /** Текущий запуск; null — ничего не выполняется. Меняется только на EDT. */
    private var current: RunTask? = null
    private var stamps: Map<Path, Long> = emptyMap()
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    fun addListener(parent: Disposable, listener: (Event) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Проверить [targets] (файлы и папки); пустой список — весь проект по конфигурации.
     * Несохранённые документы сохраняются: анализ идёт по файлам на диске. Можно звать с EDT из любого обработчика.
     */
    fun run(analyzer: ToolBatchAnalyzer, targets: List<VirtualFile>) {
        // Сохранение документов требует write-intent lock, которого нет у обработчиков Swing-кнопок.
        // invokeLater выполняет код под ним (WriteIntentReadAction — @ApiStatus.Experimental в 262).
        ApplicationManager.getApplication().invokeLater({ start(analyzer, targets) }, project.disposed)
    }

    private fun start(analyzer: ToolBatchAnalyzer, targets: List<VirtualFile>) {
        FileDocumentManager.getInstance().saveAllDocuments()
        cancel()
        lastRun = analyzer to targets
        showToolWindow(analyzer)

        val spec = analyzer.spec
        val paths = targets.filter { it.isInLocalFileSystem }.map { it.toNioPath() }
        val context = targets.firstOrNull { !it.isDirectory } ?: targets.firstOrNull()?.let { it.findChild("composer.json") ?: it }
        val startedAt = System.currentTimeMillis()

        val task = object : RunTask(project, message("panel.progress", spec.displayName)) {
            private var outcome: BatchOutcome? = null

            override fun run(progress: ProgressIndicator) {
                attach(progress)
                if (progress.isCanceled) return
                progress.isIndeterminate = true
                val state = analyzer.state(project)
                outcome = when (val lookup = ToolLocator.locate(project, spec, state, context)) {
                    is ToolLocator.Lookup.Ready -> analyzer.analyze(project, lookup.tool.withTimeout(batchTimeoutMs(state)), paths)
                    is ToolLocator.Lookup.Misconfigured -> BatchOutcome.Failed("${lookup.title}\n${lookup.details}")
                    ToolLocator.Lookup.NotInstalled -> BatchOutcome.Failed(message("notification.not.installed", spec.binaryName))
                }
            }

            override fun onSuccess() {
                val result = outcome ?: return
                if (isCancelled) return
                publish(buildReport(spec.displayName, startedAt, targets.size, result))
            }

            override fun onFinished() {
                if (current === this) current = null
                fire(Event.FINISHED)
            }
        }
        current = task
        fire(Event.STARTED)
        task.queue()
    }

    fun rerun() {
        val (analyzer, targets) = lastRun ?: return
        run(analyzer, targets.filter { it.isValid })
    }

    fun cancel() {
        current?.cancel()
    }

    /** Файл изменён после проверки: номера строк в отчёте могли съехать. */
    fun isStale(file: ReportFile): Boolean {
        val stamp = stamps[file.path] ?: return false
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file.path) ?: return false
        return virtualFile.modificationStamp != stamp || FileDocumentManager.getInstance().isFileModified(virtualFile)
    }

    private fun publish(newReport: Report) {
        stamps = newReport.files.mapNotNull { file ->
            LocalFileSystem.getInstance().findFileByNioFile(file.path)?.let { file.path to it.modificationStamp }
        }.toMap()
        report = newReport
    }

    private fun buildReport(toolName: String, startedAt: Long, requested: Int, outcome: BatchOutcome): Report {
        val duration = System.currentTimeMillis() - startedAt
        return when (outcome) {
            is BatchOutcome.Done -> Report(
                toolName = toolName,
                startedAtMillis = startedAt,
                durationMillis = duration,
                requestedTargets = requested,
                files = outcome.problems
                    .filterValues { it.isNotEmpty() }
                    .map { (path, problems) -> ReportFile(path, relativePath(path), ReportView.sortProblems(problems)) },
                errors = outcome.errors,
                details = outcome.details,
            )
            is BatchOutcome.Failed -> Report(toolName, startedAt, duration, requested, emptyList(), listOf(outcome.message), outcome.details)
        }
    }

    private fun relativePath(path: Path): String {
        val base = project.basePath?.let { Path.of(it) } ?: return path.toString()
        return if (path.startsWith(base)) base.relativize(path).joinToString("/") else path.toString()
    }

    private fun showToolWindow(analyzer: ToolBatchAnalyzer) {
        ToolWindowManager.getInstance(project).getToolWindow(analyzer.spec.displayName)?.activate(null, false)
    }

    private fun fire(event: Event) {
        ApplicationManager.getApplication().invokeLater({ listeners.forEach { it(event) } }, project.disposed)
    }

    /** Фоновая задача, которую можно отменить и до того, как платформа выдала ей индикатор. */
    private abstract class RunTask(project: Project, title: String) : Task.Backgroundable(project, title, true) {
        @Volatile
        private var progress: ProgressIndicator? = null

        @Volatile
        var isCancelled = false
            private set

        fun attach(indicator: ProgressIndicator) {
            progress = indicator
            if (isCancelled) indicator.cancel()
        }

        fun cancel() {
            isCancelled = true
            progress?.cancel() // отмена индикатора убивает процесс инструмента (PreparedTool)
        }
    }

    override fun dispose() {
        cancel()
    }

    companion object {
        fun getInstance(project: Project): ToolReportService = project.service()

        /** Пакетный запуск дольше одного файла: ×10 от таймаута, но не меньше 5 минут. */
        fun batchTimeoutMs(state: ToolState): Int = maxOf(state.timeoutSeconds * 10, 300) * 1000
    }
}
