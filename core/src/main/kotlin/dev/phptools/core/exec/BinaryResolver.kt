package dev.phptools.core.exec

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Результат поиска бинарника. Чистая логика без платформы, чтобы тестировать на временных папках. */
sealed interface Resolution {
    /** Бинарник найден на хосте. [workDir] — папка-владелец `vendor` (или корень проекта). */
    data class Local(val executable: Path, val workDir: Path) : Resolution

    /** Docker/WSL: заданный путь локально не найден, считаем его путём внутри контейнера или дистрибутива. */
    data class InContainer(val executable: String, val workDir: Path) : Resolution

    /** Путь задан явно, но файла нет, а Docker выключен. Об этом нужно сказать пользователю. */
    data class ConfiguredMissing(val path: Path) : Resolution

    /** Путь не задан и `vendor/bin/<tool>` не найден: инструмент просто не установлен. */
    data object NotInstalled : Resolution
}

object BinaryResolver {

    /**
     * @param configured значение `ToolState.executable`; относительный путь резолвится от [projectBase].
     * @param contextDir папка анализируемого файла; поиск `vendor/bin` идёт от неё вверх до [projectBase].
     * @param useDocker инструмент выполняется не на хосте (Docker или WSL).
     * @param preferBat Windows, локальный режим, без PHP-интерпретатора: брать `<binary>.bat`, если он есть рядом.
     */
    fun resolve(
        projectBase: Path,
        configured: String,
        binaryName: String,
        contextDir: Path?,
        useDocker: Boolean,
        preferBat: Boolean,
    ): Resolution {
        val base = projectBase.toAbsolutePath().normalize()
        if (configured.isNotBlank()) {
            val path = try {
                Path.of(configured).let { if (it.isAbsolute) it else base.resolve(it) }.normalize()
            } catch (_: InvalidPathException) {
                null
            }
            return when {
                path != null && Files.isRegularFile(path) -> Resolution.Local(withBat(path, preferBat), workDirFor(path, base))
                useDocker -> Resolution.InContainer(configured, base)
                else -> Resolution.ConfiguredMissing(path ?: base.resolve(configured.trim()))
            }
        }

        for (dir in searchDirs(base, contextDir)) {
            val candidate = dir.resolve("vendor").resolve("bin").resolve(binaryName)
            if (Files.isRegularFile(candidate)) {
                return Resolution.Local(withBat(candidate, preferBat), dir)
            }
        }
        return Resolution.NotInstalled
    }

    /** Папки от [contextDir] вверх до [base] включительно. Если [contextDir] вне проекта — только [base]. */
    internal fun searchDirs(base: Path, contextDir: Path?): List<Path> {
        val start = contextDir?.toAbsolutePath()?.normalize()
        if (start == null || !start.startsWith(base)) return listOf(base)
        return generateSequence(start) { if (it == base) null else it.parent }.toList()
    }

    /** `.../<owner>/vendor/bin/<tool>` → `<owner>`, иначе корень проекта. */
    private fun workDirFor(executable: Path, base: Path): Path {
        val bin = executable.parent
        val vendor = bin?.parent
        if (bin?.fileName?.toString() == "bin" && vendor?.fileName?.toString() == "vendor") {
            vendor.parent?.let { return it }
        }
        return base
    }

    private fun withBat(executable: Path, preferBat: Boolean): Path {
        if (!preferBat) return executable
        val bat = executable.resolveSibling("${executable.fileName}.bat")
        return if (Files.isRegularFile(bat)) bat else executable
    }
}
