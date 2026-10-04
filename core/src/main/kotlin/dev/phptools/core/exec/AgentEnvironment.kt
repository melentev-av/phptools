package dev.phptools.core.exec

/**
 * Переменные окружения, по которым PHP-инструменты определяют запуск из AI-агента и меняют вывод.
 * Например, PHPStan 2.2 в этом случае отдаёт другой JSON (`error_details` вместо `files`) и добавляет
 * инструкции для агента. Если IDE запущена из терминала с агентом, она унаследует эти переменные,
 * поэтому дочернему процессу они не передаются.
 *
 * Список — из `PHPStan\Internal\AgentDetector::ENV_VARS` (PHPStan 2.2.16) и
 * `Psalm\Internal\CliUtils::runningUnderAiAgent` (Psalm 6.19.1). Psalm ещё смотрит на `AGENT=goose`.
 */
object AgentEnvironment {
    val VARIABLES: Set<String> = setOf(
        "AI_AGENT",
        "AMP_CURRENT_THREAD_ID",
        "ANTIGRAVITY_AGENT",
        "AUGMENT_AGENT",
        "CLAUDECODE",
        "CLAUDE_CODE",
        "CLINE_ACTIVE",
        "CODEX_SANDBOX",
        "CODEX_THREAD_ID",
        "COPILOT_CLI",
        "CURSOR_AGENT",
        "CURSOR_TRACE_ID",
        "GEMINI_CLI",
        "OPENCODE",
        "OPENCODE_CLIENT",
        "PI_CODING_AGENT",
        "REPL_ID",
        "TRAE_AI_SHELL_ID",
    )

    fun strip(environment: Map<String, String>): Map<String, String> =
        environment.filter { (name, value) -> name !in VARIABLES && !(name == "AGENT" && value.equals("goose", ignoreCase = true)) }
}
