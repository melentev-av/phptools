package dev.phptools.phpcsfixer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

data class CsFixerFile(
    /** Путь от инструмента: относительный к рабочей папке, абсолютный или `php://stdin`. */
    val name: String,
    val diff: String,
    val fixers: List<String>,
)

sealed interface CsFixerResult {
    data class Ok(val files: List<CsFixerFile>) : CsFixerResult

    /** В файле синтаксическая ошибка (бит 4) — молча, синтаксис подсвечивает PHP-плагин. */
    data object SyntaxError : CsFixerResult

    data class Failed(val details: String) : CsFixerResult
}

/** Разбор вывода `php-cs-fixer fix --format=json -v`. Чистая логика без платформы. */
object CsFixerOutput {
    /** Биты кода выхода, означающие сбой: 1 общая ошибка, 16/32 конфигурация, 64 исключение. */
    private const val FAILURE_BITS = 1 or 16 or 32 or 64
    private const val SYNTAX_BIT = 4

    fun parse(exitCode: Int, stdout: String, stderr: String): CsFixerResult {
        if (exitCode < 0 || exitCode and FAILURE_BITS != 0) return CsFixerResult.Failed(stderr.ifBlank { stdout }.trim())
        if (exitCode and SYNTAX_BIT != 0) return CsFixerResult.SyntaxError

        val start = stdout.indexOf('{')
        val end = stdout.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return if (exitCode == 0) CsFixerResult.Ok(emptyList()) else CsFixerResult.Failed(stderr.ifBlank { stdout }.trim())
        }
        val json = try {
            JsonParser.parseString(stdout.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return CsFixerResult.Failed(stderr.ifBlank { stdout }.trim())

        val files = json.get("files")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            CsFixerFile(
                name = o.string("name") ?: return@mapNotNull null,
                diff = o.string("diff").orEmpty(),
                fixers = (o.get("appliedFixers") ?: o.get("fixers"))
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                    .orEmpty(),
            )
        }
        return CsFixerResult.Ok(files)
    }

    /** Вывод `list-files`: строки вида `'./app/Foo.php'` → `app/Foo.php`. */
    fun parseListFiles(stdout: String): Set<String> =
        stdout.lineSequence()
            .map { it.trim().removeSurrounding("'").removeSurrounding("\"").replace('\\', '/').removePrefix("./") }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()
}

/** Сборка аргументов PHP-CS-Fixer. */
object CsFixerCommand {
    /** Путь для чтения кода из stdin. */
    const val STDIN = "-"

    private fun common(config: String?, allowRisky: Boolean, extraArgs: List<String>): List<String> = buildList {
        addAll(listOf("--format=json", "-v", "--using-cache=no", "--show-progress=none", "--no-interaction", "--path-mode=intersection"))
        config?.takeIf { it.isNotBlank() }?.let { add("--config=$it") }
        if (allowRisky) add("--allow-risky=yes")
        addAll(extraArgs)
    }

    /** Проверка без изменений: `targets` — пути или [STDIN]; пустой список — файлы из `Finder` конфига. */
    fun dryRunArgs(targets: List<String>, config: String?, allowRisky: Boolean, extraArgs: List<String>): List<String> =
        listOf("fix", "--dry-run", "--diff") + common(config, allowRisky, extraArgs) + targets

    /** Реальное исправление файлов — только по явному действию пользователя. */
    fun fixArgs(targets: List<String>, config: String?, allowRisky: Boolean, extraArgs: List<String>): List<String> =
        listOf("fix") + common(config, allowRisky, extraArgs) + targets

    fun listFilesArgs(config: String?): List<String> = buildList {
        add("list-files")
        config?.takeIf { it.isNotBlank() }?.let { add("--config=$it") }
    }
}
