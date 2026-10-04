package dev.phptools.core.docker

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.core.settings.RunMode
import dev.phptools.core.settings.ToolState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Определение Docker Compose через Docker CLI (в фоне) с запасным разбором YAML. */
object DockerDetector {
    private const val TIMEOUT_MS = 20_000
    private val LOG = logger<DockerDetector>()

    data class Result(
        val guesses: List<DockerGuess>,
        val composeCommand: String,
        /** Конфигурация получена через `docker compose config` (иначе — запасной разбор YAML). */
        val viaCli: Boolean,
    ) {
        val best: DockerGuess? get() = guesses.firstOrNull()
        val confident: Boolean get() = ComposeDetection.isConfident(guesses)
    }

    fun hasComposeFile(projectPath: Path): Boolean = ComposeDetection.STANDARD_FILES.any { Files.isRegularFile(projectPath.resolve(it)) }

    /** Блокирующий вызов — только в фоне. `null` — compose в проекте не найден. */
    fun detect(projectPath: String): Result? {
        val base = Path.of(projectPath)
        // Запущенные сервисы и файлы, с которыми поднят compose-проект этой папки.
        val ps = run(
            listOf(
                "docker", "ps",
                "--filter", "label=com.docker.compose.project.working_dir=$projectPath",
                "--format", "{{.Label \"com.docker.compose.service\"}}|{{.Label \"com.docker.compose.project.config_files\"}}",
            ),
            base,
        )
        val (running, configFiles) = ps?.let(ComposeDetection::parseRunning) ?: (emptySet<String>() to emptyList())
        if (!hasComposeFile(base) && configFiles.isEmpty()) return null

        val composeCommand = ComposeDetection.composeCommand(configFiles, projectPath)
        val config = run(ParametersListUtil.parse(composeCommand) + listOf("config", "--format", "json"), base)
        val services = if (config != null) {
            ComposeDetection.parseConfigJson(config)
        } else {
            val file = ComposeDetection.STANDARD_FILES.map(base::resolve).firstOrNull(Files::isRegularFile) ?: return null
            ComposeDetection.parseYaml(Files.readString(file), projectPath)
        }
        return Result(ComposeDetection.guess(services, projectPath, running), composeCommand, config != null)
    }

    /** Записать найденное в настройки инструмента и включить Docker-режим. */
    fun apply(state: ToolState, guess: DockerGuess, composeCommand: String) {
        state.applyRunMode(RunMode.DOCKER)
        state.composeService = guess.service
        state.containerProjectPath = guess.containerProjectPath
        state.composeCommand = composeCommand
    }

    /** Запустится ли инструмент локально: бинарник найден и есть PHP (свой интерпретатор или `php` в PATH). */
    fun toolRunsLocally(project: Project, spec: ToolSpec, state: ToolState): Boolean {
        // Копия того же класса: copyFrom между разными наследниками ToolState небезопасен.
        val local = state.javaClass.getDeclaredConstructor().newInstance().also { it.copyFrom(state) }.apply { applyRunMode(RunMode.LOCAL) }
        if (ToolLocator.locate(project, spec, local) !is ToolLocator.Lookup.Ready) return false
        return state.phpInterpreter.orEmpty().isNotBlank() || PathEnvironmentVariableUtil.findInPath("php") != null
    }

    private fun run(command: List<String>, cwd: Path): String? {
        val commandLine = GeneralCommandLine(command)
            .withWorkingDirectory(cwd)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return try {
            val output = CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS, true)
            if (output.exitCode == 0 && !output.isTimeout) output.stdout else null.also { LOG.debug("${command.take(3)}: exit ${output.exitCode} ${output.stderr.take(300)}") }
        } catch (e: ExecutionException) {
            LOG.debug(e) // docker не установлен — запасной разбор YAML
            null
        }
    }
}

/**
 * При открытии проекта: инструмент запускается локально, но локально он не заработает (нет бинарника или PHP),
 * а в проекте есть Docker Compose — предложить Docker-режим. Без согласия пользователя ничего не включается.
 *
 * Каждый плагин регистрирует свой наследник: `<postStartupActivity implementation="..."/>`.
 */
abstract class DockerSuggestion(private val spec: ToolSpec) : ProjectActivity {

    protected abstract fun state(project: Project): ToolState

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val state = state(project)
        val properties = PropertiesComponent.getInstance(project)
        val dontAsk = properties.isTrueValue(dontAskKey())
        val hasCompose = DockerDetector.hasComposeFile(Path.of(basePath))
        if (!ComposeDetection.shouldOffer(state.effectiveRunMode() == RunMode.LOCAL, false, dontAsk, hasCompose)) return
        if (DockerDetector.toolRunsLocally(project, spec, state)) return

        val result = DockerDetector.detect(basePath) ?: return
        val best = result.best ?: return
        val text = buildString {
            append(message("docker.suggest.content", spec.displayName, best.service, best.containerProjectPath))
            if (!best.running) append(' ').append(message("docker.suggest.not.running"))
            if (!result.confident) append(' ').append(message("docker.suggest.ambiguous", result.guesses.size))
        }
        val group = NotificationGroupManager.getInstance().getNotificationGroup(spec.id) ?: return
        group.createNotification(message("docker.suggest.title"), text, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(message("docker.suggest.enable")) {
                DockerDetector.apply(state(project), best, result.composeCommand)
                ToolNotifier.reset(project, spec)
                DaemonCodeAnalyzer.getInstance(project).restart("PHP tools: switched to Docker Compose")
            })
            .addAction(NotificationAction.createSimpleExpiring(message("docker.suggest.configure")) {
                ToolNotifier.openSettings(project, spec)
            })
            .addAction(NotificationAction.createSimpleExpiring(message("builtin.dont.ask")) {
                properties.setValue(dontAskKey(), true)
            })
            .notify(project)
    }

    private fun dontAskKey() = "dev.phptools.${spec.id}.dockerSuggestion.dontAsk"
}
