package dev.phptools.phpcsfixer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

/**
 * Фикстуры (`src/test/resources/fixtures`) сняты с PHP-CS-Fixer 3.95.27:
 * `<name>.php.txt` — исходник, `<name>.json` — `fix --dry-run --diff --format=json -v -` по нему,
 * `<name>.fixed.txt` — результат настоящего `fix` того же файла.
 */
class CsFixerFixtureTest {
    private fun resource(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @ParameterizedTest
    @ValueSource(strings = ["Messy", "Long", "NoEol"])
    fun `applying all hunks equals real fix`(name: String) {
        val source = resource("$name.php.txt")
        val result = CsFixerOutput.parse(8, resource("$name.json"), "") as CsFixerResult.Ok
        val hunks = CsFixerDiff.parse(result.files.single().diff)

        assertEquals(resource("$name.fixed.txt"), CsFixerDiff.applyAll(source, hunks))
    }

    @Test
    fun `fixers are read with -v`() {
        val result = CsFixerOutput.parse(8, resource("Messy.json"), "") as CsFixerResult.Ok

        assertEquals("php://stdin", result.files.single().name)
        assertTrue("single_quote" in result.files.single().fixers)
        assertTrue("no_unused_imports" in result.files.single().fixers)
    }

    @Test
    fun `changes describe each changed range`() {
        val result = CsFixerOutput.parse(8, resource("Messy.json"), "") as CsFixerResult.Ok
        val changes = CsFixerDiff.parse(result.files.single().diff).flatMap(CsFixerDiff::changes)

        assertEquals(
            listOf(
                Change(1..1, null, ""),
                Change(4..6, "use App\\Models\\User;", null),
                Change(9..9, "    public function greet( \$name )", "    public function greet(\$name)"),
                Change(11..13, "        \$greeting = \"Привет\";", "        \$greeting = 'Привет';"),
            ),
            changes,
        )
    }

    @Test
    fun `long file has several hunks`() {
        val result = CsFixerOutput.parse(8, resource("Long.json"), "") as CsFixerResult.Ok

        assertEquals(listOf(1, 30), CsFixerDiff.parse(result.files.single().diff).map { it.oldStart })
    }
}

class CsFixerDiffTest {
    @Test
    fun `single replacement`() {
        val diff = "--- a\n+++ b\n@@ -2,3 +2,3 @@\n a\n-b\n+B\n c\n"
        val hunk = CsFixerDiff.parse(diff).single()

        assertEquals(2, hunk.oldStart)
        assertEquals(3, hunk.oldCount)
        assertEquals(listOf("a", "B", "c"), hunk.newLines)
        assertEquals(listOf(3..3), hunk.changedOldLines)
        assertEquals("x\na\nB\nc\ny\n", CsFixerDiff.applyAll("x\na\nb\nc\ny\n", listOf(hunk)))
    }

    @Test
    fun `line removal`() {
        val hunk = CsFixerDiff.parse("@@ -1,3 +1,2 @@\n a\n-b\n c\n").single()

        assertEquals(listOf(2..2), hunk.changedOldLines)
        assertEquals("a\nc\n", CsFixerDiff.applyAll("a\nb\nc\n", listOf(hunk)))
    }

    @Test
    fun `pure insertion is attached to the previous line`() {
        val hunk = CsFixerDiff.parse("@@ -1,2 +1,3 @@\n <?php\n+\n namespace A;\n").single()

        assertEquals(listOf(1..1), hunk.changedOldLines)
        assertEquals("<?php\n\nnamespace A;\n", CsFixerDiff.applyAll("<?php\nnamespace A;\n", listOf(hunk)))
    }

    @Test
    fun `consecutive removals form one range`() {
        val hunk = CsFixerDiff.parse("@@ -1,5 +1,3 @@\n a\n-b\n-c\n+C\n d\n e\n").single()

        assertEquals(listOf(2..3), hunk.changedOldLines)
    }

    @Test
    fun `missing count defaults to one`() {
        val hunk = CsFixerDiff.parse("@@ -3 +3 @@\n-x\n+y\n").single()

        assertEquals(1, hunk.oldCount)
        assertEquals("a\nb\ny\n", CsFixerDiff.applyAll("a\nb\nx\n", listOf(hunk)))
    }

    @Test
    fun `no newline at end of file is added by the fixer`() {
        val diff = "@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n"
        val hunk = CsFixerDiff.parse(diff).single()

        assertEquals("a\nb\n", CsFixerDiff.applyAll("a\nb", listOf(hunk)))
    }

    @Test
    fun `new side without final newline`() {
        val diff = "@@ -1,2 +1,2 @@\n a\n-b\n+c\n\\ No newline at end of file\n"

        assertEquals("a\nc", CsFixerDiff.applyAll("a\nb\n", CsFixerDiff.parse(diff)))
    }

    @Test
    fun `hunk outside of text does not apply`() {
        assertNull(CsFixerDiff.applyAll("a\n", CsFixerDiff.parse("@@ -5,1 +5,1 @@\n-x\n+y\n")))
    }

    @Test
    fun `replacement keeps the rest of the document`() {
        val hunk = CsFixerDiff.parse("@@ -2,1 +2,1 @@\n-    return \"a\";\n+    return 'a';\n").single()
        val text = "<?php\n    return \"a\";\n// tail\n"
        val r = CsFixerDiff.replacementFor(text, hunk)!!

        assertEquals("    return \"a\";", text.substring(r.start, r.end))
        assertEquals("    return 'a';", r.text)
    }
}

class CsFixerOutputTest {
    @Test
    fun `exit codes`() {
        assertEquals(CsFixerResult.Ok(emptyList()), CsFixerOutput.parse(0, """{"files":[],"time":{"total":0}}""", ""))
        assertInstanceOf(CsFixerResult.Ok::class.java, CsFixerOutput.parse(8, """{"files":[{"name":"a.php","diff":"","appliedFixers":[]}]}""", ""))
        assertEquals(CsFixerResult.SyntaxError, CsFixerOutput.parse(4, """{"files":[]}""", ""))
        assertEquals(CsFixerResult.SyntaxError, CsFixerOutput.parse(12, """{"files":[]}""", ""))
        assertEquals(CsFixerResult.Failed("bad config"), CsFixerOutput.parse(16, "", "bad config"))
        assertInstanceOf(CsFixerResult.Failed::class.java, CsFixerOutput.parse(64, "", "boom"))
        assertInstanceOf(CsFixerResult.Failed::class.java, CsFixerOutput.parse(1, "", "PHP too old"))
    }

    @Test
    fun `fixers key variants and garbage before json`() {
        val stdout = "PHP Warning: x\n" + """{"files":[{"name":"a.php","diff":"d","fixers":["single_quote"]}]}"""

        assertEquals(listOf("single_quote"), (CsFixerOutput.parse(8, stdout, "") as CsFixerResult.Ok).files.single().fixers)
    }

    @Test
    fun `empty stdout with exit 0 is no changes`() {
        assertEquals(CsFixerResult.Ok(emptyList()), CsFixerOutput.parse(0, "", "You are running PHP CS Fixer on PHP 8.5.0 …"))
    }

    @Test
    fun `list-files output`() {
        val stdout = "'./app/Providers/AppServiceProvider.php'\n'./app/Style/Messy.php'\n\n"

        assertEquals(setOf("app/Providers/AppServiceProvider.php", "app/Style/Messy.php"), CsFixerOutput.parseListFiles(stdout))
    }

    @Test
    fun `command arguments`() {
        assertEquals(
            listOf("fix", "--dry-run", "--diff", "--format=json", "-v", "--using-cache=no", "--show-progress=none", "--no-interaction",
                "--path-mode=intersection", "--config=/p/.php-cs-fixer.php", "--allow-risky=yes", "-"),
            CsFixerCommand.dryRunArgs(listOf("-"), "/p/.php-cs-fixer.php", true, emptyList()),
        )
        assertEquals(
            listOf("fix", "--format=json", "-v", "--using-cache=no", "--show-progress=none", "--no-interaction", "--path-mode=intersection", "/p/app"),
            CsFixerCommand.fixArgs(listOf("/p/app"), null, false, emptyList()),
        )
        assertEquals(listOf("list-files"), CsFixerCommand.listFilesArgs(" "))
    }
}
