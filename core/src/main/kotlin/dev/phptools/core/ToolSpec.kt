package dev.phptools.core

/** Неизменяемое описание внешнего PHP-инструмента. */
data class ToolSpec(
    /** "phpstan" — id группы уведомлений и ключ кэшей. */
    val id: String,
    /** "PHPStan" */
    val displayName: String,
    /** "phpstan" — ищется `vendor/bin/phpstan`. */
    val binaryName: String,
    val versionArgs: List<String> = listOf("--version"),
    /** id из plugin.xml, чтобы открыть настройки из уведомления. */
    val configurableId: String,
    /**
     * shortName инспекции, через которую тот же инструмент встроен в PHP-плагин OpenIDE (`ru.openide.openphp`).
     * Если она включена, пользователю предлагается её выключить, чтобы ошибки не подсвечивались дважды.
     */
    val builtInInspectionShortName: String? = null,
)
