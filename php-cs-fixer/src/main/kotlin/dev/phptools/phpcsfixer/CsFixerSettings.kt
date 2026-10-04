package dev.phptools.phpcsfixer

import com.intellij.DynamicBundle
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.settings.ToolState
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class CsFixerState : ToolState(defaultTimeoutSeconds = 20) {
    /** `--allow-risky=yes`. */
    var allowRisky by property(false)
}

/** Настройки проекта; файл `.idea/php-cs-fixer-tools.xml` можно коммитить. */
@Service(Service.Level.PROJECT)
@State(name = "PhpCsFixerSettings", storages = [Storage("php-cs-fixer-tools.xml")])
class CsFixerSettings : SimplePersistentStateComponent<CsFixerState>(CsFixerState()) {
    companion object {
        fun getInstance(project: Project): CsFixerSettings = project.service()
    }
}

/** Общие вещи для запуска: конфиг и доп. аргументы. */
object CsFixerRun {
    /** Путь к конфигу на стороне инструмента или `null` (CS-Fixer ищет `.php-cs-fixer(.dist).php` сам). */
    fun configTarget(project: Project, tool: PreparedTool, state: CsFixerState): String? {
        val configured = state.configPath.orEmpty().trim().ifEmpty { return null }
        val path = Path.of(configured)
        if (path.isAbsolute) return tool.toTarget(path)
        val base = project.basePath ?: return configured
        return tool.toTarget(Path.of(base).resolve(path))
    }

    fun extraArgs(state: CsFixerState): List<String> = ParametersListUtil.parse(state.extraArgs.orEmpty())
}

/**
 * Файлы из `Finder` конфигурации (`php-cs-fixer list-files`). Нужен для анализа через stdin:
 * без пути CS-Fixer не может применить исключения `Finder`. Кэш — 30 с и до Apply настроек.
 */
object CsFixerFinder {
    private const val TTL_MS = 30_000L

    private data class Entry(val files: Set<String>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Входит ли файл в `Finder`. `null` — выяснить не удалось (тогда анализируем: лишняя подсветка лучше,
     * чем молча пропущенный файл).
     */
    fun contains(project: Project, tool: PreparedTool, state: CsFixerState, file: Path): Boolean? {
        val key = project.locationHash + "\u0000" + tool.plan.workDir
        val now = System.currentTimeMillis()
        val entry = cache[key]?.takeIf { now - it.at < TTL_MS } ?: run {
            val output = tool.run(CsFixerCommand.listFilesArgs(CsFixerRun.configTarget(project, tool, state)))
            if (output.exitCode != 0 || output.cancelled || output.timedOut) return null
            Entry(CsFixerOutput.parseListFiles(output.stdout), now).also { cache[key] = it }
        }
        val relative = runCatching { tool.plan.workDir.relativize(file) }.getOrNull()?.joinToString("/") ?: return null
        return relative in entry.files
    }

    fun reset(project: Project) {
        cache.keys.removeIf { it.startsWith(project.locationHash + "\u0000") }
    }
}

private const val BUNDLE = "messages.CsFixerBundle"

object CsFixerBundle : DynamicBundle(CsFixerBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
