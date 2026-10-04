# Бриф 04 — Плагин Phan

> Предусловие: выполнены брифы 00, 01a и 01b (подсветка, режимы проверки, панель и отчёт в `core`). Структура плагина та же, что у PHPStan; большую часть можно взять оттуда. Работать в модуле `phan/` (добавить в `settings.gradle.kts`).

> **Целевая платформа — только OpenIDE.** Правила те же, что в брифе 00: API проверять по jar'ам OpenIDE и записывать в `docs/api-notes.md`, сетевых запросов не делать, от PHP-плагина (`ru.openide.openphp`) не зависеть.

## Цель

Плагин подсвечивает проблемы [Phan](https://github.com/phan/phan) в редакторе OpenIDE, даёт quick-fix `@phan-suppress-next-line` и подключает Phan к общей панели с отчётом (из брифа 01b). Работает локально и через Docker Compose.

Phan — статический анализатор того же класса, что PHPStan и Psalm. У PHP-плагина OpenIDE встроенной интеграции Phan нет, поэтому `BuiltInAnalyzerCheck` не нужен (`builtInInspectionShortName = null`).

## Что проверено на реальном Phan (6.0.7, PHP 8.5, без php-ast)

Перед реализацией перепроверить на установленной версии и записать в `docs/api-notes.md`.

| Факт | Следствие для плагина |
|---|---|
| Phan требует расширение **`php-ast`**. Без него: `ERROR: The php-ast extension must be loaded…`, работать можно только с `--allow-polyfill-parser` (заметно медленнее). В `php:8.5-cli` и в локальном PHP 8.5 расширения нет | Настройка `allowPolyfillParser` (по умолчанию **true**: с `php-ast` флаг ни на что не влияет). Текст про php-ast в stderr — понятное уведомление с подсказкой |
| Phan анализирует **всю программу**: даже для одного файла (`-I app/Foo.php`) парсит все `directory_list` из `.phan/config.php`. На маленьком Laravel без php-ast — **~11 с на файл** | По умолчанию `checkMode = ON_SAVE`; `timeoutSeconds` = **120**. В README прямо написать, что `php-ast` сильно ускоряет |
| Анализ одного файла: `-I <file>` (`--include-analysis-file-list`, через запятую или повторяя опцию) | Так же — несколько файлов в панели одним запуском |
| В CLI **нет** аналога `--tmp-file`: подмена файла временным (`temporary_file_mapping`) есть только в режиме демона (`--daemonize-tcp-port` + `phan_client`) и language server (`--language-server-on-stdin`) | `canAnalyzeUnsaved = false` в v0.1: режим «При вводе» работает как «При сохранении» (это уже делает `CheckPolicy`). Демон — кандидат на v0.2 (см. ниже) |
| Конфиг по умолчанию — `.phan/config.php` в рабочей папке; свой — `-k <file>` | `configPath` → `-k` |
| Вывод: `-m json` (`--output-mode`), прогресс выключается `--no-progress-bar` | См. «Запуск» |
| Коды выхода: **0** — проблем нет (`[]`), **1** — есть проблемы; ошибка конфига — тоже **1**, но stdout пустой, а в stderr `ERROR: …` со стеком | Код 0/1 с JSON — норма; 1 без JSON — сбой с текстом stderr |
| Файл вне `directory_list` (или в `exclude_analysis_directory_list`) — код 0, **stdout пустой** (даже не `[]`) | Пустой stdout при коде 0 — «проблем нет», молча |
| Пути в `location.path` — **относительные** от рабочей папки (`app/Broken.php`), в Docker тоже | Сопоставление по относительному пути от рабочей папки; для панели — `workDir.resolve(path)` |
| Колонок обычно нет: `lines: {begin, end}`; у некоторых проблем (например, `PhanSyntaxError`) есть `begin_column` | Колонка — если есть, иначе строка от первого непробельного символа |
| `description` начинается с категории и имени проверки: `"TypeError PhanTypeMismatchReturnReal Returning 'a' of type string…"` | Показывать текст без префикса `<Category> <check_name> ` |
| `severity`: 0 (low), 5 (normal), 10 (critical) | 0 → `weak = true` (не выше WEAK_WARNING); 5 и 10 — из профиля инспекции |
| Подавление: `// @phan-suppress-next-line PhanA, PhanB` (несколько через запятую — проверено), также `-current-line`, `-previous-line`, `@phan-file-suppress` | Quick-fix, см. ниже |
| Переменных AI-агентов (как у PHPStan 2.2) Phan не проверяет | `AgentEnvironment` из `core` всё равно применяется — вреда нет |

Пример JSON:

```json
[
  {"type":"issue","type_id":11013,"check_name":"PhanUndeclaredMethod",
   "description":"UndefError PhanUndeclaredMethod Call to undeclared method \\App\\Broken::nope",
   "severity":10,
   "location":{"path":"app/Broken.php","lines":{"begin":3,"end":3}}},
  {"type":"issue","type_id":17000,"check_name":"PhanSyntaxError",
   "description":"Syntax PhanSyntaxError Fallback parser diagnostic error: 'Name' expected.",
   "severity":10,
   "location":{"path":"app/Syntax.php","lines":{"begin":2,"end":2,"begin_column":26}}}
]
```

## Метаданные

- Plugin ID: `dev.phptools.phan`, имя: **Phan Integration** (for OpenIDE).
- `<depends>com.intellij.modules.platform</depends>`, `<depends>com.intellij.modules.vcs</depends>` (панель).
- Группа уведомлений: `phan`.
- Инспекция: shortName `PhanInspection`, displayName «Phan validation», groupName «PHP tools», `enabledByDefault="true"`, `level="WARNING"`, без `language`. Класс: `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. Описание: `inspectionDescriptions/PhanInspection.html`.
- Аннотатор: `<externalAnnotator language="PHP" implementationClass="...PhanAnnotator"/>`.
- Полоса над редактором: наследник `FileProblemsBanner` (на случай ошибок без строки).
- Настройки: `<projectConfigurable parentId="tools" id="dev.phptools.phan" displayName="Phan" .../>`.
- Панель: `<toolWindow id="Phan" anchor="left" .../>` (наследник `ToolPanelFactory`) и действие «Проверить Phan» в `ProjectViewPopupMenu` (наследник `CheckWithToolAction`).
- `RehighlightOnSave` в `<applicationListeners>`.
- `runIdeWithPhp` в `build.gradle.kts`, как у PHPStan.

## Настройки (поверх `ToolState`)

| Поле | Тип | По умолчанию | Аргумент CLI |
|---|---|---|---|
| `allowPolyfillParser` | Boolean | `true` | `--allow-polyfill-parser` |
| `processes` | Int | `1` | `-j <n>` (только для панели: в редакторе всегда 1) |
| `memoryLimit` | String | `""` | `--memory-limit <v>` (пусто — не передавать) |

`timeoutSeconds` по умолчанию **120**, `checkMode` по умолчанию **`ON_SAVE`** (поле в `ToolState` общее; дать наследнику способ задать свой default, как с таймаутом).

## Запуск

Редактор (один файл):

```
<phan>
    -m json
    --no-progress-bar
    [--allow-polyfill-parser]
    [--memory-limit <memoryLimit>]
    [-k <toTarget(configPath)>]
    <extraArgs...>
    -I <relative(file)>
```

- Рабочая папка — владелец `vendor` (из `core`): там же лежит `.phan/`.
- Путь в `-I` — **относительно рабочей папки** (так же Phan пишет `location.path`); в Docker это одинаково, маппинг не нужен.
- Панель: `-I a.php,b.php,…` одной опцией (разбивать на пачки через `CommandChunks`), `-j <processes>`; «Проверить проект» — без `-I` (анализ всего `directory_list`).

## Разбор вывода

- Код 0 или 1, stdout — JSON-массив с первого `[`; пустой stdout при коде 0 — проблем нет.
- Код 1 и JSON нет — сбой: `notifyFailure` с первыми строками stderr. Отдельно распознать «php-ast extension must be loaded» → подсказка включить `allowPolyfillParser` или поставить `php-ast`.
- Прочие коды — сбой.
- Для редактора — записи, где `location.path` совпадает с относительным путём файла (сравнивать с нормализованными `/`); если запись не совпала, но файл один — брать все.
- Текст: `description` без префикса `<Category> <check_name> `; идентификатор — `check_name`.
- Строки: `lines.begin`/`lines.end`; колонка — `lines.begin_column`, если есть.
- Парсер — чистая функция без платформы (Gson из платформы допустим).

## Отображение

- Сообщение: `Phan: <текст>`.
- Tooltip (HTML, экранировать): текст; `<code>check_name</code>`; severity словами (low / normal / critical).
- Ссылки на документацию: у Phan нет стабильной страницы на каждую проверку. Использовать вики со списком проблем (`https://github.com/phan/phan/wiki/Issue-Types-Caught-by-Phan`) — **перед реализацией проверить**, что страница существует и есть ли якоря на отдельные проверки; если нет — ссылку не показывать.
- `severity 0` → `weak = true`.
- В отчёте панели — те же поля через `ReportProblem` (`identifier = check_name`, `docUrl` — см. выше).

## Quick-fix: `PhanSuppressFix(line, checkName)`

- Текст: `Suppress '<check_name>' with @phan-suppress-next-line`, family: `Phan: suppress issue`.
- Если предыдущая строка, без ведущих пробелов, начинается с `// @phan-suppress-next-line ` — дописать `, <check_name>` в конец списка проверок (если её там ещё нет).
- Иначе вставить над строкой проблемы `// @phan-suppress-next-line <check_name>` с отступом строки проблемы.
- Чистая функция по образцу `IgnoreComment` из PHPStan (можно обобщить в `core` с параметрами «префикс комментария» и «разделитель»).
- Работает и из отчёта панели (`ToolBatchAnalyzer.ignoreFix`).

## Режим демона (не для v0.1)

Phan умеет держать проект в памяти: `phan --daemonize-tcp-port <port>` и клиент `phan_client` с подменой файла (`temporary_file_mapping`). Это даёт быструю проверку **при вводе** и несохранённых файлов. В v0.1 не делать: нужен жизненный цикл фонового процесса (старт, порт, перезапуск при изменении конфига, остановка с проектом), а в Docker — проброс порта. Записать как план на v0.2 в README модуля.

## Тесты

- Парсер на фикстурах из реального Phan: `[]`; несколько проблем; `begin_column`; пустой stdout с кодом 0; код 1 без JSON (ошибка конфига); мусор перед JSON; сообщение про php-ast.
- Срезание префикса `description`.
- Сопоставление путей (относительные, `\` → `/`).
- Сборка аргументов: с/без polyfill, конфига, memory-limit; панель с несколькими файлами и `-j`; проект целиком.
- Quick-fix: новая строка, дописывание, дубликат не добавляется.

## Ручная проверка (в `playground/laravel`, через `runIdeWithPhp`)

В playground уже стоит `phan/phan` и есть `.phan/config.php` (`directory_list: app` + зависимости).

1. `ON_SAVE`: в `app/Broken.php` проблемы появляются после сохранения; в tooltip — `check_name`.
2. `ON_TYPING`: ведёт себя как «при сохранении» (Phan не умеет несохранённые файлы в CLI).
3. Quick-fix вставляет `// @phan-suppress-next-line PhanUndeclaredMethod`, после сохранения проблема уходит.
4. Синтаксическая ошибка — подсветка с колонкой.
5. Сломанный `.phan/config.php` — одно уведомление со stderr, не спам.
6. Без `--allow-polyfill-parser` (выключить настройку) — понятное уведомление про php-ast.
7. Панель Phan: проверка изменённых файлов и «Проверить проект», отчёт с переходами и quick-fix.
8. Docker-режим: «Проверить» показывает версию Phan из контейнера, подсветка и панель работают.

## Не делать

- Не запускать `phan --automatic-fix` и плагины, правящие код.
- Не трогать `.phan/config.php` и baseline пользователя (кроме явных действий пользователя — их в v0.1 нет).
- Не ставить `php-ast` за пользователя.
- Не поднимать демон Phan в v0.1.

## Критерии готовности

- [ ] Все пункты ручной проверки проходят.
- [ ] Юнит-тесты зелёные.
- [ ] Факты о Phan перепроверены на установленной версии и записаны в `docs/api-notes.md`.
- [ ] README модуля: требования (`php-ast` или polyfill, скорость), режимы проверки (почему по умолчанию «При сохранении»), настройки, Docker-пример, план на демон.
