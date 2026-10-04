package dev.phptools.core.startup

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.WslPaths
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.core.settings.RunMode
import dev.phptools.core.settings.ToolState

/**
 * Windows: проект открыт по WSL-пути (`\\wsl$\<distro>\…`), а инструмент запускается локально —
 * предложить переключиться на WSL. Сам режим не включается без согласия пользователя.
 *
 * Каждый плагин регистрирует свой наследник: `<postStartupActivity implementation="..."/>`.
 */
abstract class WslSuggestion(private val spec: ToolSpec) : ProjectActivity {

    protected abstract fun state(project: Project): ToolState

    override suspend fun execute(project: Project) {
        if (!shouldSuggest(SystemInfo.isWindows, project.basePath, state(project).effectiveRunMode(), dontAsk(project))) return
        val distribution = WslPaths.distributionOf(project.basePath.orEmpty()) ?: return

        val group = NotificationGroupManager.getInstance().getNotificationGroup(spec.id) ?: return
        group.createNotification(
            message("wsl.suggest.title"),
            message("wsl.suggest.content", spec.displayName, distribution),
            NotificationType.INFORMATION,
        )
            .addAction(NotificationAction.createSimpleExpiring(message("wsl.suggest.enable")) {
                val state = state(project)
                state.applyRunMode(RunMode.WSL)
                state.wslDistribution = distribution
                ToolNotifier.reset(project, spec)
                DaemonCodeAnalyzer.getInstance(project).restart("PHP tools: switched to WSL")
            })
            .addAction(NotificationAction.createSimpleExpiring(message("builtin.dont.ask")) {
                PropertiesComponent.getInstance(project).setValue(dontAskKey(), true)
            })
            .notify(project)
    }

    private fun dontAsk(project: Project) = PropertiesComponent.getInstance(project).isTrueValue(dontAskKey())

    private fun dontAskKey() = "dev.phptools.${spec.id}.wslSuggestion.dontAsk"

    companion object {
        /** Чистая логика: предлагать ли переключение. */
        fun shouldSuggest(isWindows: Boolean, projectPath: String?, mode: RunMode, dontAsk: Boolean): Boolean =
            isWindows && !dontAsk && mode == RunMode.LOCAL && projectPath != null && WslPaths.distributionOf(projectPath) != null
    }
}
