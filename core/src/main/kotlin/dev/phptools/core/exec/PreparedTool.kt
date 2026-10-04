package dev.phptools.core.exec

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import dev.phptools.core.ToolSpec
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

data class ToolOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    /** Процесс не удалось запустить (нет docker, нет прав и т.п.); текст ошибки также в [stderr]. */
    val startFailed: Boolean = false,
)

/** Найденный и готовый к запуску инструмент. Никогда не вызывать на EDT. */
class PreparedTool(
    val spec: ToolSpec,
    val plan: LaunchPlan,
    private val timeoutMs: Int,
) {
    val isDocker: Boolean get() = plan.isDocker

    /** Путь, понятный инструменту: локальный абсолютный или путь внутри контейнера. */
    fun toTarget(localPath: Path): String = plan.toTarget(localPath)

    /** Путь из вывода инструмента → локальный (см. [LaunchPlan.fromTarget]). */
    fun fromTarget(toolPath: String): Path = plan.fromTarget(toolPath)

    /** Тот же инструмент с другим таймаутом (например, для пакетного запуска из панели). */
    fun withTimeout(timeoutMs: Int): PreparedTool = PreparedTool(spec, plan, timeoutMs)

    /** Длина неизменной части команды — для разбиения путей на пачки ([CommandChunks]). */
    fun commandLength(args: List<String>): Int = plan.command(args).command.sumOf { it.length + 1 }

    fun run(args: List<String>, stdin: String? = null): ToolOutput = execute(plan.command(args), stdin)

    /**
     * Анализ несохранённого буфера. Локально — временный файл в системной temp-папке (не в проекте),
     * в Docker — файл создаётся внутри контейнера из stdin.
     */
    fun runWithTempFile(content: String, ext: String, args: (tmpPath: String) -> List<String>): ToolOutput {
        if (plan.isDocker) {
            val tmp = "/tmp/phptools-${UUID.randomUUID()}.$ext"
            return execute(plan.tempFileCommand(tmp, args(tmp)), content)
        }
        val file = try {
            FileUtil.createTempFile("phptools-", ".$ext", true)
        } catch (e: IOException) {
            return ToolOutput(-1, "", e.message.orEmpty(), timedOut = false, cancelled = false, startFailed = true)
        }
        try {
            file.writeText(content, StandardCharsets.UTF_8)
            return run(args(file.absolutePath))
        } finally {
            FileUtil.delete(file)
        }
    }

    private fun execute(spec: CommandSpec, stdin: String?): ToolOutput {
        check(!ApplicationManager.getApplication().isDispatchThread) { "External tools must not run on EDT" }

        val commandLine = GeneralCommandLine(spec.command)
            .withWorkingDirectory(spec.workDir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        // Окружение консоли, но без переменных AI-агентов (см. AgentEnvironment).
        val environment = AgentEnvironment.strip(commandLine.parentEnvironment)
        commandLine
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
        LOG.debug { "Running: ${commandLine.commandLineString} in ${spec.workDir}" }

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            return ToolOutput(-1, "", e.message.orEmpty(), timedOut = false, cancelled = false, startFailed = true)
        }

        // stdin пишем из пул-потока и закрываем, иначе возможен дедлок на больших файлах:
        // процесс ждёт, пока прочитают его stdout, а мы — пока он дочитает stdin.
        val input = handler.processInput
        if (stdin == null) {
            closeQuietly(input)
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    input.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
                } catch (e: IOException) {
                    LOG.debug(e) // процесс завершился раньше, чем дочитал stdin
                }
            }
        }

        // Отмена индикатора убивает процесс.
        val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
        val output = handler.runProcessWithProgressIndicator(indicator, timeoutMs, true)
        return ToolOutput(output.exitCode, output.stdout, output.stderr, output.isTimeout, output.isCancelled)
    }

    private fun closeQuietly(input: java.io.OutputStream) {
        try {
            input.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        val LOG = logger<PreparedTool>()
    }
}
