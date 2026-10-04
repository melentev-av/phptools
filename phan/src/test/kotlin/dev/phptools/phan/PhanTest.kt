package dev.phptools.phan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фикстуры (`src/test/resources/fixtures`) сняты с Phan 6.0.7 без php-ast (`--allow-polyfill-parser`). */
class PhanOutputTest {
    private fun resource(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun ok(result: PhanResult) = assertInstanceOf(PhanResult.Ok::class.java, result)

    @Test
    fun `issues are parsed with stripped prefix`() {
        val issues = ok(PhanOutput.parse(1, resource("issues.json"), "")).issues

        assertEquals(listOf("PhanUndeclaredMethod", "PhanTypeMismatchReturnReal", "PhanSyntaxError"), issues.map { it.checkName })
        val undeclared = issues[0]
        assertEquals("Call to undeclared method \\App\\PhanDemo::nope", undeclared.message)
        assertEquals("app/PhanDemo.php", undeclared.path)
        assertEquals(8, undeclared.lineBegin)
        assertEquals(10, undeclared.severity)
        assertNull(undeclared.column)
        assertEquals(35, issues[2].column)
        assertTrue(issues[1].message.startsWith("Returning 'a' of type"))
    }

    @Test
    fun `clean file and file outside directory_list`() {
        assertTrue(ok(PhanOutput.parse(0, resource("clean.json"), "")).issues.isEmpty())
        assertTrue(ok(PhanOutput.parse(0, "", "")).issues.isEmpty())
    }

    @Test
    fun `config error is a failure`() {
        val stderr = resource("config-error.stderr.txt")
        val result = assertInstanceOf(PhanResult.Failed::class.java, PhanOutput.parse(1, "", stderr))

        assertTrue(result.details.startsWith("ERROR: ParseError"))
    }

    @Test
    fun `missing php-ast is recognized`() {
        val stderr = "ERROR: The php-ast extension must be loaded in order for Phan to work. Either install and enable php-ast, or invoke Phan with the CLI option --allow-polyfill-parser"

        assertEquals(PhanResult.PhpAstMissing, PhanOutput.parse(1, "", stderr))
    }

    @Test
    fun `garbage before json and unexpected exit code`() {
        assertEquals(3, ok(PhanOutput.parse(1, "Warning: something\n" + resource("issues.json"), "")).issues.size)
        assertInstanceOf(PhanResult.Failed::class.java, PhanOutput.parse(255, resource("issues.json"), "Fatal"))
    }

    @Test
    fun `issues are matched by relative path`() {
        val issues = ok(PhanOutput.parse(1, resource("issues.json"), "")).issues

        assertEquals(2, PhanOutput.issuesFor(issues, "app/PhanDemo.php").size)
        assertEquals(1, PhanOutput.issuesFor(issues, "./app/PhanSyntax.php").size)
        assertTrue(PhanOutput.issuesFor(issues, "app/Other.php").isEmpty())
    }

    @Test
    fun `prefix stripping and severity`() {
        assertEquals("text", PhanOutput.stripPrefix("UndefError PhanX text", "PhanX"))
        assertEquals("No prefix here", PhanOutput.stripPrefix("No prefix here", "PhanX"))
        assertTrue(PhanIssue("PhanX", "m", 0, "a.php", 1, 1, null).isLow)
    }

    @Test
    fun `documentation url`() {
        assertEquals(
            "https://github.com/phan/phan/blob/v6/internal/Issue-Types-Caught-by-Phan.md#phanundeclaredmethod",
            PhanOutput.docUrl("PhanUndeclaredMethod"),
        )
        assertNull(PhanOutput.docUrl("SomePluginIssue"))
    }
}

class PhanCommandTest {
    @Test
    fun `editor arguments`() {
        assertEquals(
            listOf("-m", "json", "--no-progress-bar", "--allow-polyfill-parser", "-I", "app/A.php"),
            PhanCommand.args(listOf("app/A.php"), true, "", null, emptyList()),
        )
    }

    @Test
    fun `config, memory, processes and several files`() {
        assertEquals(
            listOf("-m", "json", "--no-progress-bar", "--memory-limit", "2G", "-k", "/p/.phan/config.php", "-j", "4", "--analyze-twice", "-I", "a.php,b.php"),
            PhanCommand.args(listOf("a.php", "b.php"), false, "2G", "/p/.phan/config.php", listOf("--analyze-twice"), processes = 4),
        )
    }

    @Test
    fun `whole project has no -I`() {
        assertEquals(listOf("-m", "json", "--no-progress-bar"), PhanCommand.args(emptyList(), false, null, null, emptyList()))
    }
}

class PhanSuppressTest {
    private fun apply(text: String, line: Int, check: String): String {
        val insert = PhanSuppress.edit(text, line, check) ?: return text
        return text.substring(0, insert.offset) + insert.text + text.substring(insert.offset)
    }

    @Test
    fun `inserts comment with the same indent`() {
        assertEquals(
            "<?php\n        // @phan-suppress-next-line PhanUndeclaredMethod\n        \$this->nope();\n",
            apply("<?php\n        \$this->nope();\n", 2, "PhanUndeclaredMethod"),
        )
    }

    @Test
    fun `appends to existing comment with a comma`() {
        assertEquals(
            "<?php\n// @phan-suppress-next-line PhanA, PhanB\nfoo();\n",
            apply("<?php\n// @phan-suppress-next-line PhanA\nfoo();\n", 3, "PhanB"),
        )
    }

    @Test
    fun `duplicate and current-line comments`() {
        assertNull(PhanSuppress.edit("<?php\n// @phan-suppress-next-line PhanA, PhanB\nfoo();\n", 3, "PhanB"))
        // `-current-line` не трогаем — вставляем отдельную строку.
        assertEquals(
            "<?php\n// @phan-suppress-current-line PhanA\n// @phan-suppress-next-line PhanB\nfoo();\n",
            apply("<?php\n// @phan-suppress-current-line PhanA\nfoo();\n", 3, "PhanB"),
        )
    }

    @Test
    fun `out of range`() {
        assertNull(PhanSuppress.edit("<?php\n", 3, "PhanA"))
    }
}
