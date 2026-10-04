package dev.phptools.core.startup

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ex.modifyAndCommitProjectProfile
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec

/**
 * При открытии проекта проверяет, не включён ли тот же инструмент во встроенных анализаторах
 * PHP-плагина OpenIDE, и предлагает выключить встроенную инспекцию, чтобы ошибки не подсвечивались дважды.
 *
 * Работает только через профиль инспекций платформы, по строковому shortName: классы PHP-плагина не используются.
 * Если PHP-плагина нет или его инспекция больше не регистрируется (например, ушла в платную версию) — молчит.
 *
 * Каждый плагин регистрирует свой наследник: `<postStartupActivity implementation="..."/>`.
 */
abstract class BuiltInAnalyzerCheck(private val spec: ToolSpec) : ProjectActivity {

    override suspend fun execute(project: Project) {
        val shortName = spec.builtInInspectionShortName ?: return
        val properties = PropertiesComponent.getInstance(project)
        if (properties.isTrueValue(dontAskKey())) return
        if (!readAction { isBuiltInEnabled(project, shortName) }) return

        val group = NotificationGroupManager.getInstance().getNotificationGroup(spec.id) ?: return
        group.createNotification(
            message("builtin.title", spec.displayName),
            message("builtin.content", spec.displayName),
            NotificationType.INFORMATION,
        )
            .addAction(NotificationAction.createSimpleExpiring(message("builtin.disable")) {
                modifyAndCommitProjectProfile(project) { it.setToolEnabled(shortName, false, project) }
            })
            .addAction(NotificationAction.createSimpleExpiring(message("builtin.keep")) {})
            .addAction(NotificationAction.createSimpleExpiring(message("builtin.dont.ask")) {
                properties.setValue(dontAskKey(), true)
            })
            .notify(project)
    }

    private fun isBuiltInEnabled(project: Project, shortName: String): Boolean {
        if (project.isDisposed) return false
        val key = HighlightDisplayKey.find(shortName) ?: return false
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        // Убеждаемся, что инспекция именно из PHP-плагина, а не однофамилец из другого плагина.
        val owner = profile.getInspectionTool(shortName, project)?.extension?.pluginDescriptor?.pluginId?.idString
        return owner == OPENPHP_PLUGIN_ID && profile.isToolEnabled(key)
    }

    private fun dontAskKey() = "dev.phptools.${spec.id}.builtInCheck.dontAsk"

    companion object {
        const val OPENPHP_PLUGIN_ID = "ru.openide.openphp"
    }
}
