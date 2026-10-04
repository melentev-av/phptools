package dev.phptools.phpcsfixer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.phptools.core.exec.CommandChunks
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.core.panel.ChangedFiles
import dev.phptools.core.panel.ToolReportService
import dev.phptools.phpcsfixer.CsFixerBundle.message
import java.nio.file.Path

/**
 * Реальный `php-cs-fixer fix` для файлов и папок — только по явному действию пользователя.
 * После запуска IDE перечитывает файлы с диска.
 */
object CsFixerFixRunner {

    fun fix(project: Project, files: List<VirtualFile>) {
        if (files.isEmpty()) return
        // Сохранение документов требует write-intent lock; invokeLater выполняет код под ним.
        ApplicationManager.getApplication().invokeLater({ start(project, files) }, project.disposed)
    }

    fun fixPaths(project: Project, paths: List<Path>) =
        fix(project, paths.mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) })

    private fun start(project: Project, files: List<VirtualFile>) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val spec = PhpCsFixerTool.SPEC
        object : Task.Backgroundable(project, message("fix.progress"), true) {
            private var fixed = -1
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val state = CsFixerSettings.getInstance(project).state
                val lookup = ToolLocator.locate(project, spec, state, files.firstOrNull { !it.isDirectory } ?: files.first())
                val tool = when (lookup) {
                    is ToolLocator.Lookup.Ready -> lookup.tool.withTimeout(ToolReportService.batchTimeoutMs(state))
                    is ToolLocator.Lookup.Misconfigured -> {
                        failure = "${lookup.title}\n${lookup.details}"
                        return
                    }
                    ToolLocator.Lookup.NotInstalled -> {
                        failure = dev.phptools.core.PhpToolsBundle.message("notification.not.installed", spec.binaryName)
                        return
                    }
                }
                val config = CsFixerRun.configTarget(project, tool, state)
                val extra = CsFixerRun.extraArgs(state)
                val targets = files.map { tool.toTarget(it.toNioPath()) }
                val limit = if (SystemInfo.isWindows) CommandChunks.WINDOWS_LIMIT else CommandChunks.UNIX_LIMIT
                val fixedLength = tool.commandLength(CsFixerCommand.fixArgs(emptyList(), config, state.allowRisky, extra))
                var count = 0
                for (chunk in CommandChunks.split(fixedLength, targets, limit)) {
                    if (indicator.isCanceled) break
                    val output = tool.run(CsFixerCommand.fixArgs(chunk, config, state.allowRisky, extra))
                    if (output.cancelled) break
                    when (val result = CsFixerOutput.parse(output.exitCode, output.stdout, output.stderr)) {
                        is CsFixerResult.Ok -> count += result.files.size
                        CsFixerResult.SyntaxError -> Unit
                        is CsFixerResult.Failed -> {
                            failure = (if (output.timedOut) message("timeout.title", ToolReportService.batchTimeoutMs(state) / 1000) + "\n" else "") +
                                ToolNotifier.excerpt(result.details)
                            break
                        }
                    }
                }
                fixed = count
                // Перечитать исправленные файлы с диска (папки — рекурсивно).
                VfsUtil.markDirtyAndRefresh(false, true, true, *files.toTypedArray())
            }

            override fun onSuccess() {
                failure?.let {
                    ToolNotifier.notifyFailure(project, spec, message("fix.failed"), it)
                    return
                }
                if (fixed < 0) return
                NotificationGroupManager.getInstance().getNotificationGroup(spec.id)
                    ?.createNotification(message("fix.done", fixed), NotificationType.INFORMATION)
                    ?.notify(project)
                // Если открыт отчёт панели — обновить его.
                ToolReportService.getInstance(project).takeIf { it.report != null && it.canRerun }?.rerun()
            }
        }.queue()
    }
}

/** «Fix with PHP-CS-Fixer»: меню Code, контекстное меню редактора и дерева Project. */
class FixWithCsFixerAction : DumbAwareAction() {
    init {
        templatePresentation.setText { message("action.fix") }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && targets(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CsFixerFixRunner.fix(project, targets(e))
    }

    private fun targets(e: AnActionEvent): List<VirtualFile> {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return selected.filter { it.isInLocalFileSystem && (it.isDirectory || ChangedFiles.isCandidate(it.path)) }
    }
}
