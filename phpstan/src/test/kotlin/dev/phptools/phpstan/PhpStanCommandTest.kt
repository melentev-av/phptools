package dev.phptools.phpstan

import dev.phptools.core.exec.DockerTarget
import dev.phptools.core.exec.LaunchPlan
import dev.phptools.core.exec.Resolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PhpStanCommandTest {
    private val base = "analyse --error-format=json --no-progress --no-interaction".split(" ")

    @Test
    fun `minimal arguments`() {
        assertEquals(base + "/p/app/A.php", PhpStanCommand.analyseArgs("/p/app/A.php", "", null, null, emptyList()))
    }

    @Test
    fun `memory limit, config, level and extra args`() {
        val args = PhpStanCommand.analyseArgs("/p/app/A.php", "1G", "/p/phpstan.neon", "6", listOf("--xdebug"))

        assertEquals(base + listOf("--memory-limit=1G", "-c", "/p/phpstan.neon", "--level=6", "--xdebug", "/p/app/A.php"), args)
    }

    @Test
    fun `blank level and memory limit are not passed`() {
        assertEquals(base + "/p/A.php", PhpStanCommand.analyseArgs("/p/A.php", "  ", "", " ", emptyList()))
    }

    @Test
    fun `unsaved buffer locally uses absolute tmp path`() {
        val args = PhpStanCommand.analyseArgs("/p/app/A.php", null, null, null, emptyList(), tmpFile = "/tmp/phptools-1.php")

        assertEquals(base + listOf("--tmp-file=/tmp/phptools-1.php", "--instead-of=/p/app/A.php", "/p/app/A.php"), args)
    }

    @Test
    fun `unsaved buffer in docker goes through sh wrapper`() {
        val project = Path.of("/home/u/project")
        val docker = DockerTarget(listOf("docker", "compose"), "laravel.test", "/var/www/html")
        val plan = LaunchPlan.create(project, Resolution.Local(project.resolve("vendor/bin/phpstan"), project), null, docker)
        val target = plan.toTarget(project.resolve("app/A.php"))
        val tmp = "/tmp/phptools-1.php"

        val command = plan.tempFileCommand(tmp, PhpStanCommand.analyseArgs(target, null, null, null, emptyList(), tmp)).command

        assertEquals(
            listOf("docker", "compose", "exec", "-T", "-w", "/var/www/html", "laravel.test", "sh", "-c", LaunchPlan.TEMP_FILE_SCRIPT, "sh", tmp,
                "/var/www/html/vendor/bin/phpstan") + base +
                listOf("--tmp-file=$tmp", "--instead-of=/var/www/html/app/A.php", "/var/www/html/app/A.php"),
            command,
        )
    }

    @Test
    fun `batch arguments for several targets and for the whole project`() {
        assertEquals(base + listOf("--level=5", "/p/app/A.php", "/p/app/Models"), PhpStanCommand.batchArgs(listOf("/p/app/A.php", "/p/app/Models"), null, null, "5", emptyList()))
        assertEquals(base + listOf("-c", "/p/phpstan.neon"), PhpStanCommand.batchArgs(emptyList(), "", "/p/phpstan.neon", null, emptyList()))
    }

    @Test
    fun `dump parameters arguments`() {
        assertEquals(listOf("dump-parameters", "--json", "--no-interaction", "-c", "/p/phpstan.neon"), PhpStanCommand.dumpParametersArgs("/p/phpstan.neon"))
        assertEquals(listOf("dump-parameters", "--json", "--no-interaction"), PhpStanCommand.dumpParametersArgs(null))
    }

    @Test
    fun `config level is read from dump-parameters`() {
        val stdout = """{"analysedPathsFromConfig":["/p/app"],"level":6,"paths":["/p/app"],"usedLevel":"6"}"""

        assertEquals(ConfigLevel.Set("6"), PhpStanConfigLevel.parse(0, stdout, "Note: Using configuration file /p/phpstan.neon."))
        assertEquals(ConfigLevel.Set("max"), PhpStanConfigLevel.parse(0, """{"level":"max"}""", ""))
    }

    @Test
    fun `missing level is detected`() {
        val stderr = "\nNo rules detected\n\nYou have the following choices:\n"

        assertEquals(ConfigLevel.NotSet, PhpStanConfigLevel.parse(1, "", stderr))
        assertEquals(ConfigLevel.NotSet, PhpStanConfigLevel.parse(0, """{"level":null}""", ""))
    }

    @Test
    fun `broken config gives unknown level`() {
        assertInstanceOf(ConfigLevel.Unknown::class.java, PhpStanConfigLevel.parse(1, "", "Invalid configuration:"))
    }
}
