package dev.phptools.core.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReportViewTest {
    private val user = ReportFile(
        Path.of("/p/app/Models/User.php"), "app/Models/User.php",
        listOf(
            ReportProblem(12, "Call to an undefined method", "method.notFound", "Did you mean bar()?", "https://phpstan.org/error-identifiers/method.notFound", true),
            ReportProblem(3, "Return type mismatch", "return.type", docUrl = "https://phpstan.org/error-identifiers/return.type"),
            ReportProblem(null, "File could not be loaded"),
        ),
    )
    private val controller = ReportFile(
        Path.of("/p/app/Http/Controller.php"), "app/Http/Controller.php",
        listOf(ReportProblem(7, "Another undefined method", "method.notFound")),
    )
    private val report = Report("PHPStan", 0, 4200, 2, listOf(user, controller), emptyList(), listOf("level 6", "Docker"))

    @Test
    fun `files are sorted by path and problems by line with file level first`() {
        val files = ReportView.filter(report, "")

        assertEquals(listOf("app/Http/Controller.php", "app/Models/User.php"), files.map { it.relativePath })
        assertEquals(listOf(null, 3, 12), files[1].problems.map { it.line })
    }

    @Test
    fun `filter by message, identifier and tip`() {
        assertEquals(2, ReportView.filter(report, "undefined").sumOf { it.problems.size })
        assertEquals(listOf("return.type"), ReportView.filter(report, "RETURN.type").flatMap { it.problems }.map { it.identifier })
        assertEquals(1, ReportView.filter(report, "bar()").sumOf { it.problems.size })
        assertTrue(ReportView.filter(report, "nothing like this").isEmpty())
    }

    @Test
    fun `filter by path keeps all problems of the file`() {
        val files = ReportView.filter(report, "Models/")

        assertEquals(listOf("app/Models/User.php"), files.map { it.relativePath })
        assertEquals(3, files.single().problems.size)
    }

    @Test
    fun `group by identifier puts frequent first and empty last`() {
        val groups = ReportView.groupByIdentifier(ReportView.filter(report, ""))

        assertEquals(listOf("method.notFound", "return.type", ""), groups.map { it.first })
        assertEquals(2, groups.first().second.size)
    }

    @Test
    fun `problem count`() {
        assertEquals(4, report.problemCount)
    }

    @Test
    fun `markdown export`() {
        val md = ReportView.toMarkdown(report)

        assertTrue(md.startsWith("# PHPStan report\n"))
        assertTrue("- Errors: 4 in 2 file(s)" in md)
        assertTrue("- level 6" in md && "- Docker" in md && "- Duration: 4.2 s" in md)
        assertTrue("## app/Models/User.php (3)" in md)
        assertTrue("- Line 12: Call to an undefined method — [`method.notFound`](https://phpstan.org/error-identifiers/method.notFound)" in md)
        assertTrue("  - Tip: Did you mean bar()?" in md)
        assertTrue("- File: File could not be loaded" in md)
        assertTrue("- Line 7: Another undefined method — `method.notFound`" in md)
        assertFalse("## Run errors" in md)
    }

    @Test
    fun `markdown includes run errors`() {
        val failed = report.copy(files = emptyList(), errors = listOf("Invalid configuration"))

        assertTrue("## Run errors\n\n```\nInvalid configuration\n```" in ReportView.toMarkdown(failed))
    }

    @Test
    fun `changed files candidates`() {
        assertTrue(ChangedFiles.isCandidate("/p/app/User.php"))
        assertTrue(ChangedFiles.isCandidate("C:\\p\\app\\User.PHP"))
        assertFalse(ChangedFiles.isCandidate("/p/vendor/x/y.php"))
        assertFalse(ChangedFiles.isCandidate("/p/README.md"))
    }
}
