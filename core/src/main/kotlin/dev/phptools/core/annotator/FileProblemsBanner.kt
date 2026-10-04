package dev.phptools.core.annotator

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * Последние ошибки уровня файла (без строки) по каждому инструменту. Заполняется аннотатором,
 * показывается полосой над редактором ([FileProblemsBanner]).
 */
object FileProblems {
    private val messages = ConcurrentHashMap<String, List<String>>()
    private val dismissed = ConcurrentHashMap.newKeySet<String>()

    fun get(spec: ToolSpec, file: VirtualFile): List<String> =
        if (key(spec, file) in dismissed) emptyList() else messages[key(spec, file)].orEmpty()

    /** @return `true`, если набор сообщений изменился и полосу нужно перерисовать. */
    fun set(spec: ToolSpec, file: VirtualFile, fileMessages: List<String>): Boolean {
        val key = key(spec, file)
        val changed = if (fileMessages.isEmpty()) messages.remove(key) != null else messages.put(key, fileMessages) != fileMessages
        // «Скрыть» действует до следующего прогона.
        return dismissed.remove(key) || changed
    }

    fun dismiss(spec: ToolSpec, file: VirtualFile) {
        dismissed.add(key(spec, file))
    }

    private fun key(spec: ToolSpec, file: VirtualFile) = "${spec.id}\u0000${file.url}"
}

/**
 * Полоса над редактором с ошибками уровня файла. Каждый плагин регистрирует наследник:
 * `<editorNotificationProvider implementation="..."/>`.
 */
abstract class FileProblemsBanner(private val spec: ToolSpec) : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val fileMessages = FileProblems.get(spec, file)
        if (fileMessages.isEmpty()) return null

        return Function { editor ->
            val more = fileMessages.size - 1
            val text = "${spec.displayName}: ${fileMessages.first()}" +
                if (more > 0) " " + message("banner.more", more) else ""
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text(text)
                toolTipText = fileMessages.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
                createActionLabel(message("banner.hide")) {
                    FileProblems.dismiss(spec, file)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }
}
