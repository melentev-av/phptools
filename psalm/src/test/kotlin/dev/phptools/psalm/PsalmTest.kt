package dev.phptools.psalm

import dev.phptools.core.annotator.ProblemRanges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фикстуры сняты с Psalm 6.19.1 (playground/laravel). */
class PsalmOutputTest {
    private val json = """
        [{"link":"https:\/\/psalm.dev\/011","severity":"error","line_from":6,"line_to":6,"type":"InvalidReturnType",
          "message":"The declared return type 'int' for App\\Broken::x is incorrect, got ''a''","file_name":"app\/Broken.php",
          "file_path":"\/var\/www\/html\/app\/Broken.php","snippet":"    public function x(): int","selected_text":"int",
          "from":62,"to":65,"column_from":26,"column_to":29,"shortcode":11,"error_level":6},
         {"link":"https:\/\/psalm.dev\/050","severity":"info","line_from":6,"line_to":6,"type":"MissingParamType",
          "message":"Parameter ${'$'}a has no provided type","file_name":"app\/Broken.php","file_path":"\/var\/www\/html\/app\/Broken.php",
          "column_from":30,"column_to":32},
         {"link":"https:\/\/psalm.dev\/022","severity":"error","line_from":3,"line_to":3,"type":"UndefinedMethod",
          "message":"Method App\\Other::nope does not exist","file_name":"app\/Other.php","file_path":"\/var\/www\/html\/app\/Other.php",
          "column_from":16,"column_to":20}]
    """.trimIndent()

    private fun ok(result: PsalmResult) = assertInstanceOf(PsalmResult.Ok::class.java, result)

    @Test
    fun `issues are parsed`() {
        val issues = ok(PsalmOutput.parse(2, json, "")).issues

        assertEquals(3, issues.size)
        val first = issues[0]
        assertEquals("InvalidReturnType", first.type)
        assertEquals(6, first.lineFrom)
        assertEquals(26, first.columnFrom)
        assertEquals(29, first.columnTo)
        assertEquals("https://psalm.dev/011", first.link)
        assertTrue(issues[1].isInfo)
    }

    @Test
    fun `no issues`() {
        assertTrue(ok(PsalmOutput.parse(0, "[]\n", "")).issues.isEmpty())
        assertTrue(ok(PsalmOutput.parse(0, "", "")).issues.isEmpty())
    }

    @Test
    fun `garbage before json`() {
        assertEquals(3, ok(PsalmOutput.parse(2, "Deprecated: something\n$json", "")).issues.size)
    }

    @Test
    fun `config error is a failure`() {
        val stderr = "Problem parsing /p/psalm.xml:\n  Error on line 2:\n    Element 'foo': This element is not expected."

        assertEquals(PsalmResult.Failed(stderr), PsalmOutput.parse(1, "", stderr))
    }

    @Test
    fun `unexpected exit code is a failure`() {
        assertInstanceOf(PsalmResult.Failed::class.java, PsalmOutput.parse(255, json, "Fatal"))
    }

    @Test
    fun `issues are matched by relative path`() {
        val issues = ok(PsalmOutput.parse(2, json, "")).issues

        assertEquals(listOf("InvalidReturnType", "MissingParamType"), PsalmOutput.issuesFor(issues, "app/Broken.php").map { it.type })
        assertEquals(listOf("UndefinedMethod"), PsalmOutput.issuesFor(issues, "app/Other.php").map { it.type })
        assertTrue(PsalmOutput.issuesFor(issues, "app/Missing.php").isEmpty())
    }

    @Test
    fun `single file in output is used when path does not match`() {
        val issues = ok(PsalmOutput.parse(2, json, "")).issues.filter { it.fileName == "app/Broken.php" }

        assertEquals(2, PsalmOutput.issuesFor(issues, "somewhere/else.php").size)
    }

    @Test
    fun `byte columns are converted to characters`() {
        // Psalm 6.19.1: `nope` с 31-го символа, но column_from = 37 (6 кириллических букв = +6 байт)
        val text = "<?php\n        \$s = \"Привет\"; \$this->nope(\$s);\n"
        val range = ProblemRanges(text).compute(2, 2, 37, 41, byteColumns = true)

        assertEquals("nope", text.substring(range.first, range.last + 1))
    }

    @Test
    fun `byte columns with emoji and past end of line`() {
        val text = "\$a = '😀'; foo();\n"
        // 😀 — 4 байта и 2 UTF-16 символа: `foo` с 12-го символа, но с 14-го байта (6 + 4 + 3 байта перед ним)
        val range = ProblemRanges(text).compute(1, 1, 14, 17, byteColumns = true)

        assertEquals("foo", text.substring(range.first, range.last + 1))
        val tail = ProblemRanges(text).compute(1, 1, 14, 500, byteColumns = true)
        assertEquals("foo();", text.substring(tail.first, tail.last + 1))
    }
}

class PsalmCommandTest {
    @Test
    fun `editor arguments`() {
        assertEquals(
            listOf("--output-format=json", "--no-progress", "--monochrome", "--threads=1", "--show-info=false", "/p/app/A.php"),
            PsalmCommand.args(listOf("/p/app/A.php"), 1, false, null, emptyList()),
        )
    }

    @Test
    fun `config, show info, threads and extra args`() {
        assertEquals(
            listOf("--output-format=json", "--no-progress", "--monochrome", "--threads=4", "--show-info=true", "-c", "/p/psalm.xml", "--no-cache", "a.php", "b.php"),
            PsalmCommand.args(listOf("a.php", "b.php"), 4, true, "/p/psalm.xml", listOf("--no-cache")),
        )
    }

    @Test
    fun `whole project and invalid threads`() {
        assertEquals(
            listOf("--output-format=json", "--no-progress", "--monochrome", "--threads=1", "--show-info=false"),
            PsalmCommand.args(emptyList(), 0, false, " ", emptyList()),
        )
    }
}

class PsalmSuppressTest {
    private fun apply(text: String, line: Int, type: String): String {
        val insert = PsalmSuppress.edit(text, line, type) ?: return text
        return text.substring(0, insert.offset) + insert.text + text.substring(insert.offset)
    }

    @Test
    fun `inserts docblock above with the same indent`() {
        assertEquals(
            "<?php\n        /** @psalm-suppress UndefinedMethod */\n        \$this->nope();\n",
            apply("<?php\n        \$this->nope();\n", 2, "UndefinedMethod"),
        )
    }

    @Test
    fun `appends to single-line suppress with a comma`() {
        assertEquals(
            "<?php\n    /** @psalm-suppress UndefinedMethod, InvalidScalarArgument */\n    \$this->nope(strlen(\$i));\n",
            apply("<?php\n    /** @psalm-suppress UndefinedMethod */\n    \$this->nope(strlen(\$i));\n", 3, "InvalidScalarArgument"),
        )
    }

    @Test
    fun `duplicate type is not added`() {
        assertNull(PsalmSuppress.edit("<?php\n/** @psalm-suppress A, B */\nfoo();\n", 3, "B"))
    }

    @Test
    fun `multi-line docblock is not touched`() {
        val text = "<?php\n    /**\n     * Doc.\n     */\n    public function x() {}\n"

        assertEquals("<?php\n    /**\n     * Doc.\n     */\n    /** @psalm-suppress MissingReturnType */\n    public function x() {}\n", apply(text, 5, "MissingReturnType"))
    }

    @Test
    fun `out of range`() {
        assertNull(PsalmSuppress.edit("<?php\n", 3, "A"))
        assertNull(PsalmSuppress.edit("<?php\nfoo();\n", 2, " "))
    }
}
