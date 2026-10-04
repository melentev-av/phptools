package dev.phptools.core.exec

import dev.phptools.core.settings.RunMode
import dev.phptools.core.settings.ToolState
import dev.phptools.core.startup.WslSuggestion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WslPathsTest {
    @Test
    fun `unc paths to linux`() {
        assertEquals("/home/u/app/x.php", WslPaths.toLinux("""\\wsl$\Ubuntu\home\u\app\x.php""", "Ubuntu"))
        assertEquals("/home/u/app", WslPaths.toLinux("""\\wsl.localhost\Ubuntu-24.04\home\u\app\""", "ubuntu-24.04"))
        assertEquals("/home/u/app", WslPaths.toLinux("//wsl$/Ubuntu/home/u/app", null))
        assertEquals("/", WslPaths.toLinux("""\\wsl.localhost\Ubuntu""", "Ubuntu"))
    }

    @Test
    fun `other distribution is not mapped`() {
        assertNull(WslPaths.toLinux("""\\wsl$\Debian\home\u""", "Ubuntu"))
    }

    @Test
    fun `windows drives map to mnt`() {
        assertEquals("/mnt/c/Users/u/x.php", WslPaths.toLinux("""C:\Users\u\x.php""", "Ubuntu"))
        assertEquals("/mnt/d", WslPaths.toLinux("D:\\", null))
        assertNull(WslPaths.toLinux("relative/path.php", "Ubuntu"))
    }

    @Test
    fun `linux paths back to windows`() {
        assertEquals("""\\wsl.localhost\Ubuntu\home\u\app\x.php""", WslPaths.toWindows("/home/u/app/x.php", """\\wsl.localhost\Ubuntu"""))
        assertEquals("""C:\Users\u\x.php""", WslPaths.toWindows("/mnt/c/Users/u/x.php", null))
        assertNull(WslPaths.toWindows("/home/u/x.php", null))
        assertNull(WslPaths.toWindows("app/x.php", """\\wsl$\Ubuntu"""))
    }

    @Test
    fun `distribution and unc root from project path`() {
        assertEquals("Ubuntu", WslPaths.distributionOf("//wsl.localhost/Ubuntu/home/u/app"))
        assertNull(WslPaths.distributionOf("""C:\work\app"""))
        assertEquals("""\\wsl$\Ubuntu""", WslPaths.uncRoot("//wsl$/Ubuntu/home/u/app", "Ubuntu"))
        assertEquals("""\\wsl.localhost\Debian""", WslPaths.uncRoot("""C:\work""", "Debian"))
    }

    @Test
    fun `utf-16 noise from wsl exe is cleaned`() {
        assertEquals("There is no distribution", WslOutput.fixUtf16("T\u0000h\u0000e\u0000r\u0000e\u0000 is no distribution"))
        assertEquals("ok", WslOutput.fixUtf16("ok"))
    }
}

class ShellQuoteTest {
    @Test
    fun `safe arguments stay as is`() {
        assertEquals("vendor/bin/phpstan analyse --error-format=json", ShellQuote.join(listOf("vendor/bin/phpstan", "analyse", "--error-format=json")))
    }

    @Test
    fun `quotes spaces, dollars and single quotes`() {
        assertEquals("'a b' '\$HOME' 'it'\\''s' ''", ShellQuote.join(listOf("a b", "\$HOME", "it's", "")))
    }
}

class WslLaunchPlanTest {
    // На macOS/Linux `Path` не умеет UNC, поэтому команды проверяются на путях в стиле Linux.
    private val base = Path.of("/home/u/app")
    private val wsl = WslTarget("Ubuntu", loginShell = false, uncRoot = """\\wsl.localhost\Ubuntu""", windowsWorkDir = Path.of("/winhome"))

    private fun plan(target: WslTarget = wsl, php: String? = null) =
        LaunchPlan.create(base, Resolution.Local(base.resolve("vendor/bin/phpstan"), base), php, null, target)

    @Test
    fun `plain command uses exec without shell`() {
        val spec = plan(php = "php").command(listOf("analyse", "app/A.php"))

        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu", "--cd", "/home/u/app", "--exec", "php", "/home/u/app/vendor/bin/phpstan", "analyse", "app/A.php"),
            spec.command,
        )
        assertEquals(Path.of("/winhome"), spec.workDir)
    }

    @Test
    fun `default distribution omits -d`() {
        val spec = plan(wsl.copy(distribution = null)).command(listOf("--version"))

        assertEquals(listOf("wsl.exe", "--cd", "/home/u/app", "--exec", "/home/u/app/vendor/bin/phpstan", "--version"), spec.command)
    }

    @Test
    fun `login shell wraps the command into bash -lc`() {
        val spec = plan(wsl.copy(loginShell = true)).command(listOf("analyse", "my file.php"))

        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu", "--cd", "/home/u/app", "--exec", "bash", "-lc", "/home/u/app/vendor/bin/phpstan analyse 'my file.php'"),
            spec.command,
        )
    }

    @Test
    fun `temp file is created inside wsl`() {
        val tmp = "/tmp/phptools-1.php"
        val spec = plan().tempFileCommand(tmp, listOf("analyse", "--tmp-file=$tmp"))

        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu", "--cd", "/home/u/app", "--exec", "sh", "-c", LaunchPlan.TEMP_FILE_SCRIPT, "sh", tmp,
                "/home/u/app/vendor/bin/phpstan", "analyse", "--tmp-file=$tmp"),
            spec.command,
        )
        assertTrue(plan().usesRemoteTempFile)
        assertEquals("WSL (Ubuntu)", plan().runtimeLabel)
    }

    @Test
    fun `executable configured inside wsl is passed as is`() {
        val p = LaunchPlan.create(base, Resolution.InContainer("/usr/local/bin/phpstan", base), null, null, wsl)

        assertEquals("/usr/local/bin/phpstan", p.command(emptyList()).command.last())
    }

    @Test
    fun `paths from tool output map back to windows`() {
        assertEquals(Path.of("""\\wsl.localhost\Ubuntu\home\u\app\A.php"""), plan().fromTarget("/home/u/app/A.php"))
    }
}

class RunModeTest {
    @Test
    fun `old docker flag migrates to docker mode`() {
        val state = ToolState().apply { useDocker = true }

        assertEquals(RunMode.DOCKER, state.effectiveRunMode())
    }

    @Test
    fun `explicit mode wins and keeps the old flag consistent`() {
        val state = ToolState().apply { useDocker = true }
        state.applyRunMode(RunMode.WSL)

        assertEquals(RunMode.WSL, state.effectiveRunMode())
        assertFalse(state.useDocker)
        state.applyRunMode(RunMode.DOCKER)
        assertTrue(state.useDocker)
        assertEquals(RunMode.LOCAL, ToolState().effectiveRunMode())
    }

    @Test
    fun `wsl suggestion only on windows for wsl projects in local mode`() {
        assertTrue(WslSuggestion.shouldSuggest(true, "//wsl$/Ubuntu/home/u/app", RunMode.LOCAL, dontAsk = false))
        assertFalse(WslSuggestion.shouldSuggest(false, "//wsl$/Ubuntu/home/u/app", RunMode.LOCAL, dontAsk = false))
        assertFalse(WslSuggestion.shouldSuggest(true, """C:\work\app""", RunMode.LOCAL, dontAsk = false))
        assertFalse(WslSuggestion.shouldSuggest(true, "//wsl$/Ubuntu/home/u/app", RunMode.DOCKER, dontAsk = false))
        assertFalse(WslSuggestion.shouldSuggest(true, "//wsl$/Ubuntu/home/u/app", RunMode.LOCAL, dontAsk = true))
    }
}
