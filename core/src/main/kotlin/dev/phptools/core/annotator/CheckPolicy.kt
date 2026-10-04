package dev.phptools.core.annotator

import dev.phptools.core.settings.CheckMode

/** Что делать с файлом в аннотаторе. Чистая логика без платформы. */
enum class CheckDecision {
    /** Не анализировать и не ждать сохранения. */
    SKIP,

    /** Не анализировать сейчас; перезапустить подсветку после сохранения. */
    DEFER,

    /** Анализировать. */
    ANALYZE,
}

object CheckPolicy {
    /**
     * @param inEditor `false` — пакетный запуск (Inspect Code): это явное действие пользователя, режим не учитывается.
     * @param canAnalyzeUnsaved инструмент умеет анализировать несохранённый буфер.
     */
    fun decide(mode: CheckMode, inEditor: Boolean, unsaved: Boolean, canAnalyzeUnsaved: Boolean): CheckDecision {
        if (!inEditor) return CheckDecision.ANALYZE
        return when (mode) {
            CheckMode.MANUAL -> CheckDecision.SKIP
            CheckMode.ON_SAVE -> if (unsaved) CheckDecision.DEFER else CheckDecision.ANALYZE
            CheckMode.ON_TYPING -> if (unsaved && !canAnalyzeUnsaved) CheckDecision.DEFER else CheckDecision.ANALYZE
        }
    }
}
