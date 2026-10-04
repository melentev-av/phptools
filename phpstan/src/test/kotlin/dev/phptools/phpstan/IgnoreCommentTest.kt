package dev.phptools.phpstan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IgnoreCommentTest {

    private fun apply(text: String, line: Int, id: String): String {
        val insert = IgnoreComment.edit(text, line, id) ?: return text
        return text.substring(0, insert.offset) + insert.text + text.substring(insert.offset)
    }

    @Test
    fun `inserts comment above with the same indent`() {
        val text = "<?php\nclass A {\n    public function f() {\n        \$this->nope();\n    }\n}\n"

        assertEquals(
            "<?php\nclass A {\n    public function f() {\n        // @phpstan-ignore method.notFound\n        \$this->nope();\n    }\n}\n",
            apply(text, 4, "method.notFound"),
        )
    }

    @Test
    fun `tabs are kept`() {
        assertEquals("<?php\n\t// @phpstan-ignore x.y\n\tfoo();\n", apply("<?php\n\tfoo();\n", 2, "x.y"))
    }

    @Test
    fun `appends to existing comment`() {
        val text = "<?php\n    // @phpstan-ignore return.type\n    return 'a';\n"

        assertEquals("<?php\n    // @phpstan-ignore return.type, method.notFound\n    return 'a';\n", apply(text, 3, "method.notFound"))
    }

    @Test
    fun `appends before explanation in parentheses`() {
        val text = "<?php\n// @phpstan-ignore return.type (legacy code)\nreturn 'a';\n"

        assertEquals("<?php\n// @phpstan-ignore return.type, argument.type (legacy code)\nreturn 'a';\n", apply(text, 3, "argument.type"))
    }

    @Test
    fun `existing identifier is not duplicated`() {
        assertNull(IgnoreComment.edit("<?php\n// @phpstan-ignore a.b, c.d\nfoo();\n", 3, "c.d"))
    }

    @Test
    fun `ignore-next-line is not extended`() {
        val text = "<?php\n// @phpstan-ignore-next-line\nfoo();\n"

        assertEquals("<?php\n// @phpstan-ignore-next-line\n// @phpstan-ignore a.b\nfoo();\n", apply(text, 3, "a.b"))
    }

    @Test
    fun `first line and windows line endings`() {
        assertEquals("// @phpstan-ignore a.b\nfoo();", apply("foo();", 1, "a.b"))
        assertEquals("<?php\r\n  // @phpstan-ignore a.b, c.d\r\n  foo();\r\n", apply("<?php\r\n  // @phpstan-ignore a.b\r\n  foo();\r\n", 3, "c.d"))
    }

    @Test
    fun `line out of range`() {
        assertNull(IgnoreComment.edit("<?php\n", 5, "a.b"))
        assertNull(IgnoreComment.edit("<?php\n", 0, "a.b"))
    }
}
