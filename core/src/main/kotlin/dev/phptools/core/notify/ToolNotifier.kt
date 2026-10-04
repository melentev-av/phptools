package dev.phptools.core.notify

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.phptools.core.PhpToolsBundle
import dev.phptools.core.ToolSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Balloon-уведомления об ошибках инструмента. Одно и то же сообщение (по заголовку) для пары
 * (проект, инструмент) показывается один раз за сессию; троттлинг сбрасывается при Apply в настройках.
 *
 * Группа уведомлений с id = `spec.id` должна быть зарегистрирована в plugin.xml.
 */
object ToolNotifier {
    private const val STDERR_LINES = 20

    private val shown = ConcurrentHashMap.newKeySet<String>()

    fun notifyFailure(project: Project, spec: ToolSpec, title: String, details: String) =
        notify(project, spec, title, details, NotificationType.ERROR)

    /** Информационное уведомление с тем же троттлингом (один раз за сессию на пару проект+инструмент). */
    fun notifyInfo(project: Project, spec: ToolSpec, title: String, details: String) =
        notify(project, spec, title, details, NotificationType.INFORMATION)

    private fun notify(project: Project, spec: ToolSpec, title: String, details: String, type: NotificationType) {
        if (project.isDisposed) return
        if (!shown.add(key(project, spec, title))) return

        val group = NotificationGroupManager.getInstance().getNotificationGroup(spec.id)
        if (group == null) {
            LOG.warn("Notification group '${spec.id}' is not registered in plugin.xml")
            return
        }
        val content = StringUtil.escapeXmlEntities(details).replace("\n", "<br>")
        group.createNotification(title, content, type)
            .addAction(NotificationAction.createSimpleExpiring(PhpToolsBundle.message("notification.open.settings")) {
                openSettings(project, spec)
            })
            .notify(project)
    }

    /** Сбросить троттлинг для пары (проект, инструмент). Вызывается при Apply в настройках инструмента. */
    fun reset(project: Project, spec: ToolSpec) {
        val prefix = key(project, spec, "")
        shown.removeIf { it.startsWith(prefix) }
    }

    /** Первые ~20 строк stderr — главное, что поможет пользователю починить конфиг. */
    fun excerpt(text: String, maxLines: Int = STDERR_LINES): String {
        val lines = text.trim().lines()
        return if (lines.size <= maxLines) lines.joinToString("\n") else (lines.take(maxLines) + "…").joinToString("\n")
    }

    fun openSettings(project: Project, spec: ToolSpec) {
        // Перегрузка showSettingsDialog(project, String) ищет по отображаемому имени, поэтому ищем по id.
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { (it as? SearchableConfigurable)?.id == spec.configurableId },
            null,
        )
    }

    private fun key(project: Project, spec: ToolSpec, title: String) = "${project.locationHash}\u0000${spec.id}\u0000$title"

    private val LOG = logger<ToolNotifier>()
}
