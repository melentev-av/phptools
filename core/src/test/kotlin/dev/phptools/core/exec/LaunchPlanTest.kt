package dev.phptools.core.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class LaunchPlanTest {
    private val base = Path.of("/home/u/project")
    private val docker = DockerTarget(listOf("docker", "compose", "-f", "docker-compose.dev.yml"), "app", "/var/www/html")

    @Test
    fun `local command uses vendor owner as cwd`() {
        val workDir = base.resolve("backend")
        val plan = LaunchPlan.create(base, Resolution.Local(workDir.resolve("vendor/bin/phpstan"), workDir), null, null)

        val spec = plan.command(listOf("analyse", "--error-format=json"))

        assertEquals(listOf("/home/u/project/backend/vendor/bin/phpstan", "analyse", "--error-format=json"), spec.command)
        assertEquals(workDir, spec.workDir)
    }

    @Test
    fun `local command with php interpreter`() {
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/psalm"), base), "/usr/bin/php8.3", null)

        assertEquals(listOf("/usr/bin/php8.3", "/home/u/project/vendor/bin/psalm", "--version"), plan.command(listOf("--version")).command)
    }

    @Test
    fun `blank php interpreter is ignored`() {
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/psalm"), base), "  ", null)

        assertEquals(listOf("/home/u/project/vendor/bin/psalm"), plan.command(emptyList()).command)
    }

    @Test
    fun `local toTarget returns absolute path`() {
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/phpstan"), base), null, null)

        assertEquals("/home/u/project/src/A.php", plan.toTarget(base.resolve("src/../src/A.php")))
    }

    @Test
    fun `docker command maps executable and workdir into container`() {
        val workDir = base.resolve("backend")
        val plan = LaunchPlan.create(base, Resolution.Local(workDir.resolve("vendor/bin/phpstan"), workDir), "php", docker)

        val spec = plan.command(listOf("analyse"))

        assertEquals(
            listOf(
                "docker", "compose", "-f", "docker-compose.dev.yml",
                "exec", "-T", "-w", "/var/www/html/backend", "app",
                "php", "/var/www/html/backend/vendor/bin/phpstan", "analyse",
            ),
            spec.command,
        )
        assertEquals(base, spec.workDir)
    }

    @Test
    fun `docker in-container executable is passed as is`() {
        val plan = LaunchPlan.create(base, Resolution.InContainer("/opt/tools/phpstan", base), null, docker)

        assertEquals(
            listOf("docker", "compose", "-f", "docker-compose.dev.yml", "exec", "-T", "-w", "/var/www/html", "app", "/opt/tools/phpstan"),
            plan.command(emptyList()).command,
        )
    }

    @Test
    fun `docker toTarget maps project paths and keeps outside paths`() {
        val plan = LaunchPlan.create(base, Resolution.InContainer("vendor/bin/phpstan", base), null, docker)

        assertEquals("/var/www/html/app/Models/User.php", plan.toTarget(base.resolve("app/Models/User.php")))
        assertEquals("/var/www/html", plan.toTarget(base))
        assertEquals("/tmp/other.php", plan.toTarget(Path.of("/tmp/other.php")))
        // Префикс должен совпадать по компонентам пути, а не по строке.
        assertEquals("/home/u/project2/a.php", plan.toTarget(Path.of("/home/u/project2/a.php")))
    }

    @Test
    fun `container path trailing slash is normalized`() {
        assertEquals("/app/src/A.php", LaunchPlan.mapToContainer(base, "/app/", base.resolve("src/A.php")))
    }

    @Test
    fun `docker temp file command wraps tool in sh`() {
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/phpstan"), base), null, docker)

        val spec = plan.tempFileCommand("/tmp/phptools-1.php", listOf("analyse", "/tmp/phptools-1.php"))

        assertEquals(
            listOf(
                "docker", "compose", "-f", "docker-compose.dev.yml", "exec", "-T", "-w", "/var/www/html", "app",
                "sh", "-c", """t="$1"; shift; cat > "${'$'}t"; "$@"; c=$?; rm -f "${'$'}t"; exit ${'$'}c""", "sh", "/tmp/phptools-1.php",
                "/var/www/html/vendor/bin/phpstan", "analyse", "/tmp/phptools-1.php",
            ),
            spec.command,
        )
    }

    @Test
    fun `temp file command requires docker`() {
        val plan = LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/phpstan"), base), null, null)

        assertThrows<IllegalArgumentException> { plan.tempFileCommand("/tmp/x.php", emptyList()) }
    }

    @Test
    fun `unresolved tool cannot be launched`() {
        assertThrows<IllegalArgumentException> { LaunchPlan.create(base, Resolution.NotInstalled, null, null) }
    }
}
