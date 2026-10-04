package dev.phptools.core.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CommandChunksTest {
    @Test
    fun `everything fits in one chunk`() {
        assertEquals(listOf(listOf("a", "b", "c")), CommandChunks.split(10, listOf("a", "b", "c"), 100))
    }

    @Test
    fun `chunks respect the limit`() {
        // fixed 10 + each item 4 chars + separator = 5 → 2 items per chunk with limit 20
        val chunks = CommandChunks.split(10, listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"), 20)

        assertEquals(listOf(listOf("aaaa", "bbbb"), listOf("cccc", "dddd"), listOf("eeee")), chunks)
    }

    @Test
    fun `item longer than limit goes alone`() {
        assertEquals(listOf(listOf("a"), listOf("x".repeat(50)), listOf("b")), CommandChunks.split(5, listOf("a", "x".repeat(50), "b"), 10))
    }

    @Test
    fun `empty input`() {
        assertEquals(emptyList<List<String>>(), CommandChunks.split(5, emptyList(), 10))
    }

    @Test
    fun `fromTarget maps container paths back to the project`() {
        val base = Path.of("/home/u/project")
        val docker = DockerTarget(listOf("docker", "compose"), "app", "/var/www/html/")
        val plan = LaunchPlan.create(base, Resolution.InContainer("vendor/bin/phpstan", base), null, docker)

        assertEquals(base.resolve("app/Models/User.php"), plan.fromTarget("/var/www/html/app/Models/User.php"))
        assertEquals(base, plan.fromTarget("/var/www/html"))
        assertEquals(Path.of("/var/www/html2/a.php"), plan.fromTarget("/var/www/html2/a.php"))
        assertEquals(Path.of("/tmp/x.php"), plan.fromTarget("/tmp/x.php"))
    }

    @Test
    fun `fromTarget is identity locally`() {
        val base = Path.of("/home/u/project")
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/phpstan"), base), null, null)

        assertEquals(Path.of("/home/u/project/app/A.php"), plan.fromTarget("/home/u/project/app/A.php"))
    }
}
