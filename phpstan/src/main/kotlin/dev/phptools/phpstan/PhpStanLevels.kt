package dev.phptools.phpstan

import com.intellij.openapi.project.Project
import dev.phptools.core.exec.PreparedTool
import java.nio.file.Path

/** Уровень анализа: из конфигурации проекта, а из настроек — только если в конфиге его нет. */
object PhpStanLevels {

    /** Путь к конфигу на стороне инструмента или `null` (PHPStan ищет сам). */
    fun configTarget(project: Project, tool: PreparedTool, state: PhpStanState): String? {
        val configured = state.configPath.orEmpty().trim().ifEmpty { return null }
        val path = Path.of(configured)
        if (path.isAbsolute) return tool.toTarget(path)
        val base = project.basePath ?: return configured
        return tool.toTarget(Path.of(base).resolve(path))
    }

    /** `phpstan dump-parameters` — блокирующий вызов, только в фоне. */
    fun detect(tool: PreparedTool, config: String?): ConfigLevel {
        val output = tool.run(PhpStanCommand.dumpParametersArgs(config))
        if (output.cancelled || output.timedOut || output.startFailed) return ConfigLevel.Unknown(output.stderr)
        return PhpStanConfigLevel.parse(output.exitCode, output.stdout, output.stderr)
    }

    /**
     * Значение для `--level`: уровень из настроек, если он выбран и в конфиге уровня нет.
     * Конфиг опрашивается только когда уровень выбран, результат кэшируется до Apply настроек.
     */
    fun levelArgument(project: Project, tool: PreparedTool, state: PhpStanState, config: String?): String? {
        val chosen = state.level.orEmpty().trim().ifEmpty { return null }
        return if (configLevel(project, tool, config) is ConfigLevel.Set) null else chosen
    }

    /** Уровень из конфига с кэшем до Apply настроек. */
    fun configLevel(project: Project, tool: PreparedTool, config: String?): ConfigLevel =
        PhpStanRuntime.configLevel(project) ?: detect(tool, config).also { PhpStanRuntime.rememberConfigLevel(project, it) }

    /** Уровень, с которым фактически идёт анализ, — для шапки отчёта. */
    fun effectiveLevel(project: Project, tool: PreparedTool, state: PhpStanState, config: String?): String? =
        when (val level = configLevel(project, tool, config)) {
            is ConfigLevel.Set -> level.level
            else -> state.level.orEmpty().trim().ifEmpty { null }
        }
}
