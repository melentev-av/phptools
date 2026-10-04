# Бриф 03 — Плагин PHP-CS-Fixer

> Предусловие: выполнен бриф 00. Работать в модуле `php-cs-fixer/`. Этот плагин устроен иначе, чем PHPStan и Psalm: он не только подсвечивает нарушения, но и **исправляет** их.

> **Целевая платформа — только OpenIDE.** Не PhpStorm и не другие IDE JetBrains: сборка, тесты, ручная проверка и публикация (marketplace.openide.ru) только под OpenIDE; никаких зависимостей от PHP-плагина JetBrains (`com.jetbrains.php`). Любое API платформы проверять по jar'ам и исходникам OpenIDE, а не по документации JetBrains, и записывать в `docs/api-notes.md`. Подробно — разделы «Целевая платформа» и «Как проверять API» в брифе 00.

## Цель

1. Подсвечивать места, которые PHP-CS-Fixer поменял бы, с diff во всплывающей подсказке.
2. Quick-fix: применить исправление к одному фрагменту или ко всему файлу **прямо из diff**, без повторного запуска процесса.
3. Действие «Fix with PHP-CS-Fixer» для текущего файла, выделенных файлов и папок в дереве проекта (запускает реальный `fix`).

Всё работает локально и через Docker Compose. Laravel Pint в v0.1 не входит.

## Метаданные

- Plugin ID: `dev.phptools.phpcsfixer`, имя: **PHP-CS-Fixer Integration** (for OpenIDE)
- `<depends>com.intellij.modules.platform</depends>`
- Группа уведомлений: `php-cs-fixer`
- Инспекция: shortName `PhpCsFixerInspection`, displayName «PHP-CS-Fixer validation», groupName «PHP tools», `enabledByDefault="true"`, `level="WEAK WARNING"`, без `language`. Класс: `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. Описание: `inspectionDescriptions/PhpCsFixerInspection.html`.
- Аннотатор: `<externalAnnotator language="PHP" implementationClass="...PhpCsFixerAnnotator"/>`.
- Действие: id `PhpTools.PhpCsFixer.Fix`, текст «Fix with PHP-CS-Fixer». Добавить в группы `CodeMenu`, `EditorPopupMenu`, `ProjectViewPopupMenu`. Шорткат не назначать.
- Настройки: `<projectConfigurable parentId="tools" id="dev.phptools.phpcsfixer" displayName="PHP-CS-Fixer" .../>`.
- `RehighlightOnSave` из `core` регистрируется в `<applicationListeners>`.
- Бинарник: `vendor/bin/php-cs-fixer`.

## Настройки (поверх `ToolState`)

| Поле | Тип | По умолчанию | Аргумент CLI |
|---|---|---|---|
| `allowRisky` | Boolean | `false` | `--allow-risky=yes` |

`timeoutSeconds` по умолчанию **20**.

## Анализ (dry-run)

```
<php-cs-fixer> fix
    --dry-run
    --diff
    --format=json
    --using-cache=no
    --show-progress=none
    --no-interaction
    --path-mode=intersection
    [--config=<toTarget(configPath)>]
    [--allow-risky=yes]
    <extraArgs...>
    <toTarget(file)>
```

`--path-mode=intersection` обязателен: тогда учитывается `Finder` из конфига пользователя, и файлы, исключённые в `.php-cs-fixer.php`, не подсвечиваются.

**Несохранённый буфер:** `canAnalyzeUnsaved = false`. Анализ происходит после сохранения через механизм `PendingRehighlight` из `core`. Причина: строки в diff должны соответствовать содержимому документа один в один.

### Коды выхода (битовая маска)

- `0` — нарушений нет; `8` — есть что исправить. Оба нормальные.
- Бит `4` — синтаксическая ошибка в файле: **молча** вернуть пустой результат, синтаксис подсвечивает PHP-плагин.
- Любые другие биты (`1`, `16`, `32`, `64`) — `notifyFailure` со stderr.

Сверить коды с `php-cs-fixer fix --help` установленной версии.

### Формат вывода

```json
{
  "files": [
    {
      "name": "app/Foo.php",
      "appliedFixers": ["no_unused_imports", "single_quote"],
      "diff": "--- app/Foo.php\n+++ app/Foo.php\n@@ -3,7 +3,6 @@\n namespace App;\n \n-use Foo\\Bar;\n use Baz;\n..."
    }
  ],
  "time": { "total": 0.1 },
  "memory": 12
}
```

- Список фиксеров может называться `appliedFixers` или `fixers`: поддержать оба.
- Файл в `files` один (анализируется один файл); его отсутствие значит «нарушений нет».

## Разбор unified diff — чистая функция, обязательно с тестами

```kotlin
data class Hunk(val oldStart: Int, val oldCount: Int, val newLines: List<String>,
                val changedOldLines: List<IntRange>, val text: String)
fun parseUnifiedDiff(diff: String): List<Hunk>
```

- Заголовок: `^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@`; отсутствующий count равен 1.
- Строки тела: `' '` — контекст, `'-'` — удалена, `'+'` — добавлена, `'\'` (`\ No newline at end of file`) игнорировать. Строки `---`/`+++` до первого `@@` пропускать.
- `changedOldLines` — диапазоны старых строк, которые реально меняются: подряд идущие `-` образуют диапазон; чистая вставка (`+` без `-`) привязывается к ближайшей предыдущей старой строке (или к `oldStart`, если она первая).
- `text` — сырой текст hunk'а для tooltip.

## Отображение

- Одна аннотация на каждый диапазон из `changedOldLines`.
- Сообщение: `PHP-CS-Fixer: <фиксеры через запятую>`.
- Tooltip: `<pre>` с экранированным текстом hunk'а.
- Severity из профиля, по умолчанию `WEAK_WARNING`.

## Quick-fixes

Оба фикса получают `expectedStamp` (stamp документа на момент анализа) и **ничего не делают**, если документ изменился (`isAvailable` возвращает `false`).

1. **`ApplyHunkFix(hunk)`** — «Apply PHP-CS-Fixer fix»: заменить старые строки `[oldStart, oldStart+oldCount-1]` на `newLines`. Заменяется текст от начала первой строки до конца последней, **без** её перевода строки; новые строки склеиваются через `\n`. Если `oldCount == 0`, вставить после строки `oldStart`.
2. **`ApplyAllHunksFix(hunks)`** — «Fix whole file with PHP-CS-Fixer»: применить все hunk'и **с конца файла к началу** в одной write-команде, чтобы номера строк не съезжали.

Обе правки делаются через `Document` в `WriteCommandAction`, чтобы работал Undo. После применения документ становится несохранённым, подсветка уходит и вернётся после сохранения. Это ожидаемое поведение.

## Действие «Fix with PHP-CS-Fixer»

- `update` (`ActionUpdateThread.BGT`): доступно, если есть проект и среди выделенных (`VIRTUAL_FILE_ARRAY`, иначе файл из редактора) есть `.php`-файл или папка внутри проекта.
- `actionPerformed`: на EDT вызвать `FileDocumentManager.getInstance().saveAllDocuments()`, затем `Task.Backgroundable` с отменой:
  ```
  <php-cs-fixer> fix --using-cache=no --show-progress=none --no-interaction --path-mode=intersection [--config=...] [--allow-risky=yes] <extraArgs...> <toTarget(path)>...
  ```
- После запуска: `VfsUtil.markDirtyAndRefresh(...)` для выделенных путей (рекурсивно для папок), чтобы IDE перечитала файлы.
- Результат: при коде 0 — уведомление «Исправлено файлов: N» (N из JSON, если добавлен `--format=json`, иначе без числа); при ошибке — `notifyFailure` со stderr.

## Тесты

- `parseUnifiedDiff`: одиночная замена; удаление строки; чистая вставка; несколько hunk'ов; `\ No newline at end of file`; hunk в начале файла.
- Применение hunk'а как чистая функция `(text, hunk) → text`: результат совпадает с ожидаемым «исправленным» файлом. **Главный тест:** взять реальный вывод `php-cs-fixer --dry-run --diff --format=json` для 2–3 файлов-фикстур и проверить, что применение всех hunk'ов даёт ровно тот же текст, что `php-cs-fixer fix` без dry-run.
- Разбор JSON: `appliedFixers` и `fixers`, пустой `files`, мусор перед JSON.
- Интерпретация кодов выхода: 0, 8, 4, 12 (4|8), 16, 64.

## Ручная проверка (в `playground/`)

1. Добавить неиспользуемый `use` и двойные кавычки там, где нужны одинарные, сохранить: подсветка с diff в tooltip.
2. «Apply PHP-CS-Fixer fix» на одном месте: исправлено только оно, Ctrl+Z откатывает.
3. «Fix whole file»: результат совпадает с `vendor/bin/php-cs-fixer fix app/Foo.php`.
4. «Fix with PHP-CS-Fixer» на папке `app/` в дереве проекта: файлы исправлены, редакторы показывают новое содержимое.
5. Файл, исключённый в `.php-cs-fixer.php` через `notPath`, не подсвечивается.
6. Docker-режим: и подсветка, и действие на папке работают.
7. Файл с синтаксической ошибкой: нет уведомлений, нет подсветки от CS-Fixer.

## Не делать

- Fix on save — в v0.1 не делать, оставить на следующую версию.
- Не создавать и не править `.php-cs-fixer.php` пользователя.
- Не запускать `fix` без `--dry-run` из аннотатора: реальные изменения файлов только из явного действия пользователя.

## Критерии готовности

- [ ] Все пункты ручной проверки проходят.
- [ ] Юнит-тесты (особенно diff и применение hunk'ов) зелёные.
- [ ] README модуля: настройки, что делают quick-fix'ы и действие, Docker-пример.
