package dev.phptools.core.settings

import com.intellij.openapi.components.BaseState

/**
 * Общие настройки любого инструмента. Наследники добавляют свои поля и хранятся в
 * `@Service(PROJECT) @State(storages = [Storage("<tool>-tools.xml")]) SimplePersistentStateComponent<S>`.
 *
 * [defaultTimeoutSeconds] и [defaultCheckMode] позволяют наследнику задать свои значения по умолчанию
 * (например, таймаут 60 для PHPStan или «При сохранении» для медленного Phan);
 * у наследника всё равно должен быть конструктор без аргументов для десериализации.
 */
open class ToolState(
    defaultTimeoutSeconds: Int = 30,
    defaultCheckMode: CheckMode = CheckMode.ON_TYPING,
) : BaseState() {
    /** Путь к бинарнику. Пусто — автопоиск `vendor/bin/<tool>`. */
    var executable by string("")
    /** Путь к конфигу. Пусто — инструмент ищет сам. */
    var configPath by string("")
    /** Если задан — запуск как `<php> <executable>`. В Docker это команда внутри контейнера. */
    var phpInterpreter by string("")
    var useDocker by property(false)
    var composeCommand by string(DEFAULT_COMPOSE_COMMAND)
    var composeService by string("")
    var containerProjectPath by string(DEFAULT_CONTAINER_PROJECT_PATH)
    /** Доп. аргументы, разбираются через `ParametersListUtil.parse`. */
    var extraArgs by string("")
    var timeoutSeconds by property(defaultTimeoutSeconds)
    /** Когда запускать проверку в редакторе. На Inspect Code не влияет. */
    var checkMode by enum(defaultCheckMode)

    companion object {
        const val DEFAULT_COMPOSE_COMMAND = "docker compose"
        const val DEFAULT_CONTAINER_PROJECT_PATH = "/var/www/html"
    }
}
