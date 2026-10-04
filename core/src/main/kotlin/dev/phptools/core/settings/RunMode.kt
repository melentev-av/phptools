package dev.phptools.core.settings

/** Где запускать инструмент. */
enum class RunMode {
    LOCAL,
    DOCKER,

    /** Внутри WSL (только Windows). */
    WSL,
}
