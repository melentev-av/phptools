package dev.phptools.phpstan

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/** Сборка аргументов `phpstan analyse`. Чистая логика без платформы. */
object PhpStanCommand {

    /**
     * @param config путь к конфигу на стороне инструмента (`toTarget`) или `null`.
     * @param level уровень для `--level`, только если в конфиге уровня нет; `null`/пусто — не передавать.
     * @param tmpFile путь к временному файлу (анализ несохранённого буфера) или `null`.
     * @param target путь к анализируемому файлу на стороне инструмента.
     */
    fun analyseArgs(
        target: String,
        memoryLimit: String?,
        config: String?,
        level: String?,
        extraArgs: List<String>,
        tmpFile: String? = null,
    ): List<String> = buildList {
        addAll(listOf("analyse", "--error-format=json", "--no-progress", "--no-interaction"))
        memoryLimit?.trim()?.takeIf { it.isNotEmpty() }?.let { add("--memory-limit=$it") }
        config?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-c", it)) }
        level?.trim()?.takeIf { it.isNotEmpty() }?.let { add("--level=$it") }
        addAll(extraArgs)
        if (tmpFile != null) {
            add("--tmp-file=$tmpFile")
            add("--instead-of=$target")
        }
        add(target)
    }

    fun dumpParametersArgs(config: String?): List<String> = buildList {
        addAll(listOf("dump-parameters", "--json", "--no-interaction"))
        config?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-c", it)) }
    }
}

sealed interface ConfigLevel {
    /** Уровень задан в конфиге (`0`–`10` или `max`). */
    data class Set(val level: String) : ConfigLevel

    /** В конфиге уровня нет: PHPStan без `--level` не запустится («No rules detected»). */
    data object NotSet : ConfigLevel

    /** Не удалось выяснить (PHPStan не найден, сломан конфиг и т.п.). */
    data class Unknown(val details: String) : ConfigLevel
}

/** Разбор вывода `phpstan dump-parameters --json`. */
object PhpStanConfigLevel {
    fun parse(exitCode: Int, stdout: String, stderr: String): ConfigLevel {
        if ("No rules detected" in stdout || "No rules detected" in stderr) return ConfigLevel.NotSet
        val start = stdout.indexOf('{')
        val end = stdout.lastIndexOf('}')
        if (exitCode != 0 || start < 0 || end <= start) return ConfigLevel.Unknown(stderr.ifBlank { stdout }.trim())
        val level = try {
            JsonParser.parseString(stdout.substring(start, end + 1)).asJsonObject.get("level")
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        if (level == null || level.isJsonNull) return ConfigLevel.NotSet
        return if (level.isJsonPrimitive) ConfigLevel.Set(level.asString) else ConfigLevel.Unknown(level.toString())
    }
}
