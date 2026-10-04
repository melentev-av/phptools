package dev.phptools.psalm

import com.intellij.DynamicBundle
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.settings.ToolState
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.nio.file.Path

class PsalmState : ToolState(defaultTimeoutSeconds = 60) {
    /** `--show-info`: info-проблемы как weak warning. */
    var showInfo by property(false)
    /** `--threads`. */
    var threads by property(1)
}

/** Настройки проекта; файл `.idea/psalm-tools.xml` можно коммитить. */
@Service(Service.Level.PROJECT)
@State(name = "PsalmSettings", storages = [Storage("psalm-tools.xml")])
class PsalmSettings : SimplePersistentStateComponent<PsalmState>(PsalmState()) {
    companion object {
        fun getInstance(project: Project): PsalmSettings = project.service()

        /** Путь к конфигу на стороне инструмента или `null` (Psalm ищет `psalm.xml` сам). */
        fun configTarget(project: Project, tool: PreparedTool, state: PsalmState): String? {
            val configured = state.configPath.orEmpty().trim().ifEmpty { return null }
            val path = Path.of(configured)
            if (path.isAbsolute) return tool.toTarget(path)
            val base = project.basePath ?: return configured
            return tool.toTarget(Path.of(base).resolve(path))
        }
    }
}

private const val BUNDLE = "messages.PsalmBundle"

object PsalmBundle : DynamicBundle(PsalmBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
