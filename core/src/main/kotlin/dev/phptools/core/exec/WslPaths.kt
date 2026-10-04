package dev.phptools.core.exec

/**
 * Перевод путей между Windows и WSL. Чистая логика на строках (на macOS/Linux `Path` не умеет UNC-пути,
 * поэтому тестируется на строках).
 *
 * - `\\wsl$\<distro>\home\u\app`, `\\wsl.localhost\<distro>\…` и те же пути с `/` (`//wsl$/…` — так часто
 *   хранит `project.basePath`) ↔ `/home/u/app`;
 * - диск Windows `C:\Users\u` ↔ `/mnt/c/Users/u`.
 */
object WslPaths {
    private val UNC = Regex("""^(?:\\\\|//)(wsl\$|wsl\.localhost)[\\/]([^\\/]+)(.*)$""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^([A-Za-z]):(?:[\\/](.*))?$""")
    private val MNT = Regex("""^/mnt/([a-zA-Z])(?:/(.*))?$""")

    data class UncPath(
        /** `wsl$` или `wsl.localhost` — как в исходном пути. */
        val host: String,
        val distribution: String,
        /** Путь внутри дистрибутива, всегда с ведущим `/`. */
        val linuxPath: String,
    )

    fun parseUnc(windowsPath: String): UncPath? {
        val m = UNC.find(windowsPath) ?: return null
        val rest = m.groupValues[3].replace('\\', '/').trimEnd('/')
        return UncPath(m.groupValues[1], m.groupValues[2], rest.ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" })
    }

    /** Имя дистрибутива из пути проекта или `null`, если путь не в WSL. */
    fun distributionOf(windowsPath: String): String? = parseUnc(windowsPath)?.distribution

    /**
     * Windows → Linux. [distribution] — ожидаемый дистрибутив: путь из другого дистрибутива не переводится.
     * `null` — путь не удаётся выразить внутри WSL.
     */
    fun toLinux(windowsPath: String, distribution: String?): String? {
        parseUnc(windowsPath)?.let { unc ->
            if (distribution != null && !unc.distribution.equals(distribution, ignoreCase = true)) return null
            return unc.linuxPath
        }
        DRIVE.find(windowsPath)?.let { m ->
            val rest = m.groupValues[2].replace('\\', '/').trimEnd('/')
            return "/mnt/${m.groupValues[1].lowercase()}" + if (rest.isEmpty()) "" else "/$rest"
        }
        return null
    }

    /**
     * Linux → Windows (вывод инструментов, отчёт). [uncRoot] — корень дистрибутива как в пути проекта,
     * например `\\wsl.localhost\Ubuntu`; `null` — переводятся только `/mnt/<диск>/…`.
     */
    fun toWindows(linuxPath: String, uncRoot: String?): String? {
        MNT.find(linuxPath)?.let { m ->
            val rest = m.groupValues[2].replace('/', '\\')
            return "${m.groupValues[1].uppercase()}:\\" + rest
        }
        if (!linuxPath.startsWith("/") || uncRoot == null) return null
        return uncRoot.trimEnd('\\', '/') + linuxPath.replace('/', '\\')
    }

    /** `\\<host>\<distro>` для [distribution], в стиле пути проекта (если он в WSL). */
    fun uncRoot(projectPath: String?, distribution: String): String {
        val host = projectPath?.let(::parseUnc)?.host ?: "wsl.localhost"
        return "\\\\$host\\$distribution"
    }
}

/** POSIX-экранирование аргументов для `bash -lc '<команда>'`. Чистая логика. */
object ShellQuote {
    private val SAFE = Regex("""^[A-Za-z0-9_@%+=:,./-]+$""")

    fun quote(arg: String): String =
        if (arg.isNotEmpty() && SAFE.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"

    fun join(args: List<String>): String = args.joinToString(" ", transform = ::quote)
}

/** Вывод `wsl.exe`. Чистая логика. */
object WslOutput {
    /**
     * Старые версии WSL игнорируют `WSL_UTF8` и пишут свои сообщения в UTF-16LE; прочитанные как UTF-8,
     * они превращаются в «т\u0000е\u0000к\u0000с\u0000т». Для ASCII достаточно убрать нули.
     */
    fun fixUtf16(text: String): String = if ('\u0000' in text) text.replace("\u0000", "") else text
}
