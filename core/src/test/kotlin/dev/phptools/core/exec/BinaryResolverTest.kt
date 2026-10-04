package dev.phptools.core.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BinaryResolverTest {
    @TempDir
    lateinit var base: Path

    private fun touch(relative: String): Path =
        base.resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.createFile(it)
        }

    private fun resolve(
        configured: String = "",
        contextDir: Path? = null,
        useDocker: Boolean = false,
        preferBat: Boolean = false,
    ) = BinaryResolver.resolve(base, configured, "phpstan", contextDir, useDocker, preferBat)

    @Test
    fun `finds vendor bin in project root`() {
        val exe = touch("vendor/bin/phpstan")

        assertEquals(Resolution.Local(exe, base), resolve(contextDir = base.resolve("src")))
    }

    @Test
    fun `nearest vendor owner up from file wins`() {
        touch("vendor/bin/phpstan")
        val nested = touch("packages/api/vendor/bin/phpstan")
        val context = base.resolve("packages/api/src/Http")
        Files.createDirectories(context)

        assertEquals(Resolution.Local(nested, base.resolve("packages/api")), resolve(contextDir = context))
    }

    @Test
    fun `search stops at project root`() {
        assertEquals(Resolution.NotInstalled, resolve(contextDir = base.resolve("src")))
    }

    @Test
    fun `context outside project falls back to root`() {
        val exe = touch("vendor/bin/phpstan")

        assertEquals(Resolution.Local(exe, base), resolve(contextDir = Path.of("/somewhere/else")))
    }

    @Test
    fun `configured relative path resolves from project root`() {
        val exe = touch("tools/vendor/bin/phpstan")

        assertEquals(Resolution.Local(exe, base.resolve("tools")), resolve(configured = "tools/vendor/bin/phpstan"))
    }

    @Test
    fun `configured path outside vendor uses project root as workdir`() {
        val exe = touch("bin/phpstan.phar")

        assertEquals(Resolution.Local(exe, base), resolve(configured = exe.toString()))
    }

    @Test
    fun `configured missing path without docker is reported`() {
        assertEquals(Resolution.ConfiguredMissing(base.resolve("nope/phpstan")), resolve(configured = "nope/phpstan"))
    }

    @Test
    fun `configured missing path in docker is a container path`() {
        assertEquals(Resolution.InContainer("/usr/local/bin/phpstan", base), resolve(configured = "/usr/local/bin/phpstan", useDocker = true))
    }

    @Test
    fun `bat sibling is preferred when requested`() {
        touch("vendor/bin/phpstan")
        val bat = touch("vendor/bin/phpstan.bat")

        assertEquals(Resolution.Local(bat, base), resolve(preferBat = true))
        assertEquals(Resolution.Local(base.resolve("vendor/bin/phpstan"), base), resolve(preferBat = false))
    }

    @Test
    fun `search dirs go from context up to base`() {
        val dirs = BinaryResolver.searchDirs(base, base.resolve("a/b"))

        assertEquals(listOf(base.resolve("a/b"), base.resolve("a"), base), dirs)
    }
}
