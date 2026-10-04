package dev.phptools.phpstan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

data class PhpStanMessage(
    val message: String,
    /** `null` — ошибка уровня файла. */
    val line: Int?,
    val ignorable: Boolean,
    val identifier: String?,
    val tip: String?,
)

sealed interface PhpStanResult {
    /** Ключи [files] — пути на стороне инструмента (в Docker — пути контейнера). */
    data class Ok(val files: Map<String, List<PhpStanMessage>>, val errors: List<String>) : PhpStanResult

    /** Файлов для анализа нет (например, файл в `excludePaths`) — молча. */
    data object NoFiles : PhpStanResult

    /** Версия PHPStan не знает `--tmp-file` (до 2.1.17 / 1.12.27). */
    data object TmpFileUnsupported : PhpStanResult

    data class Failed(val details: String) : PhpStanResult
}

/** Разбор вывода `phpstan analyse --error-format=json`. Чистая логика без платформы. */
object PhpStanOutput {

    fun parse(exitCode: Int, stdout: String, stderr: String): PhpStanResult {
        if (stderr.contains("--tmp-file") && stderr.contains("does not exist")) return PhpStanResult.TmpFileUnsupported
        if (exitCode != 0 && exitCode != 1) return PhpStanResult.Failed(failureText(stdout, stderr))

        val json = extractJson(stdout)
        if (json == null) {
            if ("No files found to analyse" in stderr || "No files found to analyse" in stdout) return PhpStanResult.NoFiles
            return PhpStanResult.Failed(failureText(stdout, stderr))
        }
        return when {
            json.has("files") -> PhpStanResult.Ok(parseFiles(json.getAsJsonObjectOrNull("files")), stringList(json.get("errors")))
            // Формат PHPStan 2.2+ при запуске из AI-агента; плагин убирает переменные агента, но на всякий случай.
            json.has("error_details") -> PhpStanResult.Ok(parseAgentDetails(json.getAsJsonObjectOrNull("error_details")), stringList(json.get("errors")))
            else -> PhpStanResult.Failed(failureText(stdout, stderr))
        }
    }

    /**
     * Сообщения для одного файла. Пути не маппятся обратно из контейнера: берётся запись,
     * чей ключ оканчивается на [relativePath] (относительно корня проекта), а если запись одна — она.
     */
    fun messagesFor(files: Map<String, List<PhpStanMessage>>, relativePath: String): List<PhpStanMessage> {
        val suffix = relativePath.replace('\\', '/').trimStart('/')
        val match = files.entries.firstOrNull { (key, _) ->
            val normalized = key.replace('\\', '/')
            normalized == suffix || normalized.endsWith("/$suffix")
        }
        return match?.value ?: files.values.singleOrNull().orEmpty()
    }

    /** JSON с первой `{` до последней `}`: перед ним может быть мусор (warning'и PHP, Note). */
    private fun extractJson(stdout: String): JsonObject? {
        val start = stdout.indexOf('{')
        val end = stdout.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JsonParser.parseString(stdout.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun parseFiles(files: JsonObject?): Map<String, List<PhpStanMessage>> {
        files ?: return emptyMap()
        return files.entrySet().associate { (path, value) ->
            val messages = value.takeIf { it.isJsonObject }?.asJsonObject?.get("messages")
            path to messages.objects().mapNotNull { m ->
                val text = m.string("message") ?: return@mapNotNull null
                PhpStanMessage(text, m.int("line"), m.bool("ignorable") ?: false, m.string("identifier"), m.string("tip"))
            }
        }
    }

    private fun parseAgentDetails(details: JsonObject?): Map<String, List<PhpStanMessage>> {
        details ?: return emptyMap()
        return details.entrySet().associate { (path, value) ->
            path to value.objects().mapNotNull { m ->
                val text = m.string("message") ?: return@mapNotNull null
                // В этом формате нет ignorable и tip.
                PhpStanMessage(text, m.int("line"), ignorable = false, identifier = m.string("identifier"), tip = null)
            }
        }
    }

    private fun failureText(stdout: String, stderr: String): String = stderr.ifBlank { stdout }.trim()

    private fun stringList(element: JsonElement?): List<String> =
        element?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private fun JsonElement?.objects(): List<JsonObject> =
        this?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
