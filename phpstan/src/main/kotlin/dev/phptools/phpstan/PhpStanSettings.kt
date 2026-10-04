package dev.phptools.phpstan

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.phptools.core.settings.ToolState
import java.util.concurrent.ConcurrentHashMap

class PhpStanState : ToolState(defaultTimeoutSeconds = 60) {
    /** `--memory-limit`; пусто — не передавать. */
    var memoryLimit by string("1G")
    /** `--level`, только если уровня нет в конфиге; пусто — не передавать. */
    var level by string("")
}

/** Настройки проекта; файл `.idea/phpstan-tools.xml` можно коммитить. */
@Service(Service.Level.PROJECT)
@State(name = "PhpStanSettings", storages = [Storage("phpstan-tools.xml")])
class PhpStanSettings : SimplePersistentStateComponent<PhpStanState>(PhpStanState()) {
    companion object {
        fun getInstance(project: Project): PhpStanSettings = project.service()
    }
}

/** Состояние, которое живёт до следующего Apply настроек. */
object PhpStanRuntime {
    /** Проекты, где PHPStan не поддерживает `--tmp-file`. */
    private val tmpFileUnsupported = ConcurrentHashMap.newKeySet<String>()
    /** Уровень из конфига по проекту (результат `dump-parameters`). */
    private val configLevels = ConcurrentHashMap<String, ConfigLevel>()

    fun isTmpFileUnsupported(project: Project) = project.locationHash in tmpFileUnsupported

    fun markTmpFileUnsupported(project: Project) {
        tmpFileUnsupported.add(project.locationHash)
    }

    fun configLevel(project: Project): ConfigLevel? = configLevels[project.locationHash]

    fun rememberConfigLevel(project: Project, level: ConfigLevel) {
        if (level !is ConfigLevel.Unknown) configLevels[project.locationHash] = level
    }

    fun reset(project: Project) {
        tmpFileUnsupported.remove(project.locationHash)
        configLevels.remove(project.locationHash)
    }
}
