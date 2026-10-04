package dev.phptools.core.docker

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** Bind-монтирование сервиса: [source] — абсолютный путь на хосте, [target] — путь в контейнере. */
data class Bind(val source: String, val target: String)

data class ComposeService(
    val name: String,
    val image: String?,
    /** `build.context` или `build.dockerfile` — для поиска признаков PHP. */
    val build: String?,
    val workingDir: String?,
    val binds: List<Bind>,
)

/** Кандидат для Docker-режима. */
data class DockerGuess(
    val service: String,
    val containerProjectPath: String,
    val running: Boolean,
    val score: Int,
)

/**
 * Определение Docker Compose из проекта. Чистая логика без платформы.
 * Источник — `docker compose config --format json` (нормализованная конфигурация); запасной — YAML compose-файла.
 */
object ComposeDetection {
    /** Стандартные имена compose-файлов: их не нужно передавать через `-f`. */
    val STANDARD_FILES = listOf("compose.yaml", "compose.yml", "docker-compose.yml", "docker-compose.yaml")

    private val PHP_HINTS = listOf("php", "sail", "laravel", "fpm", "symfony", "bitrix", "wordpress", "frankenphp", "roadrunner")
    private val NON_PHP_HINTS = listOf(
        "mysql", "mariadb", "postgres", "redis", "nginx", "node", "mailpit", "mailhog", "meilisearch", "selenium",
        "memcached", "rabbitmq", "elasticsearch", "opensearch", "minio", "soketi", "typesense", "valkey", "clickhouse",
    )
    private val LIKELY_NAMES = setOf("app", "php", "php-fpm", "php-cli", "workspace", "web", "backend")

    /** Разбор `docker compose config --format json`. */
    fun parseConfigJson(json: String): List<ComposeService> {
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = try {
            JsonParser.parseString(json.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return emptyList()
        val services = root.obj("services") ?: return emptyList()
        return services.entrySet().mapNotNull { (name, value) ->
            val s = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val binds = s.get("volumes").array().mapNotNull { v ->
                val o = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                if (o.string("type") != "bind") return@mapNotNull null
                Bind(o.string("source") ?: return@mapNotNull null, o.string("target") ?: return@mapNotNull null)
            }
            val build = s.get("build")?.let { b ->
                when {
                    b.isJsonPrimitive -> b.asString
                    b.isJsonObject -> listOfNotNull(b.asJsonObject.string("context"), b.asJsonObject.string("dockerfile")).joinToString(" ")
                    else -> null
                }
            }
            ComposeService(name, s.string("image"), build, s.string("working_dir"), binds)
        }
    }

    /**
     * Запасной разбор compose-файла (SnakeYAML из платформы, безопасный конструктор).
     * Понимает короткий (`./:/var/www/html`, `.:/var/www/html:cached`) и длинный синтаксис `volumes`;
     * относительные источники — от папки compose-файла [composeDir]. Интерполяцию переменных не делает.
     */
    fun parseYaml(text: String, composeDir: String): List<ComposeService> {
        val root = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *>
        } catch (_: YAMLException) {
            null
        } ?: return emptyList()
        val services = root["services"] as? Map<*, *> ?: return emptyList()
        return services.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val s = value as? Map<*, *> ?: return@mapNotNull null
            val binds = (s["volumes"] as? List<*>).orEmpty().mapNotNull { v -> parseYamlVolume(v, composeDir) }
            val build = when (val b = s["build"]) {
                is String -> b
                is Map<*, *> -> listOfNotNull(b["context"] as? String, b["dockerfile"] as? String).joinToString(" ")
                else -> null
            }
            ComposeService(name, s["image"] as? String, build, s["working_dir"] as? String, binds)
        }
    }

    private fun parseYamlVolume(volume: Any?, composeDir: String): Bind? = when (volume) {
        is String -> {
            // source:target[:mode]; на Windows source может начинаться с `C:`.
            val parts = Regex("""^((?:[A-Za-z]:)?[^:]+):([^:]+)(?::.*)?$""").find(volume)?.groupValues
            parts?.let { p ->
                val source = p[1]
                if (!isHostPath(source)) null else Bind(resolve(composeDir, source), p[2])
            }
        }
        is Map<*, *> -> {
            val type = volume["type"] as? String
            val source = volume["source"] as? String
            val target = volume["target"] as? String
            if ((type == null || type == "bind") && source != null && target != null && isHostPath(source)) Bind(resolve(composeDir, source), target) else null
        }
        else -> null
    }

    /** Именованный том (`dbdata:/var/lib/mysql`) — не путь на хосте. */
    private fun isHostPath(source: String) =
        source.startsWith(".") || source.startsWith("/") || source.startsWith("~") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(source)

    private fun resolve(dir: String, source: String): String {
        if (source.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(source)) return normalize(source)
        val parts = ArrayDeque<String>()
        (normalize(dir) + "/" + source.replace('\\', '/')).split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        val drive = Regex("^[A-Za-z]:").find(normalize(dir))?.value
        return if (drive != null) parts.joinToString("/") else "/" + parts.joinToString("/")
    }

    /**
     * Кандидаты, отсортированные от лучшего: сервисы, в которые смонтирован корень проекта или его предок.
     * @param projectPath корень проекта на хосте (как в `source` монтирований).
     * @param running запущенные сервисы.
     */
    fun guess(services: List<ComposeService>, projectPath: String, running: Set<String>): List<DockerGuess> {
        val project = normalize(projectPath)
        return services.mapNotNull { service ->
            val containerPath = service.binds.firstNotNullOfOrNull { containerPathFor(it, project) } ?: return@mapNotNull null
            var score = 0
            if (service.workingDir != null && normalize(service.workingDir) == normalize(containerPath)) score += 4
            val hints = listOfNotNull(service.image, service.build).joinToString(" ").lowercase()
            if (PHP_HINTS.any { it in hints }) score += 3
            if (NON_PHP_HINTS.any { it in hints } && PHP_HINTS.none { it in hints }) score -= 3
            if (service.name in running) score += 2
            if (service.name == "laravel.test") score += 2 else if (service.name.lowercase() in LIKELY_NAMES) score += 1
            DockerGuess(service.name, containerPath, service.name in running, score)
        }.sortedWith(compareByDescending<DockerGuess> { it.score }.thenBy { it.service })
    }

    /** Однозначный ли лидер: единственный кандидат или отрыв от второго хотя бы на 2 очка. */
    fun isConfident(guesses: List<DockerGuess>): Boolean =
        guesses.size == 1 || (guesses.size > 1 && guesses[0].score - guesses[1].score >= 2)

    /** Путь проекта в контейнере, если [bind] монтирует корень проекта или его предка. */
    fun containerPathFor(bind: Bind, projectPath: String): String? {
        val source = normalize(bind.source)
        val project = normalize(projectPath)
        val target = bind.target.trimEnd('/').ifEmpty { "/" }
        return when {
            same(source, project) -> target
            startsWith(project, "$source/") -> {
                val rest = project.substring(source.length + 1)
                if (target == "/") "/$rest" else "$target/$rest"
            }
            else -> null
        }
    }

    /**
     * `docker compose` с `-f` для файлов из метки `com.docker.compose.project.config_files`.
     * Если все файлы — стандартные имена в корне проекта, `-f` не нужен.
     */
    fun composeCommand(configFiles: List<String>, projectPath: String): String {
        val project = normalize(projectPath)
        val files = configFiles.map(::normalize).filter { it.isNotBlank() }
        // Стандартный — только файл со стандартным именем в корне проекта: compose запускается оттуда и найдёт его сам.
        val standard = files.all { f ->
            same(f.substringBeforeLast('/', ""), project) && f.substringAfterLast('/').let { it in STANDARD_FILES || isOverride(it) }
        }
        if (files.isEmpty() || standard) return "docker compose"
        return "docker compose " + files.joinToString(" ") { f ->
            val shown = if (startsWith(f, "$project/")) f.substring(project.length + 1) else f
            "-f " + if (shown.contains(' ')) "\"$shown\"" else shown
        }
    }

    private fun isOverride(name: String) = Regex("""^(docker-)?compose\.override\.ya?ml$""").matches(name)

    /** Предлагать ли Docker-режим при открытии проекта. */
    fun shouldOffer(isLocalMode: Boolean, toolRunsLocally: Boolean, dontAsk: Boolean, hasComposeFile: Boolean): Boolean =
        isLocalMode && !toolRunsLocally && !dontAsk && hasComposeFile

    /** Разбор строк `docker ps --format '{{.Label "…service"}}|{{.Label "…config_files"}}'`. */
    fun parseRunning(output: String): Pair<Set<String>, List<String>> {
        val services = mutableSetOf<String>()
        val files = linkedSetOf<String>()
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val service = line.substringBefore('|').trim()
            if (service.isNotEmpty()) services += service
            line.substringAfter('|', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(files::add)
        }
        return services to files.toList()
    }

    /** `\` → `/`, без завершающего `/`; регистр сохраняется (сравнение — в [same]/[startsWith]). */
    fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

    /** Пути Windows (с буквой диска) сравниваются без учёта регистра. */
    private fun windowsLike(path: String) = Regex("^[A-Za-z]:").containsMatchIn(path)

    private fun same(a: String, b: String) = if (windowsLike(a) || windowsLike(b)) a.equals(b, ignoreCase = true) else a == b

    private fun startsWith(path: String, prefix: String) =
        if (windowsLike(path) || windowsLike(prefix)) path.startsWith(prefix, ignoreCase = true) else path.startsWith(prefix)

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement?.array(): List<JsonElement> = this?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
}
