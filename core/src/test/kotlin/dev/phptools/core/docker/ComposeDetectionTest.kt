package dev.phptools.core.docker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `playground.json` снят с `docker compose config --format json` (Docker 29.8), путь проекта заменён на `/home/u/app`. */
class ComposeDetectionTest {
    private fun resource(name: String) = javaClass.getResource("/compose/$name")!!.readText()

    @Test
    fun `playground compose`() {
        val guesses = ComposeDetection.guess(ComposeDetection.parseConfigJson(resource("playground.json")), "/home/u/app", setOf("app"))

        assertEquals(listOf(DockerGuess("app", "/var/www/html", running = true, score = 4 + 3 + 2 + 1)), guesses)
        assertTrue(ComposeDetection.isConfident(guesses))
    }

    @Test
    fun `sail picks laravel test`() {
        val guesses = ComposeDetection.guess(ComposeDetection.parseConfigJson(resource("sail.json")), "/home/u/app", emptySet())

        assertEquals(listOf("laravel.test"), guesses.map { it.service })
        assertEquals("/var/www/html", guesses.single().containerProjectPath)
        assertFalse(guesses.single().running)
    }

    @Test
    fun `several php services are ranked and nginx goes last`() {
        val guesses = ComposeDetection.guess(ComposeDetection.parseConfigJson(resource("multi.json")), "/home/u/app", setOf("app", "nginx"))

        assertEquals(listOf("app", "queue", "nginx"), guesses.map { it.service })
        assertEquals("/var/www", guesses.first().containerProjectPath)
        assertTrue(ComposeDetection.isConfident(guesses)) // app: working_dir + php + running + имя
    }

    @Test
    fun `ambiguous when php services are equal`() {
        val guesses = ComposeDetection.guess(ComposeDetection.parseConfigJson(resource("multi.json")), "/home/u/app", emptySet())
            .filter { it.service != "app" }

        assertEquals(listOf("queue", "nginx"), guesses.map { it.service })
        val equal = listOf(DockerGuess("a", "/x", false, 3), DockerGuess("b", "/x", false, 2))
        assertFalse(ComposeDetection.isConfident(equal))
    }

    @Test
    fun `ancestor mount in a monorepo`() {
        val guesses = ComposeDetection.guess(ComposeDetection.parseConfigJson(resource("monorepo.json")), "/home/u/mono/services/api", emptySet())

        assertEquals("/srv/services/api", guesses.single().containerProjectPath)
    }

    @Test
    fun `nested mounts only do not fit`() {
        val services = listOf(ComposeService("worker", "php:8.4", null, null, listOf(Bind("/home/u/app/storage", "/data"))))

        assertTrue(ComposeDetection.guess(services, "/home/u/app", emptySet()).isEmpty())
    }

    @Test
    fun `windows host paths are compared case-insensitively`() {
        assertEquals("/var/www/html", ComposeDetection.containerPathFor(Bind("C:\\Work\\App", "/var/www/html"), "c:/work/app"))
        assertNull(ComposeDetection.containerPathFor(Bind("/home/u/App", "/var/www"), "/home/u/app"))
    }

    @Test
    fun `yaml fallback reads short and long volume syntax`() {
        val services = ComposeDetection.parseYaml(resource("sail.yml"), "/home/u/app")
        val guesses = ComposeDetection.guess(services, "/home/u/app", emptySet())

        assertEquals(listOf("laravel.test", "worker"), guesses.map { it.service })
        assertEquals("/var/www/html", guesses.first().containerProjectPath)
        assertEquals("/app", guesses[1].containerProjectPath)
        assertTrue(services.single { it.name == "mysql" }.binds.isEmpty()) // именованный том
    }

    @Test
    fun `broken yaml gives nothing`() {
        assertTrue(ComposeDetection.parseYaml("services: [unclosed", "/home/u/app").isEmpty())
        assertTrue(ComposeDetection.parseConfigJson("not json").isEmpty())
    }

    @Test
    fun `compose command adds -f only for non-standard files`() {
        assertEquals("docker compose", ComposeDetection.composeCommand(listOf("/home/u/app/docker-compose.yml", "/home/u/app/docker-compose.override.yml"), "/home/u/app"))
        assertEquals("docker compose", ComposeDetection.composeCommand(emptyList(), "/home/u/app"))
        assertEquals(
            "docker compose -f docker-compose.yml -f docker-compose.dev.yml",
            ComposeDetection.composeCommand(listOf("/home/u/app/docker-compose.yml", "/home/u/app/docker-compose.dev.yml"), "/home/u/app"),
        )
        assertEquals("docker compose -f /opt/stack/compose.yml", ComposeDetection.composeCommand(listOf("/opt/stack/compose.yml"), "/home/u/app"))
    }

    @Test
    fun `running services and config files from docker ps`() {
        val output = "app|/home/u/app/docker-compose.yml,/home/u/app/docker-compose.dev.yml\nqueue|/home/u/app/docker-compose.yml\n\n"
        val (running, files) = ComposeDetection.parseRunning(output)

        assertEquals(setOf("app", "queue"), running)
        assertEquals(listOf("/home/u/app/docker-compose.yml", "/home/u/app/docker-compose.dev.yml"), files)
    }

    @Test
    fun `offer only in local mode when the tool cannot run locally`() {
        assertTrue(ComposeDetection.shouldOffer(isLocalMode = true, toolRunsLocally = false, dontAsk = false, hasComposeFile = true))
        assertFalse(ComposeDetection.shouldOffer(isLocalMode = true, toolRunsLocally = true, dontAsk = false, hasComposeFile = true))
        assertFalse(ComposeDetection.shouldOffer(isLocalMode = false, toolRunsLocally = false, dontAsk = false, hasComposeFile = true))
        assertFalse(ComposeDetection.shouldOffer(isLocalMode = true, toolRunsLocally = false, dontAsk = true, hasComposeFile = true))
        assertFalse(ComposeDetection.shouldOffer(isLocalMode = true, toolRunsLocally = false, dontAsk = false, hasComposeFile = false))
    }
}
