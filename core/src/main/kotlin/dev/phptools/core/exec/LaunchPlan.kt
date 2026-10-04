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
 * Запуск внутри WSL (OpenIDE на Windows, проект и PHP — в Linux).
 *
 * @param distribution имя дистрибутива; `null` — дистрибутив по умолчанию (без `-d`).
 * @param loginShell запускать через `bash -lc` (php из asdf/phpenv, доступный только после профиля).
 * @param uncRoot корень дистрибутива для обратного маппинга (`\\wsl.localhost\Ubuntu`); `null` — только `/mnt/<диск>`.
 * @param windowsWorkDir существующая папка Windows для процесса `wsl.exe`; cwd внутри WSL задаёт `--cd`.
 */
data class WslTarget(
    val distribution: String?,
    val loginShell: Boolean,
    val uncRoot: String?,
    val windowsWorkDir: Path,
)

/**
 * Сборка командных строк и маппинг путей. Чистая логика без платформы.
 *
 * Локально: `[php] <exe> args...`, cwd = [workDir].
 * Docker: `<compose> exec -T -w <containerWorkDir> <service> [php] <exe> args...`, cwd = [projectBase],
 * чтобы compose нашёл свой файл.
 * WSL: `wsl.exe [-d <distro>] --cd <linuxWorkDir> --exec [php] <exe> args...` (или `--exec bash -lc '<команда>'`).
 */
class LaunchPlan private constructor(
    val projectBase: Path,
    val workDir: Path,
    val executable: String,
    val phpInterpreter: String?,
    val docker: DockerTarget?,
    val wsl: WslTarget? = null,
) {
    val isDocker: Boolean get() = docker != null

    val isWsl: Boolean get() = wsl != null

    /** Временные файлы создаются не на хосте, а там, где выполняется инструмент (контейнер или WSL). */
    val usesRemoteTempFile: Boolean get() = docker != null || wsl != null

    /** Где выполняется инструмент — для шапки отчёта. `null` — локально. */
    val runtimeLabel: String? get() = when {
        docker != null -> "Docker"
        wsl != null -> "WSL" + (wsl.distribution?.let { " ($it)" } ?: "")
        else -> null
    }

    /** Путь, понятный инструменту: локальный абсолютный или путь внутри контейнера. */
    fun toTarget(localPath: Path): String {
        val path = localPath.toAbsolutePath().normalize()
        wsl?.let { return WslPaths.toLinux(path.toString(), it.distribution) ?: path.toString().replace('\\', '/') }
        val target = docker ?: return path.toString()
        return mapToContainer(projectBase, target.containerProjectPath, path)
    }

    /**
     * Обратный маппинг пути из вывода инструмента в локальный: путь контейнера внутри [DockerTarget.containerProjectPath]
     * → путь в проекте. Остальные пути (и всё в локальном режиме) — как есть.
     */
    fun fromTarget(toolPath: String): Path {
        wsl?.let { return Path.of(WslPaths.toWindows(toolPath, it.uncRoot) ?: toolPath) }
        val target = docker ?: return Path.of(toolPath)
        val root = target.containerProjectPath.trimEnd('/', '\\').replace('\\', '/')
        val normalized = toolPath.replace('\\', '/')
        return when {
            normalized == root -> projectBase
            normalized.startsWith("$root/") -> projectBase.resolve(normalized.removePrefix("$root/")).normalize()
            else -> Path.of(toolPath)
        }
    }

    fun command(args: List<String>): CommandSpec {
        wsl?.let { return wslCommand(it, invocation(args)) }
        val target = docker ?: return CommandSpec(invocation(args), workDir)
        return CommandSpec(composeExec(target) + invocation(args), projectBase)
    }

    /**
     * Docker и WSL: временный файл создаётся там, где выполняется инструмент, из stdin и удаляется после запуска;
     * на хосте в проекте ничего не пишется. [tmpPath] — путь внутри контейнера/WSL.
     */
    fun tempFileCommand(tmpPath: String, args: List<String>): CommandSpec {
        val wrapped = listOf("sh", "-c", TEMP_FILE_SCRIPT, "sh", tmpPath) + invocation(args)
        wsl?.let { return wslCommand(it, wrapped) }
        val target = requireNotNull(docker) { "tempFileCommand is for Docker or WSL mode only" }
        return CommandSpec(composeExec(target) + wrapped, projectBase)
    }

    private fun invocation(args: List<String>): List<String> = listOfNotNull(phpInterpreter, executable) + args

    /** `--exec` — без промежуточной оболочки; `bash -lc` — одной строкой с POSIX-экранированием. */
    private fun wslCommand(target: WslTarget, command: List<String>): CommandSpec {
        val prefix = buildList {
            add("wsl.exe")
            target.distribution?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-d", it)) }
            addAll(listOf("--cd", toTarget(workDir), "--exec"))
        }
        val body = if (target.loginShell) listOf("bash", "-lc", ShellQuote.join(command)) else command
        return CommandSpec(prefix + body, target.windowsWorkDir)
    }

    private fun composeExec(target: DockerTarget): List<String> =
        target.composeCommand + listOf("exec", "-T", "-w", toTarget(workDir), target.service)

    companion object {
        const val TEMP_FILE_SCRIPT = "t=\"\$1\"; shift; cat > \"\$t\"; \"\$@\"; c=\$?; rm -f \"\$t\"; exit \$c"

        fun create(
            projectBase: Path,
            resolution: Resolution,
            phpInterpreter: String?,
            docker: DockerTarget?,
            wsl: WslTarget? = null,
        ): LaunchPlan {
            require(docker == null || wsl == null) { "Docker and WSL modes are mutually exclusive" }
            val base = projectBase.toAbsolutePath().normalize()
            val php = phpInterpreter?.takeIf { it.isNotBlank() }
            return when (resolution) {
                is Resolution.Local -> {
                    val exe = resolution.executable.toAbsolutePath().normalize()
                    val exeTarget = when {
                        docker != null -> mapToContainer(base, docker.containerProjectPath, exe)
                        wsl != null -> WslPaths.toLinux(exe.toString(), wsl.distribution) ?: exe.toString().replace('\\', '/')
                        else -> exe.toString()
                    }
                    LaunchPlan(base, resolution.workDir, exeTarget, php, docker, wsl)
                }
                is Resolution.InContainer -> {
                    require(docker != null || wsl != null) { "InContainer resolution requires Docker or WSL mode" }
                    LaunchPlan(base, resolution.workDir, resolution.executable, php, docker, wsl)
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
