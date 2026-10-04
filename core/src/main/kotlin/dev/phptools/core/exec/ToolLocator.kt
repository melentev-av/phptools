package dev.phptools.core.exec

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.PhpToolsBundle
import dev.phptools.core.ToolSpec
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.core.settings.RunMode
import dev.phptools.core.settings.ToolState
import java.nio.file.Path

object ToolLocator {

    sealed interface Lookup {
        data class Ready(val tool: PreparedTool) : Lookup
        /** Настройка есть, но запустить нельзя: об этом нужно сказать пользователю. */
        data class Misconfigured(val title: String, val details: String) : Lookup
        /** Инструмент в проекте не установлен — молчим. */
        data object NotInstalled : Lookup
    }

    /**
     * Найти инструмент для [contextFile] (или для корня проекта). При ошибке настройки показывает
     * уведомление (с троттлингом) и возвращает `null`; если инструмент не установлен — `null` молча.
     */
    fun prepare(project: Project, spec: ToolSpec, state: ToolState, contextFile: VirtualFile? = null): PreparedTool? =
        when (val lookup = locate(project, spec, state, contextFile)) {
            is Lookup.Ready -> lookup.tool
            is Lookup.Misconfigured -> {
                ToolNotifier.notifyFailure(project, spec, lookup.title, lookup.details)
                null
            }
            Lookup.NotInstalled -> null
        }

    /** То же, что [prepare], но без уведомлений: результат разбирает вызывающий (например, кнопка «Проверить»). */
    fun locate(project: Project, spec: ToolSpec, state: ToolState, contextFile: VirtualFile? = null): Lookup {
        val basePath = project.basePath ?: return Lookup.NotInstalled
        val projectBase = Path.of(basePath)
        val php = state.phpInterpreter.orEmpty().trim()

        val mode = state.effectiveRunMode()
        val docker = if (mode == RunMode.DOCKER) {
            val service = state.composeService.orEmpty().trim()
            if (service.isEmpty()) {
                return Lookup.Misconfigured(
                    PhpToolsBundle.message("notification.start.failed.title", spec.displayName),
                    PhpToolsBundle.message("settings.check.no.service"),
                )
            }
            val compose = ParametersListUtil.parse(state.composeCommand.orEmpty())
                .ifEmpty { ParametersListUtil.parse(ToolState.DEFAULT_COMPOSE_COMMAND) }
            val containerPath = state.containerProjectPath.orEmpty().trim()
                .ifEmpty { ToolState.DEFAULT_CONTAINER_PROJECT_PATH }
            DockerTarget(compose, service, containerPath)
        } else {
            null
        }
        val wsl = if (mode == RunMode.WSL) {
            if (!SystemInfo.isWindows) {
                return Lookup.Misconfigured(
                    PhpToolsBundle.message("notification.start.failed.title", spec.displayName),
                    PhpToolsBundle.message("settings.wsl.windows.only"),
                )
            }
            val distribution = state.wslDistribution.orEmpty().trim().ifEmpty { null } ?: WslPaths.distributionOf(basePath)
            WslTarget(
                distribution = distribution,
                loginShell = state.wslLoginShell,
                uncRoot = distribution?.let { WslPaths.uncRoot(basePath, it) },
                windowsWorkDir = Path.of(System.getProperty("user.home")),
            )
        } else {
            null
        }
        val remote = docker != null || wsl != null

        val contextDir = contextFile?.parent?.takeIf { it.isInLocalFileSystem }?.let { runCatching { it.toNioPath() }.getOrNull() }
        val resolution = BinaryResolver.resolve(
            projectBase = projectBase,
            configured = state.executable.orEmpty().trim(),
            binaryName = spec.binaryName,
            contextDir = contextDir,
            useDocker = remote,
            preferBat = SystemInfo.isWindows && !remote && php.isEmpty(),
        )
        return when (resolution) {
            is Resolution.ConfiguredMissing -> Lookup.Misconfigured(
                PhpToolsBundle.message("notification.binary.not.found.title", spec.displayName),
                PhpToolsBundle.message("notification.binary.not.found.details", resolution.path.toString()),
            )
            Resolution.NotInstalled -> Lookup.NotInstalled
            is Resolution.Local, is Resolution.InContainer -> {
                val plan = LaunchPlan.create(projectBase, resolution, php, docker, wsl)
                Lookup.Ready(PreparedTool(spec, plan, state.timeoutSeconds.coerceAtLeast(1) * 1000))
            }
        }
    }
}
