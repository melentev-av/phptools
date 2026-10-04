package dev.phptools.core.annotator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProblemRangesTest {
    private val text = "<?php\n    \$a = 1;\n\n\techo \$a;\r\nend"
    private val ranges = ProblemRanges(text)

    private fun slice(range: IntRange) = text.substring(range.first, range.last + 1)

    @Test
    fun `line without columns skips leading whitespace`() {
        assertEquals("\$a = 1;", slice(ranges.compute(2)))
        assertEquals("echo \$a;", slice(ranges.compute(4)))
    }

    @Test
    fun `columns are 1-based with exclusive end`() {
        assertEquals("\$a", slice(ranges.compute(2, column = 5, endColumn = 7)))
    }

    @Test
    fun `column without end goes to end of line`() {
        assertEquals("= 1;", slice(ranges.compute(2, column = 8)))
    }

    @Test
    fun `multi-line range`() {
        assertEquals("\$a = 1;\n\n\techo", slice(ranges.compute(2, endLine = 4, column = 5, endColumn = 6)))
    }

    @Test
    fun `lines are clamped to document`() {
        assertEquals("<?php", slice(ranges.compute(0)))
        assertEquals("end", slice(ranges.compute(100)))
    }

    @Test
    fun `columns are clamped to line`() {
        assertEquals("end", slice(ranges.compute(5, column = 1, endColumn = 500)))
    }

    @Test
    fun `empty range expands to whole line`() {
        assertEquals("    \$a = 1;", slice(ranges.compute(2, column = 6, endColumn = 6)))
    }

    @Test
    fun `empty line gives empty range at its start`() {
        val range = ranges.compute(3)
        assertEquals(text.indexOf("\n\n") + 1, range.first)
        assertEquals("", slice(range))
    }

    @Test
    fun `empty text`() {
        assertEquals(0..-1, ProblemRanges("").compute(1))
    }
}
