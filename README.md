# PHP Tools for OpenIDE

Плагины для **OpenIDE**: интеграция внешних PHP-инструментов (PHPStan, Psalm, PHP-CS-Fixer).

> **Плагин для OpenIDE. Работа в IDE JetBrains не поддерживается и не тестируется.**

## Структура

| Модуль | Что это |
|---|---|
| `core` | Общий код: поиск бинарника, запуск локально, в Docker Compose (с автоопределением) или в WSL, маппинг путей, режимы проверки, базовые настройки, уведомления, базовый `ExternalAnnotator`, боковая панель с отчётом. Отдельно не публикуется, вшивается в jar каждого плагина. |
| `phpstan` | Плагин `dev.phptools.phpstan`: ошибки PHPStan в редакторе, в том числе в несохранённом файле (`--tmp-file`), уровень из конфигурации, quick-fix `@phpstan-ignore`, ссылки на документацию ошибок, панель с изменёнными файлами и отчётом. Larastan подхватывается из конфигурации. |
| `psalm` | Плагин `dev.phptools.psalm`: проблемы Psalm с точными колонками, info-проблемы как слабые предупреждения, quick-fix `@psalm-suppress`, ссылки на psalm.dev, панель с отчётом. Проверяет сохранённые файлы. |
| `php-cs-fixer` | Плагин `dev.phptools.phpcsfixer`: подсветка кода, который поменяет PHP-CS-Fixer, с diff в подсказке (и для несохранённых файлов), исправление одного места или всего файла прямо из diff, «Исправить через PHP-CS-Fixer» для файлов и папок, панель с отчётом. |
| `phan` | Плагин `dev.phptools.phan`: проблемы Phan в редакторе при сохранении, работа без `php-ast` (polyfill-парсер), quick-fix `@phan-suppress-next-line`, ссылки на документацию проверок, панель с отчётом. |


Брифы лежат в `docs/briefs/`, заметки по API OpenIDE — в `docs/api-notes.md`.

## Требования

- **OpenIDE 2026.2** (build 262), установленная локально. Платформа берётся из неё, с серверов JetBrains ничего не скачивается.
- **JDK 25** (например, Axiom JDK 25 LTS). Платформа 262 скомпилирована под Java 25.

## Настройка

Путь к OpenIDE и JDK задаются в `~/.gradle/gradle.properties`, чтобы не коммитить личные пути:

```properties
# macOS: путь к бандлу .app; Linux/Windows: папка с bin/, lib/, plugins/
openidePath=/Applications/OpenIDE.app
# Если Gradle сам не находит JDK 25:
org.gradle.java.installations.paths=/path/to/jdk-25
```

Для запуска самого Gradle нужна Java 17+ в `JAVA_HOME` (подойдёт та же JDK 25):

```bash
export JAVA_HOME=/path/to/jdk-25
```

## Сборка и запуск

```bash
./gradlew build
```

```bash
./gradlew buildPlugin
```

`buildPlugin` собирает zip каждого плагина в `<модуль>/build/distributions/`, например `phpstan/build/distributions/phpstan-0.1.0.zip`. Классы `core` уже внутри jar плагина, отдельные зависимости не нужны.

```bash
./gradlew :phpstan:runIde
```

`runIde` запускает песочницу OpenIDE с плагином. Конфигурация и логи песочницы лежат в `.intellijPlatform/sandbox/` и не трогают основную установку.

PHP-плагин OpenIDE не входит в поставку IDE, поэтому в обычной песочнице PHP-файлов нет. Для ручной проверки есть задача `runIdeWithPhp`: она кладёт PHP-плагин только в песочницу, в зависимости сборки он не попадает. Путь к jar задаётся в `~/.gradle/gradle.properties`:

```properties
openphpPluginPath=/Users/<you>/Library/Application Support/OpenIDE/OpenIDE2026.2/plugins/php-for-openide/lib/php-for-openide-0.9.3.jar
```

Указывать нужно сам jar, а не папку `php-for-openide`: папку Gradle-плагин копирует без обёртки, и IDE её не загружает.

```bash
./gradlew :phpstan:runIdeWithPhp --args="$PWD/playground/builtin-check"
```

## Встроенные анализаторы PHP-плагина

У PHP-плагина OpenIDE есть своя интеграция PHPStan, Psalm и PHP-CS-Fixer. Если её инспекция включена, при открытии проекта наш плагин один раз предлагает её выключить, чтобы ошибки не подсвечивались дважды (`core/.../startup/BuiltInAnalyzerCheck.kt`). Выключается только инспекция в профиле проекта, вернуть её можно в Settings → Editor → Inspections.

## Где запускаются инструменты

В настройках каждого плагина (Settings → Tools → <инструмент> → «Запуск») три режима:

- **Локально** — `vendor/bin/<tool>` на этой машине.
- **Docker Compose** — `docker compose exec -T <сервис> …`; пути проекта переводятся в путь внутри контейнера.
  - **Автоопределение.** Кнопка «Определить из docker-compose» заполняет сервис, путь проекта в контейнере и команду compose (с `-f`, если проект поднят с нестандартными файлами). Источник — `docker compose config` и метки запущенных контейнеров; без Docker CLI compose-файл читается напрямую.
  - Если инструмент локально не запустится (нет бинарника или `php`), а в проекте есть compose-файл, при открытии проекта плагин предложит запускать его в контейнере. Ничего не включается без согласия; «Больше не спрашивать» — на проект.
- **WSL** (только когда OpenIDE запущена на Windows) — для проектов внутри WSL (`\\wsl$\Ubuntu\home\…` или `\\wsl.localhost\…`), когда PHP и Composer установлены в Linux. Плагин запускает `wsl.exe -d <дистрибутив> --cd <папка> --exec …` и переводит пути туда и обратно (`\\wsl.localhost\Ubuntu\home\u\app` ↔ `/home/u/app`, `C:\…` ↔ `/mnt/c/…`).
  - Дистрибутив: пусто — из пути проекта, иначе дистрибутив по умолчанию.
  - «Запускать через login shell» (`bash -lc`) — если `php` доступен только после профиля оболочки (asdf, phpenv и т.п.).
  - Если проект открыт из WSL, а инструмент запускается локально, при открытии проекта плагин предложит переключиться на WSL.
  - WSL-режим проверен юнит-тестами; живая проверка на Windows — по брифу 05.

## CI и релизы (GitHub Actions)

- **Build** (`.github/workflows/build.yml`) — на push в `develop` и на pull request (на `main` не нужна: туда попадает уже проверенное, а релиз собирает всё заново): сборка, тесты, `verifyPluginStructure`; zip всех плагинов — в артефактах запуска.
- **Release** (`.github/workflows/release.yml`) — на тег `vX.Y.Z`: то же самое с версией из тега и GitHub Release со всеми четырьмя zip. Монорепозиторий, поэтому релиз общий: один тег — все плагины одной версии. Тег с суффиксом (`v0.2.0-beta.1`) — пре-релиз.
- Платформа — настоящая OpenIDE: CI скачивает `https://download.openide.ru/<openideBuild>/openIDE-<openideBuild>.tar.gz` (сборка задаётся в `gradle.properties`, ~1,3 ГБ, кэшируется между запусками) и передаёт путь через `-PopenidePath`.

Выпустить релиз:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Установка в OpenIDE

Settings → Plugins → ⚙ → **Install Plugin from Disk…** → выбрать zip из `build/distributions/` → перезапустить IDE.

## Тесты

```bash
./gradlew :core:test
```

Юнит-тесты на JUnit 5 покрывают чистую логику: маппинг путей, поиск бинарника на временной структуре папок, сборку командной строки для local и Docker, перевод строк и колонок в диапазоны. Платформенный тест-фреймворк не используется, потому что тянет артефакты с серверов JetBrains. Чистая логика вынесена в классы без зависимостей от платформы (`BinaryResolver`, `LaunchPlan`, `ProblemRanges`).

## Публикация

Только в маркетплейс OpenIDE (marketplace.openide.ru). Публикация в JetBrains Marketplace не настраивается.

## Лицензия

[MIT](LICENSE) © 2026 Andrey Melentev. Иконка панели PHPStan — логотип проекта [PHPStan](https://github.com/phpstan/phpstan) (MIT, © Ondřej Mirtes, PHPStan s.r.o.).
