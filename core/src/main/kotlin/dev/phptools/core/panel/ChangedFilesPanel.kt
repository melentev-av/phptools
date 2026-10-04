package dev.phptools.core.panel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.phptools.core.PhpToolsBundle.message
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Вкладка «Изменённые файлы»: PHP-файлы из VCS (как во вкладке Commit, включая неотслеживаемые)
 * с галочками и кнопками запуска.
 */
class ChangedFilesPanel(
    private val project: Project,
    private val analyzer: ToolBatchAnalyzer,
    parent: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val list = CheckBoxList<VirtualFile>()
    /** Пути, с которых пользователь снял галочку: переживают обновление списка. */
    private val unchecked = mutableSetOf<String>()
    private val service = ToolReportService.getInstance(project)

    private val checkSelected = JButton(message("panel.check.selected")).apply { addActionListener { run(selectedFiles()) } }
    private val checkAll = JButton(message("panel.check.all")).apply { addActionListener { run(allFiles()) } }
    private val checkCurrent = JButton(message("panel.check.current")).apply { addActionListener { checkCurrentFile() } }
    private val checkProject = JButton(message("panel.check.project")).apply { addActionListener { checkWholeProject() } }
    private val refresh = JButton(message("panel.refresh")).apply { addActionListener { refresh() } }

    init {
        list.emptyText.text = message("panel.no.changes")
        list.setCheckBoxListListener { index, value ->
            list.getItemAt(index)?.let { if (value) unchecked -= it.path else unchecked += it.path }
            updateButtons()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(checkSelected)
            add(checkAll)
            add(checkCurrent)
            add(checkProject)
            add(refresh)
        }
        toolbar = buttons
        setContent(JBScrollPane(list))

        project.messageBus.connect(parent).subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() {
                ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
            }
        })
        service.addListener(parent) { updateButtons() }
        refresh()
    }

    fun refresh() {
        val files = collectChangedFiles()
        val base = project.basePath?.let { Path.of(it) }
        list.setItems(files) { file -> relative(base, file) }
        files.forEach { list.setItemSelected(it, it.path !in unchecked) }
        updateButtons()
    }

    private fun collectChangedFiles(): List<VirtualFile> {
        val changes = ChangeListManager.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        val candidates = changes.allChanges.mapNotNull { it.virtualFile } + changes.unversionedFilesPaths.mapNotNull { it.virtualFile }
        return candidates
            .asSequence()
            .filter { it.isValid && !it.isDirectory && it.isInLocalFileSystem && ChangedFiles.isCandidate(it.path) }
            .filter { index.isInContent(it) && !index.isExcluded(it) && !index.isInLibrary(it) }
            .distinctBy { it.path }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    private fun allFiles(): List<VirtualFile> = (0 until list.itemsCount).mapNotNull { list.getItemAt(it) }

    private fun selectedFiles(): List<VirtualFile> = allFiles().filter { list.isItemSelected(it) }

    private fun run(files: List<VirtualFile>) {
        if (files.isNotEmpty()) service.run(analyzer, files)
    }

    private fun checkCurrentFile() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { ChangedFiles.isCandidate(it.path) }
        if (file == null) {
            Messages.showInfoMessage(project, message("panel.no.current"), analyzer.spec.displayName)
            return
        }
        service.run(analyzer, listOf(file))
    }

    private fun checkWholeProject() {
        val answer = Messages.showOkCancelDialog(
            project,
            message("panel.project.confirm", analyzer.spec.displayName),
            analyzer.spec.displayName,
            message("panel.check.project"),
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.OK) service.run(analyzer, emptyList())
    }

    private fun updateButtons() {
        val idle = !service.isRunning
        checkSelected.isEnabled = idle && selectedFiles().isNotEmpty()
        checkAll.isEnabled = idle && list.itemsCount > 0
        checkCurrent.isEnabled = idle
        checkProject.isEnabled = idle
    }

    private fun relative(base: Path?, file: VirtualFile): String {
        val path = file.toNioPath()
        return if (base != null && path.startsWith(base)) base.relativize(path).joinToString("/") else file.path
    }
}

/** Отбор файлов для проверки. Чистая логика. */
object ChangedFiles {
    fun isCandidate(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.endsWith(".php", ignoreCase = true) && "/vendor/" !in normalized
    }
}
