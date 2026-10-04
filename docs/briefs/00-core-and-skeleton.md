# Бриф 00 — Скелет монорепозитория и общий модуль `core`

> Выполняется **первым**. Брифы 01a и 01b (PHPStan), 02 (Psalm), 03 (PHP-CS-Fixer) опираются на то, что сделано здесь.

## Цель

Создать Gradle-монорепозиторий для трёх независимых плагинов IntelliJ Platform под **OpenIDE 2026.2 (build 262)** и общий модуль `core`. В нём живёт всё, что одинаково для любого внешнего PHP-инструмента: поиск бинарника, запуск процесса локально или в Docker Compose, маппинг путей, базовые настройки, уведомления об ошибках, базовый `ExternalAnnotator`.

`core` **не публикуется** отдельным плагином. Он вшивается в каждый из трёх плагинов при сборке, так что пользователь ставит один плагин без зависимостей.

## Целевая платформа: только OpenIDE (не JetBrains)

Плагины пишутся **для OpenIDE** и под её экосистему. Продукты JetBrains (PhpStorm, IntelliJ IDEA Ultimate/Community от JetBrains) **не являются целью**: под них ничего не делаем, не тестируем и не публикуем. Это правило распространяется на все брифы (00–03).

Что из этого следует:

- **Сборка и тесты — только против локальной OpenIDE** (`local(openidePath)`). Не подключать `intellijIdeaCommunity(...)`, `phpstorm(...)` и любые другие IDE JetBrains как целевую платформу, не скачивать платформу с серверов JetBrains.
- **Ручная проверка — только в OpenIDE** (`runIde` поднимает песочницу OpenIDE). Если что-то работает в IDEA от JetBrains, но не работает в OpenIDE, это баг. Обратное — не баг.
- **PHP-поддержка — только плагин OpenIDE** (`ru.openide.openphp`). Никаких зависимостей, классов, extension point'ов и ID из PHP-плагина JetBrains (`com.jetbrains.php`, `com.jetbrains.php.*`, `PhpStorm`-специфичные API). Не ориентироваться на его документацию и примеры как на источник истины.
- **Публикация — только в маркетплейс OpenIDE** (`marketplace.openide.ru`). Не настраивать `publishPlugin` на JetBrains Marketplace, не подписывать плагин сертификатом JetBrains Marketplace, не добавлять JetBrains-специфичные метаданные (`product-descriptor`, платные лицензии JetBrains и т.п.).
- **Совместимость** задаётся по номерам сборок OpenIDE (`sinceBuild = 262`). Номера сборок совпадают со схемой IntelliJ Platform, но целимся в версии OpenIDE.
- **API проверяется по OpenIDE, а не по документации JetBrains** — см. раздел «Как проверять API» ниже.
- **Нейминг:** в названиях и описаниях плагинов не использовать торговые марки JetBrains («for PhpStorm», «for IntelliJ» и т.п.). Формат: «PHPStan Integration for OpenIDE» или просто «PHPStan Integration».
- **README и описание в `plugin.xml`** прямо говорят: «Плагин для OpenIDE. Работа в IDE JetBrains не поддерживается и не тестируется».

## Как проверять API (источник правды — OpenIDE)

Отдельной документации по разработке плагинов у OpenIDE нет. Поэтому **любой класс, метод, extension point или ID, который используется в коде, проверяется по OpenIDE до того, как на него опираться**. Документация IntelliJ Platform SDK (plugins.jetbrains.com/docs) и примеры из интернета годятся только для понимания концепций и подсказки, что искать. Сигнатуры и наличие API из них не берутся.

Порядок источников:

1. **Jar-файлы установленной OpenIDE** — главный источник. Ровно с ними плагин компилируется и работает.
   - Точный номер сборки лежит в `<openidePath>/build.txt`.
   - Найти, в каком jar лежит класс:
     ```bash
     for j in "$OPENIDE"/lib/*.jar "$OPENIDE"/lib/modules/*.jar; do
       unzip -l "$j" 2>/dev/null | grep -q 'ExternalAnnotatorBatchInspection.class' && echo "$j"
     done
     ```
   - Посмотреть сигнатуры: `javap -cp <jar> <полное.имя.Класса>`.
   - Extension point'ы и ID плагинов: `unzip -p <jar> META-INF/plugin.xml` (и `META-INF/*.xml`). Для PHP-плагина OpenIDE это jar'ы в `<openidePath>/plugins/<папка-php-плагина>/lib/` или в папке пользовательских плагинов. Смотреть только `plugin.xml` и публичные интерфейсы, код не декомпилировать.
   - Самый удобный вариант: открыть проект плагина в OpenIDE. После `local(openidePath)` платформа подключена как зависимость, так что работают Go to Class и Go to Declaration по API, а Kotlin-компилятор сразу покажет несовпадения.
2. **Исходники OpenIDE** на GitFlic, той же ветки, что установленная сборка. Нужны, чтобы понять поведение, а не только сигнатуру:
   ```bash
   # ветку сопоставить с build.txt; на момент написания основная: releases/openide/262.10968.63
   git clone --depth 1 --branch releases/openide/262.10968.63 https://gitflic.ru/project/openide/openide.git openide-src
   ```
   Клонировать в отдельную папку **вне** репозитория плагинов. Клон большой, хватит `--depth 1`.
3. **Документация JetBrains SDK** — только для концепций (что такое ExternalAnnotator, как устроены сервисы и т.п.). Если она расходится с пунктами 1–2, правы пункты 1–2.

Правила:

- Перед использованием нового API проверить его наличие в jar'ах OpenIDE (п.1). Если API помечено `@Deprecated`, `@ApiStatus.Internal` или `@ApiStatus.Experimental`, искать альтернативу; если альтернативы нет, оставить комментарий в коде.
- Вести файл **`docs/api-notes.md`**: одна строка на каждое неочевидное API — класс или EP, в какой сборке OpenIDE проверено, где найдено (jar или файл исходников), и особенности, если есть. Этот файл понадобится при обновлении на следующую версию OpenIDE.
- Ответы на «Открытые вопросы» (ниже) получать именно этим способом и записывать в `docs/api-notes.md`.

## Контекст и ограничения (важно)

- Целевая IDE: OpenIDE (форк IntelliJ IDEA Community), установлена локально. Серверы JetBrains из России могут быть недоступны, поэтому **платформа берётся из локальной установки OpenIDE**, а не скачивается.
- PHP-поддержку в OpenIDE даёт плагин `ru.openide.openphp` (закрытый исходный код). В v0.1 **не зависеть от него в коде**: плагины работают на одном API платформы, а PHP-плагин нужен только ради языка `PHP`, к которому привязан аннотатор. Его публичное API — кандидат на будущее, см. «Известное API PHP-плагина OpenIDE».
- Язык: **Kotlin**. Сборка: **Gradle (Kotlin DSL) + IntelliJ Platform Gradle Plugin 2.x**.
- Kotlin stdlib не бандлить: `kotlin.stdlib.default.dependency=false`.
- Для JSON использовать **Gson**, он есть в платформе. Сторонних библиотек не добавлять.
- Никакой телеметрии и никаких сетевых запросов из плагинов.

## Структура

```
php-tools/
├── settings.gradle.kts          # include(":core", ":phpstan", ":psalm", ":php-cs-fixer")
├── build.gradle.kts             # общее (версия Kotlin, jvmToolchain)
├── gradle.properties            # см. ниже
├── gradlew, gradlew.bat, gradle/wrapper/*
├── README.md
├── core/
│   ├── build.gradle.kts         # plugin: org.jetbrains.intellij.platform.module
│   └── src/main/kotlin/dev/phptools/core/...
├── phpstan/                     # брифы 01a, 01b
├── psalm/                       # бриф 02
└── php-cs-fixer/                # бриф 03
```

Корневой пакет: `dev.phptools` (`dev.phptools.core`, `dev.phptools.phpstan`, ...). Plugin ID: `dev.phptools.phpstan` и т.д. Позже может переименоваться, поэтому вынести group/vendor в `gradle.properties`.

### gradle.properties

```properties
pluginGroup=dev.phptools
pluginVersion=0.1.0
pluginVendor=PHP Tools
platformSinceBuild=262
# Путь к установленной OpenIDE (папка с bin/, lib/, plugins/). Каждый разработчик задаёт свой,
# лучше в ~/.gradle/gradle.properties, чтобы не коммитить.
openidePath=/path/to/OpenIDE
javaVersion=25
kotlin.stdlib.default.dependency=false
org.gradle.configuration-cache=true
```

### Сборка против локальной OpenIDE

Каждый плагин-модуль (`phpstan`, `psalm`, `php-cs-fixer`) применяет `org.jetbrains.intellij.platform`, а `core` применяет `org.jetbrains.intellij.platform.module`.

```kotlin
repositories {
    mavenCentral()
    intellijPlatform {
        localPlatformArtifacts()
        // defaultRepositories() — включать, только если доступны серверы JetBrains
    }
}
dependencies {
    intellijPlatform {
        local(providers.gradleProperty("openidePath"))
    }
    implementation(project(":core"))   // только в плагин-модулях
}
intellijPlatform {
    instrumentCode = false             // форм нет; иначе нужны артефакты с серверов JetBrains
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            untilBuild = "262.*"             // как у других плагинов под OpenIDE 2026.2; расширять после проверки на новой версии
        }
    }
}
```

**Критерий:** `./gradlew :phpstan:buildPlugin` собирает zip, внутри которого есть jar с классами `core`. `./gradlew :phpstan:runIde` запускает песочницу OpenIDE с плагином.

**Платформа 262 скомпилирована под Java 25** (bytecode major 69), поэтому JDK для сборки и `jvmToolchain` — **25** (Axiom JDK 25 LTS). JDK 21 не подойдёт, JDK 26 брать не нужно.

### Проверенный референс: связка версий

Взята из манифеста реального стороннего плагина под OpenIDE 2026.2 (Testo, `com.github.xepozz.testo`, версия 2026.16.262), который собран и работает с PHP-плагином OpenIDE:

| Что | Версия |
|---|---|
| Gradle | 9.8.0 |
| IntelliJ Platform Gradle Plugin | 2.19.0 |
| Kotlin | 2.4.20 |
| JDK сборки | 25 (BellSoft/Axiom 25.0.3 LTS) |
| Платформа | OpenIDE 2026.2.1, build 262.9437.185.3 |
| `since-build` / `until-build` | `262` / `262.*` |
| Kotlin stdlib в плагине | не бандлится |

Начинать с этих версий. Обновлять только осознанно, проверяя совместимость.

## Состав `core`

### 1. Базовое состояние настроек — `ToolState : BaseState`

`open class`, общие поля для всех инструментов:

| Поле | Тип | По умолчанию | Смысл |
|---|---|---|---|
| `executable` | String | `""` | Путь к бинарнику. Пусто — автопоиск `vendor/bin/<tool>` |
| `configPath` | String | `""` | Путь к конфигу. Пусто — инструмент ищет сам |
| `phpInterpreter` | String | `""` | Если задан — запуск как `<php> <executable>`. В Docker это команда внутри контейнера (например `php`) |
| `useDocker` | Boolean | `false` | Запуск через Docker Compose |
| `composeCommand` | String | `"docker compose"` | Можно `docker compose -f docker-compose.dev.yml` |
| `composeService` | String | `""` | Имя сервиса, например `app` или `laravel.test` (Sail) |
| `containerProjectPath` | String | `"/var/www/html"` | Куда смонтирован корень проекта в контейнере |
| `extraArgs` | String | `""` | Доп. аргументы; парсить через `ParametersListUtil.parse` |
| `timeoutSeconds` | Int | `30` | Таймаут одного запуска |

Наследники в плагинах добавляют свои поля. Хранение — в light-сервисе уровня проекта: `@Service(PROJECT) @State(storages = [Storage("<tool>-tools.xml")]) class X : SimplePersistentStateComponent<S>`. Файл лежит в `.idea/`, так что настройки можно коммитить и делиться ими с командой.

### 2. `ToolSpec`

```kotlin
data class ToolSpec(
    val id: String,              // "phpstan" — id группы уведомлений и ключ кэшей
    val displayName: String,     // "PHPStan"
    val binaryName: String,      // "phpstan" (ищется vendor/bin/phpstan)
    val versionArgs: List<String> = listOf("--version"),
    val configurableId: String,  // id из plugin.xml, чтобы открыть настройки из уведомления
)
```

### 3. Поиск бинарника — `ToolLocator.prepare(project, spec, state, contextFile?): PreparedTool?`

- Если `executable` задан: относительный путь резолвится от `project.basePath`. Если локально файла нет и `useDocker == false`, вернуть `null` и показать уведомление «бинарник не найден» (с троттлингом, см. п.6). Если `useDocker == true` и локально файла нет, считать это путём внутри контейнера и передать как есть.
- Если не задан: идти от папки `contextFile` вверх до `project.basePath` и искать `vendor/bin/<binaryName>`. Первая найденная папка-владелец `vendor` становится **рабочей директорией**. Это поддерживает проекты, где `composer.json` лежит в подпапке. Если ничего не найдено, вернуть `null` **молча**: инструмент просто не установлен в проекте.
- Windows, локальный режим, `phpInterpreter` пуст: если рядом есть `<binary>.bat`, использовать его.

### 4. Запуск — `PreparedTool`

```kotlin
class PreparedTool(...) {
    fun toTarget(localPath: Path): String          // путь, понятный инструменту
    fun run(args: List<String>, stdin: String? = null): ToolOutput
    fun runWithTempFile(content: String, ext: String, args: (tmpPath: String) -> List<String>): ToolOutput
}
data class ToolOutput(val exitCode: Int, val stdout: String, val stderr: String,
                      val timedOut: Boolean, val cancelled: Boolean)
```

**Локально:** `[php?] <exe> args...`, cwd = рабочая директория. `toTarget` возвращает абсолютный локальный путь.

**Docker Compose:** `<composeCommand> exec -T -w <containerWorkDir> <service> [php?] <exe> args...`, где локальный cwd = `project.basePath`, чтобы compose нашёл свой файл. `toTarget(path)` заменяет префикс `project.basePath` на `containerProjectPath` и нормализует разделители в `/`. Путь вне проекта передаётся как есть.

**`runWithTempFile`** нужен для анализа несохранённого буфера:
- локально: временный файл через `FileUtil.createTempFile`, удалить после запуска;
- в Docker файл создаётся **внутри контейнера** из stdin, без записи в проект на хосте:
  ```
  ... exec -T -w <dir> <service> sh -c 't="$1"; shift; cat > "$t"; "$@"; c=$?; rm -f "$t"; exit $c' sh /tmp/phptools-<uuid>.<ext> <exe> args...
  ```

Технические требования к запуску:
- `GeneralCommandLine` с UTF-8 и `ParentEnvironmentType.CONSOLE`, `CapturingProcessHandler`.
- Ждать через `runProcessWithProgressIndicator(indicator, timeoutMs)`, где indicator — текущий (`ProgressManager.getInstance().progressIndicator`) или `EmptyProgressIndicator`. Отмена индикатора должна убивать процесс.
- stdin писать из пул-потока, потом закрывать, чтобы не было дедлока на больших файлах.
- Никогда не вызывать на EDT.

### 5. Базовый аннотатор — `ToolExternalAnnotator : ExternalAnnotator<AnnotationInput, AnnotationResult>`

```kotlin
data class AnnotationInput(val project: Project, val file: VirtualFile, val path: Path,
                           val text: String, val unsaved: Boolean, val stamp: Long)
data class ToolProblem(
    val line: Int,                 // 1-based
    val endLine: Int = line,
    val column: Int? = null,       // 1-based, null = подсветить строку без ведущих пробелов
    val endColumn: Int? = null,
    val message: String,
    val tooltipHtml: String? = null,
    val weak: Boolean = false,     // info-уровень → не выше WEAK_WARNING
    val fixes: List<IntentionAction> = emptyList(),
)
data class AnnotationResult(val problems: List<ToolProblem>, val stamp: Long)
```

Абстрактные члены для наследников: `spec`, `state(project)`, `canAnalyzeUnsaved(project): Boolean` (по умолчанию `false`), `analyze(input, tool): List<ToolProblem>?`, `pairedInspectionShortName`.

Логика:
- **`collectInformation(file, editor, hasErrors)`** и **`collectInformation(file)`** (batch) ведут в одну функцию; `hasErrors` **игнорировать**, потому что PHP-плагин в бете и может давать ложные ошибки. Отсеять: файл не `.php`, не в локальной ФС, не в content-root проекта, в библиотеке или excluded, путь содержит `/vendor/`.
- Документ несохранён и `canAnalyzeUnsaved == false`: добавить файл в `PendingRehighlight` и вернуть `null`.
- **`doAnnotate`**: `ToolLocator.prepare(...)`, затем `analyze(...)`, затем `AnnotationResult(problems, input.stamp)`.
- **`apply`**: если `document.modificationStamp != result.stamp`, ничего не делать (результат устарел). Иначе для каждой проблемы:
  - диапазон: строка с clamp к границам документа; если колонок нет, то от первого непробельного символа до конца строки; пустой диапазон расширить на всю строку;
  - severity берётся **из профиля инспекций** по `pairedInspectionShortName` (`InspectionProjectProfileManager` → `getErrorLevel(HighlightDisplayKey.find(name), file).severity`); для `weak = true` не выше `WEAK_WARNING`;
  - `holder.newAnnotation(severity, message).range(...).tooltip(...).withFix(...).create()`.
- `getPairedBatchInspectionShortName()` возвращает `pairedInspectionShortName`. Тогда инструмент включается и выключается в Settings → Editor → Inspections, а «Code → Inspect Code» тоже его запускает.

### 6. Уведомления — `ToolNotifier`

- `notifyFailure(project, spec, title, details)` — balloon в группе `spec.id`, с действием «Открыть настройки» (`ShowSettingsUtil.showSettingsDialog(project, configurableId)` или по классу).
- **Троттлинг:** одно и то же сообщение для пары (проект, инструмент) показывается один раз за сессию. Сбрасывается при Apply в настройках инструмента.
- В `details` вставлять первые ~20 строк stderr: это главное, что поможет пользователю починить конфиг.

### 7. Перезапуск подсветки после сохранения — `RehighlightOnSave : FileDocumentManagerListener`

`afterDocumentSaved`: если файл есть в `PendingRehighlight`, убрать его оттуда и вызвать `DaemonCodeAnalyzer.getInstance(project).restart(psiFile)` для каждого открытого проекта, где этот файл в контенте. Регистрирует каждый плагин в своём `plugin.xml` (`<applicationListeners>`). У каждого плагина свой classloader, так что копии `core` не конфликтуют.

### 8. Базовый экран настроек — `ToolConfigurable<S : ToolState> : BoundConfigurable`

- UI на **Kotlin UI DSL v2** (`panel { }`).
- Работать с **рабочей копией** состояния: `reset()` копирует сохранённое в копию (`copyFrom`) и потом вызывает `super.reset()`; `apply()` вызывает `super.apply()`, копирует обратно и сбрасывает троттлинг уведомлений и кэши инструмента; `isModified() = super.isModified() || ui != stored`.
- Группа «Запуск»: путь к бинарнику (поле с выбором файла), путь к конфигу, PHP-интерпретатор, extra args, таймаут, чекбокс «Запускать в Docker Compose», по которому видимы поля compose command, service, путь в контейнере. Для видимости сделать свой `ComponentPredicate` по чекбоксу, не полагаясь на хелперы из `com.intellij.ui.layout`.
- Кнопка **«Проверить»**: применяет панель к рабочей копии, в фоне с прогрессом (`runProcessWithProgressSynchronously`) запускает `<exe> --version` и показывает версию или stderr. Это главный способ отладить Docker-настройку.
- Абстрактный метод `Panel.toolSpecificSettings(state: S)` для полей конкретного инструмента.
- Регистрация в каждом плагине: `<projectConfigurable parentId="tools" ...>`.

## Тесты

- Модульные тесты на чистую логику (маппинг путей, поиск бинарника на временной структуре папок, сборка командной строки для local и docker) — **JUnit 5**, без платформенного тест-фреймворка (он тянет артефакты с серверов JetBrains).
- Если JUnit-тесты в модуле с платформенным Gradle-плагином не запускаются без `testFramework(...)`, вынести чистую логику в функции без зависимостей от платформы и тестировать их. Не тратить на это больше часа: зафиксировать проблему в README и идти дальше.

## Тестовый проект (для ручной проверки)

Создать рядом `playground/` (не в репозитории плагинов или в `.gitignore`): свежий Laravel-проект с `phpstan/phpstan` (или `larastan/larastan`), `vimeo/psalm`, `friendsofphp/php-cs-fixer` в `require-dev`, плюс `docker-compose.yml` с PHP-сервисом, чтобы проверить Docker-режим.

## Известное API PHP-плагина OpenIDE (на будущее, не для v0.1)

Из `plugin.xml` и ссылок на классы стороннего плагина (Testo) видно, что у `ru.openide.openphp` есть публичное API:

- namespace extension point'ов: `ru.openide.openphp` (например, `<testFrameworkType>`);
- запуск PHP-инструментов в интерпретаторе проекта (локально, Docker, Compose, WSL): пакет `ru.openide.openphp.settings.launch` — `PhpToolLaunch`, `PreparedPhpToolLaunch`, `PhpLaunchEnvironment`, `PhpLaunchLocality`, `PhpExposedPath`, `PhpToolOutcome`;
- PSI и символы: `ru.openide.openphp.lang.psi.elements.*`, `ru.openide.openphp.lang.psi.symbols.*` (`PhpIndex`, `PhpClassInfo` и т.д.);
- тесты: `ru.openide.openphp.run.testing.*`; отладка: `ru.openide.openphp.debugger.launch.PhpDebugLaunch`.

Подключение делается так же, как у Testo: необязательная зависимость в отдельном файле конфигурации, чтобы плагин работал и без PHP-API:
```xml
<depends optional="true" config-file="with-openphp.xml">ru.openide.openphp</depends>
```
**План на v0.2:** в `with-openphp.xml` зарегистрировать альтернативную реализацию запуска в `core` поверх `PhpToolLaunch`, чтобы интерпретатор и Docker брались из настроек PHP проекта, а наш раннер оставался запасным. Перед этим проверить сигнатуры по jar'ам PHP-плагина (`javap`) и уточнить у команды OpenIDE, что это API останется в бесплатной версии. Чужой код не декомпилировать.

## Открытые вопросы — проверить в первую очередь

1. ~~ID языка PHP~~ — **подтверждено: `PHP`**. Сторонний плагин под OpenIDE регистрирует `localInspection`, `runLineMarkerContributor` и `lineMarkerProvider` с `language="PHP"`. Plugin ID PHP-плагина: `ru.openide.openphp`.
2. **Пакет `ExternalAnnotatorBatchInspection`** в платформе 262: найти через Go to Class в OpenIDE.
3. Сигнатуры UI DSL (`textFieldWithBrowseButton`, `bindText` с getter/setter) — сверить с версией платформы, при необходимости адаптировать.
4. **Есть ли у OpenIDE свой product-модуль** (аналог `com.intellij.modules.platform`, но специфичный для OpenIDE), через который можно в `plugin.xml` ограничить установку только в OpenIDE. Поискать в `lib/` и `plugin.xml` ядра OpenIDE. Если есть, добавить `<depends>` на него. Если нет, ограничиться `com.intellij.modules.platform` и записать вывод в README.
5. **Требования маркетплейса OpenIDE к плагинам** (оферта для разработчиков на marketplace.openide.ru): формат zip, подпись, обязательные поля `plugin.xml`, лицензия. Учесть до первой публикации.

## Не делать

- В v0.1 не зависеть от `ru.openide.openphp` (ни обязательно, ни опционально); никогда не декомпилировать его и сторонние плагины.
- Не добавлять поддержку, сборку, тесты или публикацию под IDE JetBrains (PhpStorm, IntelliJ IDEA от JetBrains), не использовать API PHP-плагина JetBrains.
- Не писать во временные файлы внутри проекта на хосте.
- Не запускать процессы на EDT и не держать read action во время работы процесса.
- Не добавлять сторонние библиотеки.

## Критерии готовности

- [ ] `./gradlew build` проходит; `buildPlugin` для каждого плагин-модуля даёт zip с вшитым `core`.
- [ ] Юнит-тесты на маппинг путей и сборку команд зелёные.
- [ ] README: как указать `openidePath`, как запустить `runIde`, как собрать zip и поставить его в OpenIDE (Settings → Plugins → Install from Disk).
- [ ] Ответы на «Открытые вопросы» записаны в README и `docs/api-notes.md`.
- [ ] Каждое неочевидное API платформы, использованное в `core`, проверено по jar'ам OpenIDE и записано в `docs/api-notes.md` с номером сборки.
- [ ] В README и в `<description>` каждого `plugin.xml` явно указано, что плагин для OpenIDE; в сборке нет ссылок на IDE и маркетплейс JetBrains.
