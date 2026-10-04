package dev.phptools.core.exec

import java.nio.file.Path

/** Готовая к запуску командная строка: что запускать и в какой локальной папке. */
data class CommandSpec(val command: List<String>, val workDir: Path)

/** Параметры Docker Compose. [composeCommand] уже разобран на аргументы (`docker compose -f x.yml`). */
data class DockerTarget(
    val composeCommand: List<String>,
    val service: String,
    val containerProjectPath: String,
)

/**
 * Сборка командных строк и маппинг путей. Чистая логика без платформы.
 *
 * Локально: `[php] <exe> args...`, cwd = [workDir].
 * Docker: `<compose> exec -T -w <containerWorkDir> <service> [php] <exe> args...`, cwd = [projectBase],
 * чтобы compose нашёл свой файл.
 */
class LaunchPlan private constructor(
    val projectBase: Path,
    val workDir: Path,
    val executable: String,
    val phpInterpreter: String?,
    val docker: DockerTarget?,
) {
    val isDocker: Boolean get() = docker != null

    /** Путь, понятный инструменту: локальный абсолютный или путь внутри контейнера. */
    fun toTarget(localPath: Path): String {
        val path = localPath.toAbsolutePath().normalize()
        val target = docker ?: return path.toString()
        return mapToContainer(projectBase, target.containerProjectPath, path)
    }

    fun command(args: List<String>): CommandSpec {
        val target = docker ?: return CommandSpec(invocation(args), workDir)
        return CommandSpec(composeExec(target) + invocation(args), projectBase)
    }

    /**
     * Docker: временный файл создаётся внутри контейнера из stdin и удаляется после запуска,
     * на хосте в проекте ничего не пишется. [tmpPath] — путь внутри контейнера.
     */
    fun tempFileCommand(tmpPath: String, args: List<String>): CommandSpec {
        val target = requireNotNull(docker) { "tempFileCommand is for Docker mode only" }
        return CommandSpec(composeExec(target) + listOf("sh", "-c", TEMP_FILE_SCRIPT, "sh", tmpPath) + invocation(args), projectBase)
    }

    private fun invocation(args: List<String>): List<String> = listOfNotNull(phpInterpreter, executable) + args

    private fun composeExec(target: DockerTarget): List<String> =
        target.composeCommand + listOf("exec", "-T", "-w", toTarget(workDir), target.service)

    companion object {
        const val TEMP_FILE_SCRIPT = "t=\"\$1\"; shift; cat > \"\$t\"; \"\$@\"; c=\$?; rm -f \"\$t\"; exit \$c"

        fun create(
            projectBase: Path,
            resolution: Resolution,
            phpInterpreter: String?,
            docker: DockerTarget?,
        ): LaunchPlan {
            val base = projectBase.toAbsolutePath().normalize()
            val php = phpInterpreter?.takeIf { it.isNotBlank() }
            return when (resolution) {
                is Resolution.Local -> {
                    val exe = resolution.executable.toAbsolutePath().normalize()
                    val exeTarget = if (docker != null) mapToContainer(base, docker.containerProjectPath, exe) else exe.toString()
                    LaunchPlan(base, resolution.workDir, exeTarget, php, docker)
                }
                is Resolution.InContainer -> {
                    requireNotNull(docker) { "InContainer resolution requires Docker mode" }
                    LaunchPlan(base, resolution.workDir, resolution.executable, php, docker)
                }
                is Resolution.ConfiguredMissing, Resolution.NotInstalled ->
                    throw IllegalArgumentException("Cannot launch unresolved tool: $resolution")
            }
        }

        /** Заменяет префикс [projectBase] на [containerProjectPath], разделители — `/`. Путь вне проекта — как есть. */
        fun mapToContainer(projectBase: Path, containerProjectPath: String, localPath: Path): String {
            val path = localPath.toAbsolutePath().normalize()
            val base = projectBase.toAbsolutePath().normalize()
            if (!path.startsWith(base)) return path.toString()
            val root = containerProjectPath.trimEnd('/', '\\').replace('\\', '/')
            val relative = base.relativize(path).joinToString("/") { it.toString() }
            return if (relative.isEmpty()) root.ifEmpty { "/" } else "$root/$relative"
        }
    }
}
