package dev.phptools.core.annotator

import dev.phptools.core.annotator.CheckDecision.ANALYZE
import dev.phptools.core.annotator.CheckDecision.DEFER
import dev.phptools.core.annotator.CheckDecision.SKIP
import dev.phptools.core.exec.AgentEnvironment
import dev.phptools.core.settings.CheckMode.MANUAL
import dev.phptools.core.settings.CheckMode.ON_SAVE
import dev.phptools.core.settings.CheckMode.ON_TYPING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckPolicyTest {
    @Test
    fun `on typing analyses unsaved buffers when the tool can`() {
        assertEquals(ANALYZE, CheckPolicy.decide(ON_TYPING, inEditor = true, unsaved = true, canAnalyzeUnsaved = true))
        assertEquals(DEFER, CheckPolicy.decide(ON_TYPING, inEditor = true, unsaved = true, canAnalyzeUnsaved = false))
        assertEquals(ANALYZE, CheckPolicy.decide(ON_TYPING, inEditor = true, unsaved = false, canAnalyzeUnsaved = false))
    }

    @Test
    fun `on save waits for saving`() {
        assertEquals(DEFER, CheckPolicy.decide(ON_SAVE, inEditor = true, unsaved = true, canAnalyzeUnsaved = true))
        assertEquals(ANALYZE, CheckPolicy.decide(ON_SAVE, inEditor = true, unsaved = false, canAnalyzeUnsaved = true))
    }

    @Test
    fun `manual never runs in editor`() {
        assertEquals(SKIP, CheckPolicy.decide(MANUAL, inEditor = true, unsaved = false, canAnalyzeUnsaved = true))
        assertEquals(SKIP, CheckPolicy.decide(MANUAL, inEditor = true, unsaved = true, canAnalyzeUnsaved = true))
    }

    @Test
    fun `batch inspection ignores mode`() {
        for (mode in listOf(ON_TYPING, ON_SAVE, MANUAL)) {
            assertEquals(ANALYZE, CheckPolicy.decide(mode, inEditor = false, unsaved = false, canAnalyzeUnsaved = false))
        }
    }

    @Test
    fun `agent variables are stripped from environment`() {
        val env = mapOf("PATH" to "/bin", "CLAUDECODE" to "1", "AI_AGENT" to "x", "HOME" to "/h", "CURSOR_AGENT" to "1")

        assertEquals(mapOf("PATH" to "/bin", "HOME" to "/h"), AgentEnvironment.strip(env))
    }
}
