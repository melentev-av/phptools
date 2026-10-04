package dev.phptools.core.exec

/**
 * Переменные окружения, по которым PHP-инструменты определяют запуск из AI-агента и меняют вывод.
 * Например, PHPStan 2.2 в этом случае отдаёт другой JSON (`error_details` вместо `files`) и добавляет
 * инструкции для агента. Если IDE запущена из терминала с агентом, она унаследует эти переменные,
 * поэтому дочернему процессу они не передаются.
 *
 * Список — из `PHPStan\Internal\AgentDetector::ENV_VARS` (PHPStan 2.2.16).
 */
object AgentEnvironment {
    val VARIABLES: Set<String> = setOf(
        "AI_AGENT",
        "AMP_CURRENT_THREAD_ID",
        "AUGMENT_AGENT",
        "CLAUDECODE",
        "CLAUDE_CODE",
        "CODEX_SANDBOX",
        "CODEX_THREAD_ID",
        "CURSOR_AGENT",
        "CURSOR_TRACE_ID",
        "GEMINI_CLI",
        "OPENCODE",
        "OPENCODE_CLIENT",
        "REPL_ID",
    )

    fun strip(environment: Map<String, String>): Map<String, String> = environment.filterKeys { it !in VARIABLES }
}
