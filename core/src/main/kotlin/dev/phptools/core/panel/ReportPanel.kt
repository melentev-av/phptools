package dev.phptools.core.panel

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.notify.ToolNotifier
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Вкладка «Отчёт»: шапка с итогами, ошибки запуска, фильтр и дерево «файл → ошибки». */
class ReportPanel(
    private val project: Project,
    private val analyzer: ToolBatchAnalyzer,
    parent: Disposable,
) : SimpleToolWindowPanel(false, true) {

    private sealed interface Node {
        data class FileNode(val file: ReportFile, val stale: Boolean) : Node
        data class GroupNode(val identifier: String, val docUrl: String?, val count: Int) : Node
        data class ProblemNode(val file: ReportFile, val problem: ReportProblem, val showFile: Boolean) : Node
        data class TipNode(val tip: String) : Node
        data class StatusNode(val text: String) : Node
    }

    private val service = ToolReportService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val tree = Tree(DefaultTreeModel(root))
    private val header = JBLabel()
    private val errors = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.RED
        border = JBUI.Borders.empty(4)
    }
    private val errorsPanel = JPanel(BorderLayout()).apply {
        add(errors, BorderLayout.CENTER)
        add(ActionLink(message("notification.open.settings")) { ToolNotifier.openSettings(project, analyzer.spec) }, BorderLayout.SOUTH)
        isVisible = false
    }
    private val filter = SearchTextField(false)
    private var grouping = Grouping.BY_FILE
    private var autoscroll = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.emptyText.text = message("report.empty.none")
        installTreeListeners()
        PopupHandler.installPopupMenu(tree, contextMenu(), "PhpToolsReportPopup")

        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })

        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            add(header)
            add(errorsPanel)
            add(filter)
        }
        val content = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        setContent(content)

        val actionToolbar = ActionManager.getInstance().createActionToolbar("PhpToolsReport", toolbarActions(), false)
        actionToolbar.targetComponent = tree
        toolbar = actionToolbar.component

        service.addListener(parent) { rebuild() }
        rebuild()
    }

    fun rebuild() {
        val report = service.report
        header.text = headerText(report)
        errors.text = report?.errors?.joinToString("\n\n").orEmpty()
        errorsPanel.isVisible = report != null && report.errors.isNotEmpty()

        root.removeAllChildren()
        if (report != null) {
            val files = ReportView.filter(report, filter.text)
            when (grouping) {
                Grouping.BY_FILE -> files.forEach { file ->
                    val fileNode = DefaultMutableTreeNode(Node.FileNode(file, service.isStale(file)))
                    file.problems.forEach { fileNode.add(problemNode(file, it, showFile = false)) }
                    root.add(fileNode)
                }
                Grouping.BY_IDENTIFIER -> ReportView.groupByIdentifier(files).forEach { (identifier, refs) ->
                    val groupNode = DefaultMutableTreeNode(Node.GroupNode(identifier, refs.firstNotNullOfOrNull { it.problem.docUrl }, refs.size))
                    refs.forEach { groupNode.add(problemNode(it.file, it.problem, showFile = true)) }
                    root.add(groupNode)
                }
            }
            if (files.isEmpty() && report.errors.isEmpty()) {
                val text = if (report.problemCount == 0) message("report.empty.ok") else message("report.empty.filtered")
                root.add(DefaultMutableTreeNode(Node.StatusNode(text)))
            }
        }
        (tree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(tree)
    }

    private fun problemNode(file: ReportFile, problem: ReportProblem, showFile: Boolean): DefaultMutableTreeNode =
        DefaultMutableTreeNode(Node.ProblemNode(file, problem, showFile)).apply {
            problem.tip?.takeIf { it.isNotBlank() }?.let { add(DefaultMutableTreeNode(Node.TipNode(it.trim()))) }
        }

    private fun headerText(report: Report?): String {
        if (service.isRunning) return message("report.running", analyzer.spec.displayName)
        if (report == null) return message("report.hint")
        val scope = if (report.requestedTargets == 0) message("report.scope.project") else message("report.scope.targets", report.requestedTargets)
        val facts = buildList {
            add(message("report.summary", report.problemCount, report.files.size))
            add(scope)
            add(message("report.duration", String.format("%.1f", report.durationMillis / 1000.0)))
            addAll(report.details)
            add(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.startedAtMillis)))
        }
        return facts.joinToString("  ·  ")
    }

    // --- навигация и ссылки ---

    private fun installTreeListeners() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (e.clickCount == 1) linkAt(e)?.let { BrowserUtil.browse(it); return }
                if (e.clickCount == 2) navigate(selectedNode(), focus = true)
            }
        })
        tree.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                tree.cursor = if (linkAt(e) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) navigate(selectedNode(), focus = true)
            }
        })
        tree.addTreeSelectionListener { if (autoscroll) navigate(selectedNode(), focus = false) }
    }

    /** Ссылка на документацию под курсором: фрагмент рендерера с тегом-URL. */
    private fun linkAt(e: MouseEvent): String? {
        val path = tree.getPathForLocation(e.x, e.y) ?: return null
        val bounds = tree.getPathBounds(path) ?: return null
        val node = path.lastPathComponent
        val row = tree.getRowForPath(path)
        val component = tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, tree.isPathSelected(path), tree.isExpanded(path), tree.model.isLeaf(node), row, false,
        ) as? ColoredTreeCellRenderer ?: return null
        return component.getFragmentTagAt(e.x - bounds.x) as? String
    }

    private fun selectedNode(): Node? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Node

    private fun selectedProblem(): Node.ProblemNode? {
        val treeNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val node = treeNode.userObject) {
            is Node.ProblemNode -> node
            is Node.TipNode -> (treeNode.parent as? DefaultMutableTreeNode)?.userObject as? Node.ProblemNode
            else -> null
        }
    }

    private fun navigate(node: Node?, focus: Boolean) {
        val (file, line) = when (node) {
            is Node.FileNode -> node.file to null
            is Node.ProblemNode -> node.file to node.problem.line
            is Node.TipNode -> selectedProblem()?.let { it.file to it.problem.line } ?: return
            else -> return
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file.path) ?: return
        OpenFileDescriptor(project, virtualFile, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(focus)
    }

    // --- действия ---

    private fun toolbarActions() = DefaultActionGroup(
        action(message("report.action.rerun"), AllIcons.Actions.Rerun, { service.canRerun }) { service.rerun() },
        action(message("report.action.stop"), AllIcons.Actions.Suspend, { service.isRunning }) { service.cancel() },
        Separator.getInstance(),
        toggle(message("report.action.group"), AllIcons.Actions.GroupBy, { grouping == Grouping.BY_IDENTIFIER }) {
            grouping = if (it) Grouping.BY_IDENTIFIER else Grouping.BY_FILE
            rebuild()
        },
        toggle(message("report.action.autoscroll"), AllIcons.General.AutoscrollToSource, { autoscroll }) { autoscroll = it },
        action(message("report.action.expand"), AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) },
        action(message("report.action.collapse"), AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) },
        Separator.getInstance(),
        action(message("report.action.copy.md"), AllIcons.Actions.Copy, { service.report != null }) { copyMarkdown() },
        action(message("report.action.save.md"), AllIcons.Actions.Download, { service.report != null }) { saveMarkdown() },
        Separator.getInstance(),
        action(message("notification.open.settings"), AllIcons.General.Settings) { ToolNotifier.openSettings(project, analyzer.spec) },
    )

    private fun contextMenu() = DefaultActionGroup(
        action(message("report.action.jump"), AllIcons.Actions.EditSource, { selectedNode().let { it != null && it !is Node.StatusNode && it !is Node.GroupNode } }) {
            navigate(selectedNode(), focus = true)
        },
        action(message("report.action.ignore"), null, { canIgnore() }) { ignoreSelected() },
        Separator.getInstance(),
        action(message("report.action.copy.message"), AllIcons.Actions.Copy, { selectedProblem() != null }) {
            selectedProblem()?.let { copy(it.problem.message) }
        },
        action(message("report.action.copy.identifier"), null, { selectedProblem()?.problem?.identifier != null }) {
            selectedProblem()?.problem?.identifier?.let(::copy)
        },
        action(message("report.action.docs"), AllIcons.Actions.Help, { selectedDocUrl() != null }) {
            selectedDocUrl()?.let(BrowserUtil::browse)
        },
    )

    private fun selectedDocUrl(): String? = when (val node = selectedNode()) {
        is Node.GroupNode -> node.docUrl
        else -> selectedProblem()?.problem?.docUrl
    }

    private fun canIgnore(): Boolean {
        val problem = selectedProblem()?.problem ?: return false
        return problem.ignorable && problem.line != null && analyzer.ignoreFix(problem) != null
    }

    private fun ignoreSelected() {
        val node = selectedProblem() ?: return
        val fix = analyzer.ignoreFix(node.problem) ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(node.file.path) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        WriteCommandAction.runWriteCommandAction(project, fix.text, null, { fix.invoke(project, null, psiFile) }, psiFile)
        navigate(node, focus = true)
        rebuild() // файл помечается «изменён после проверки»
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun copyMarkdown() {
        val report = service.report ?: return
        copy(ReportView.toMarkdown(report, ReportView.filter(report, filter.text)))
    }

    private fun saveMarkdown() {
        val report = service.report ?: return
        val descriptor = FileSaverDescriptor(message("report.action.save.md"), "", "md")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("${analyzer.spec.id}-report.md") ?: return
        Files.writeString(target.file.toPath(), ReportView.toMarkdown(report, ReportView.filter(report, filter.text)))
    }

    private fun action(text: String, icon: javax.swing.Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun toggle(text: String, icon: javax.swing.Icon, selected: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = selected()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
        }

    // --- отрисовка ---

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject as? Node) {
                is Node.FileNode -> {
                    val name = node.file.relativePath.substringAfterLast('/')
                    val dir = node.file.relativePath.substringBeforeLast('/', "")
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    append(name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (dir.isNotEmpty()) append("  $dir", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  " + message("report.count", node.file.problems.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (node.stale) append("  " + message("report.stale"), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.ORANGE))
                }
                is Node.GroupNode -> {
                    icon = AllIcons.Nodes.Folder
                    if (node.identifier.isEmpty()) {
                        append(message("report.no.identifier"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    } else if (node.docUrl != null) {
                        append(node.identifier, SimpleTextAttributes.LINK_BOLD_ATTRIBUTES, node.docUrl)
                    } else {
                        append(node.identifier, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    }
                    append("  " + message("report.count", node.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Node.ProblemNode -> {
                    val p = node.problem
                    icon = AllIcons.General.Error
                    if (node.showFile) append(node.file.relativePath + ":", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append((p.line?.toString() ?: message("report.whole.file")) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(p.message, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (p.identifier != null && !node.showFile) {
                        append("  ")
                        if (p.docUrl != null) {
                            append(p.identifier, SimpleTextAttributes.LINK_ATTRIBUTES, p.docUrl)
                        } else {
                            append(p.identifier, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
                is Node.TipNode -> {
                    icon = AllIcons.General.Information
                    append(node.tip, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                is Node.StatusNode -> append(node.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                null -> Unit
            }
        }
    }
}
