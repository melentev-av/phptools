package dev.phptools.core.panel

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.phptools.core.PhpToolsBundle.message

/**
 * Боковая панель инструмента: вкладки «Изменённые файлы» и «Отчёт».
 * Регистрация в плагине: `<toolWindow id="<spec.displayName>" anchor="left" factoryClass="..."/>`.
 */
abstract class ToolPanelFactory(private val analyzer: ToolBatchAnalyzer) : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val parent = toolWindow.disposable
        val contents = ContentFactory.getInstance()
        val changes = contents.createContent(ChangedFilesPanel(project, analyzer, parent), message("panel.tab.changes"), false)
        val report = contents.createContent(ReportPanel(project, analyzer, parent), message("panel.tab.report"), false)
        toolWindow.contentManager.addContent(changes)
        toolWindow.contentManager.addContent(report)

        // Любой запуск (из панели, меню проекта или полосы над редактором) показывает отчёт.
        ToolReportService.getInstance(project).addListener(parent) { event ->
            if (event == ToolReportService.Event.STARTED) toolWindow.contentManager.setSelectedContent(report)
        }
    }

    companion object {
        /** Открыть панель инструмента на вкладке «Отчёт». */
        fun showReport(project: Project, toolWindowId: String) {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(toolWindowId) ?: return
            toolWindow.activate {
                toolWindow.contentManager.contents.getOrNull(1)?.let { toolWindow.contentManager.setSelectedContent(it) }
            }
        }
    }
}

/**
 * «Проверить <инструмент>» в контекстном меню дерева Project: выбранные PHP-файлы и папки.
 * Регистрация в плагине: `<action class="..."><add-to-group group-id="ProjectViewPopupMenu" anchor="last"/></action>`.
 */
abstract class CheckWithToolAction(private val analyzer: ToolBatchAnalyzer) : DumbAwareAction() {

    init {
        templatePresentation.setText { message("action.check.with", analyzer.spec.displayName) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && targets(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = targets(e).ifEmpty { return }
        ToolReportService.getInstance(project).run(analyzer, files)
    }

    private fun targets(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().filter {
            it.isInLocalFileSystem && (it.isDirectory || ChangedFiles.isCandidate(it.path))
        }
}
