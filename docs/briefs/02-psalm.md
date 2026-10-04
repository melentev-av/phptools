# Бриф 02 — Плагин Psalm

> Предусловие: выполнены брифы 00, 01a и 01b (режимы проверки, полоса над редактором, панель и отчёт в `core`). Структура та же, что у PHPStan. Работать в модуле `psalm/`.

> **Целевая платформа — только OpenIDE.** Не PhpStorm и не другие IDE JetBrains: сборка, тесты, ручная проверка и публикация (marketplace.openide.ru) только под OpenIDE; никаких зависимостей от PHP-плагина JetBrains (`com.jetbrains.php`). Любое API платформы проверять по jar'ам и исходникам OpenIDE, а не по документации JetBrains, и записывать в `docs/api-notes.md`. Подробно — разделы «Целевая платформа» и «Как проверять API» в брифе 00.

## Цель

Плагин подсвечивает проблемы Psalm в редакторе OpenIDE с точными колонками, разделяет ошибки и info-проблемы по уровню, даёт quick-fix `@psalm-suppress` и подключает Psalm к общей панели с отчётом. Работает локально и через Docker Compose.

## Что проверено на реальном Psalm (6.19.1, PHP 8.5)

| Факт | Следствие для плагина |
|---|---|
| Коды выхода: **0** — проблем нет (`[]`), **2** — есть проблемы, **1** — ошибка конфига (stdout пустой, текст в stderr: `Problem parsing …`) | 0/2 с JSON — норма; остальное — сбой со stderr |
| Файл вне `projectFiles` — код 0, `[]` | Молча |
| JSON-массив; поля: `severity` (`error`/`info`), `line_from`/`line_to`, `column_from`/`column_to`, `type`, `message`, `file_name` (относительно корня конфига), `file_path` (абсолютный, в Docker — путь контейнера), `link` (`https://psalm.dev/022`), `selected_text`, `from`/`to`, `snippet`… | См. «Разбор вывода» |
| **`column_from`/`column_to` — байтовые**, а не символьные: в строке `$s = "Привет"; $this->nope($s);` `nope` начинается с 31-го символа, а Psalm отдаёт 37 (6 кириллических букв = +6 байт). Конец не включается | Колонки переводить из байтов UTF-8 в символы по тексту строки (в `core`, `ProblemRanges`). `from`/`to` тоже байтовые — не использовать |
| `info`-проблемы приходят только с `--show-info=true` | Настройка `showInfo`; info → `weak` |
| `@psalm-suppress A, B` — несколько типов **через запятую** работают; через пробел второй тип **не** подавляется (считается описанием); `@psalm-suppress` на отдельных строках многострочного докблока тоже работает | Quick-fix дописывает `, <type>` |
| Подавление действует на выражение, перед которым стоит докблок | Комментарий вставляется над строкой проблемы |
| Psalm при переменных AI-агентов (`CliUtils::runningUnderAiAgent`: `CLAUDECODE`, `CURSOR_AGENT`, `CLINE_ACTIVE`, `COPILOT_CLI`, `TRAE_AI_SHELL_ID`, `ANTIGRAVITY_AGENT`, `PI_CODING_AGENT`, `AGENT=goose` и др.) только выключает прогресс; формат JSON не меняется | Дополнить список в `core/AgentEnvironment` этими переменными |
| Аналога `--tmp-file` у Psalm CLI нет | `canAnalyzeUnsaved = false`: «При вводе» работает как «При сохранении» (`CheckPolicy` из `core`) |
| Один файл в маленьком Laravel — около 7 с с холодным кэшем | Таймаут по умолчанию 60 с |

## Метаданные

- Plugin ID: `dev.phptools.psalm`, имя: **Psalm Integration** (for OpenIDE).
- `<depends>com.intellij.modules.platform</depends>`, `<depends>com.intellij.modules.vcs</depends>` (панель).
- Группа уведомлений: `psalm`.
- Инспекция: shortName `PsalmInspection`, displayName «Psalm validation», groupName «PHP tools», `enabledByDefault="true"`, `level="WARNING"`, без `language`. Класс: `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. Описание: `inspectionDescriptions/PsalmInspection.html`.
- Аннотатор: `<externalAnnotator language="PHP" implementationClass="...PsalmAnnotator"/>`; полоса над редактором — наследник `FileProblemsBanner`.
- Настройки: `<projectConfigurable parentId="tools" id="dev.phptools.psalm" displayName="Psalm" .../>`.
- Панель: `<toolWindow id="Psalm" anchor="left" .../>` (наследник `ToolPanelFactory`) и «Проверить Psalm» в `ProjectViewPopupMenu`.
- Уже есть с брифа 00: `RehighlightOnSave`, `PsalmBuiltInCheck` (встроенный Psalm PHP-плагина: `PsalmExternalAnalyzer`), `PsalmTool.SPEC`.
- Иконка панели: своя (строки кода с красным подчёркиванием, светлая и тёмная SVG). Официальный логотип (`PsalmLogo.png` в репозитории, MIT) — широкая надпись на непрозрачном белом фоне, в 20 px не читается. Иконка плагина — своя.

## Настройки (поверх `ToolState`)

| Поле | Тип | По умолчанию | Аргумент CLI |
|---|---|---|---|
| `showInfo` | Boolean | `false` | `--show-info=true` / `--show-info=false` |
| `threads` | Int | `1` | `--threads=<n>` |

`timeoutSeconds` по умолчанию **60**. Режимы проверки — общие из `core` (`checkMode`).

## Запуск

```
<psalm>
    --output-format=json
    --no-progress
    --monochrome
    --threads=<threads>
    --show-info=<true|false>
    [-c <toTarget(configPath)>]
    <extraArgs...>
    <toTarget(file)>             # панель: несколько путей (CommandChunks), «Проверить проект» — без путей
```

Рабочая папка — владелец `vendor` (из `core`), там же `psalm.xml`.

## Разбор вывода

- stdout — JSON-массив с первого `[`; пустой stdout при коде 0 — ноль проблем.
- Редактор: записи, где `file_path` оканчивается на относительный путь файла (или `file_name` совпадает); если не совпало, но файл в выводе один — брать его.
- Панель: `file_path` → локальный путь через `PreparedTool.fromTarget`.
- Колонки — из байтов в символы (см. выше), диапазон `line_from:column_from` → `line_to:column_to`, с clamp к границам строк.
- `severity == "info"` → `weak = true`.
- Невалидный JSON или неожиданный код выхода → `notifyFailure` со stderr.
- Парсер — чистая функция без платформы.

## Отображение

- Сообщение: `Psalm: <message>`.
- Tooltip (HTML, экранировать): сообщение, затем `<code>type</code>` ссылкой на `link` (документация psalm.dev).
- Ошибки получают severity из профиля инспекции, info-проблемы — не выше `WEAK_WARNING`.
- Отчёт панели: `identifier = type`, `docUrl = link`.

## Quick-fix: `PsalmSuppressFix(line, type)`

- Текст: `Suppress '<type>' with @psalm-suppress`, family: `Psalm: suppress issue`.
- Если предыдущая строка, без пробелов, — однострочный докблок `/** @psalm-suppress X */` (или `X, Y`), дописать `, <type>` в конец списка; дубликат не добавлять.
- Иначе вставить над строкой проблемы `/** @psalm-suppress <type> */` с отступом строки проблемы.
- Многострочный докблок над функцией или классом в v0.1 **не трогать**: вставлять отдельную строку.
- `startInWriteAction() = true`; правка через `Document`. Работает и из отчёта панели.

## Тесты

- Парсер на фикстурах из реального Psalm: `[]`; error и info вперемешку; несколько файлов (берётся нужный); мусор перед JSON; пустой stdout с кодом 0; код 1 (ошибка конфига).
- Перевод колонок из байтов в символы: ASCII, кириллица перед проблемой, колонка за концом строки.
- Расчёт диапазона: многострочная проблема (`line_from != line_to`).
- Quick-fix как чистая функция: новая строка; дописывание к однострочному `@psalm-suppress`; дубликат.
- Сборка аргументов: `showInfo` true/false, с конфигом и без, несколько путей, проект целиком.

## Ручная проверка (в `playground/laravel`, через `runIdeWithPhp`)

В playground уже стоят `vimeo/psalm` и `psalm.xml` (`errorLevel=3`, `projectFiles: app`).

1. В сохранённом файле вызвать несуществующий метод: подсветка точно на имени метода, **в том числе если левее в строке есть кириллица**.
2. Начать печатать: подсветка Psalm не обновляется до сохранения; после Ctrl+S — обновляется.
3. Включить `showInfo`: появились weak warning'и (`MissingReturnType`, `MissingParamType`).
4. Quick-fix вставляет `/** @psalm-suppress UndefinedMethod */`, после сохранения проблема уходит; второй quick-fix на той же строке дописывает тип через запятую.
5. Ошибка в `psalm.xml`: одно уведомление со stderr, не спам.
6. Панель Psalm: изменённые файлы, «Проверить проект», отчёт со ссылками на psalm.dev и quick-fix.
7. Docker-режим: «Проверить» показывает версию Psalm из контейнера, подсветка и панель работают.

## Не делать

- Не запускать `psalm --alter`, `psalter` и другие автоисправления.
- Не трогать `psalm.xml` и baseline пользователя.
- Не ставить `--threads` больше 1 по умолчанию: на каждое сохранение это лишняя нагрузка.

## Критерии готовности

- [ ] Все пункты ручной проверки проходят.
- [ ] Юнит-тесты зелёные.
- [ ] README модуля: настройки, ограничение «анализ после сохранения», Docker-пример, панель.
