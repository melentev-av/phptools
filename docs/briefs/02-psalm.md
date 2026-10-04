# Бриф 02 — Плагин Psalm

> Предусловие: выполнен бриф 00. Лучше делать после брифа 01: структура почти такая же, и много можно взять оттуда. Работать в модуле `psalm/`.

> **Целевая платформа — только OpenIDE.** Не PhpStorm и не другие IDE JetBrains: сборка, тесты, ручная проверка и публикация (marketplace.openide.ru) только под OpenIDE; никаких зависимостей от PHP-плагина JetBrains (`com.jetbrains.php`). Любое API платформы проверять по jar'ам и исходникам OpenIDE, а не по документации JetBrains, и записывать в `docs/api-notes.md`. Подробно — разделы «Целевая платформа» и «Как проверять API» в брифе 00.

## Цель

Плагин подсвечивает проблемы Psalm в редакторе OpenIDE с точными колонками, разделяет ошибки и info-проблемы по уровню и даёт quick-fix `@psalm-suppress`. Работает локально и через Docker Compose.

## Метаданные

- Plugin ID: `dev.phptools.psalm`, имя: **Psalm Integration** (for OpenIDE)
- `<depends>com.intellij.modules.platform</depends>`
- Группа уведомлений: `psalm`
- Инспекция: shortName `PsalmInspection`, displayName «Psalm validation», groupName «PHP tools», `enabledByDefault="true"`, `level="WARNING"`, без `language`. Класс: `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. Описание: `inspectionDescriptions/PsalmInspection.html`.
- Аннотатор: `<externalAnnotator language="PHP" implementationClass="...PsalmAnnotator"/>`.
- Настройки: `<projectConfigurable parentId="tools" id="dev.phptools.psalm" displayName="Psalm" .../>`.
- `RehighlightOnSave` из `core` регистрируется в `<applicationListeners>`.

## Настройки (поверх `ToolState`)

| Поле | Тип | По умолчанию | Аргумент CLI |
|---|---|---|---|
| `showInfo` | Boolean | `false` | `--show-info=true` / `--show-info=false` |
| `threads` | Int | `1` | `--threads=<n>` |

`timeoutSeconds` по умолчанию **60**.

## Запуск

```
<psalm>
    --output-format=json
    --no-progress
    --monochrome
    --threads=<threads>
    --show-info=<true|false>
    [--config=<toTarget(configPath)>]
    <extraArgs...>
    <toTarget(file)>
```

### Несохранённый буфер

У Psalm нет аналога `--instead-of`, поэтому `canAnalyzeUnsaved = false`: несохранённый файл попадает в `PendingRehighlight`, и анализ происходит после сохранения (механизм из `core`). Временные файлы с копией кода не делать: Psalm проверяет файл в контексте проекта, и копия вне проекта даст ложные ошибки.

## Разбор вывода

Коды выхода: **0** — проблем нет, **2** — найдены проблемы (это нормально); всё остальное — сбой. Сверить коды с документацией Psalm для установленной версии.

stdout — JSON-массив. Парсить с первого `[`; пустой stdout при коде 0 — это ноль проблем.

```json
[
  {
    "severity": "error",
    "line_from": 12, "line_to": 12,
    "column_from": 9, "column_to": 21,
    "type": "UndefinedMethod",
    "message": "Method App\\Foo::bar does not exist",
    "file_name": "app/Foo.php",
    "file_path": "/var/www/html/app/Foo.php",
    "link": "https://psalm.dev/022",
    "from": 345, "to": 357
  }
]
```

- Фильтровать по файлу так же, как в PHPStan: `file_path` или `file_name` оканчивается на относительный путь. Если путь не совпал, но запись единственная, брать её.
- **Не использовать `from`/`to`**: это байтовые смещения, а в документе символы, и с кириллицей в строках всё поедет. Брать `line_from`/`column_from` → `line_to`/`column_to` с clamp к границам строк.
- `severity == "info"` → `weak = true`.
- Невалидный JSON или неожиданный код выхода → `notifyFailure` со stderr.

## Отображение

- Сообщение: `Psalm: <message>`.
- Tooltip (HTML, экранировать): сообщение, затем `<code>type</code>` и ссылка на `link` («Документация»), если она есть.
- Ошибки получают severity из профиля инспекции, info-проблемы — не выше `WEAK_WARNING`.

## Quick-fix: `PsalmSuppressFix(line, type)`

- Текст: `Suppress '<type>' with @psalm-suppress`, family: `Psalm: suppress issue`.
- Если предыдущая строка, без пробелов, — однострочный докблок `/** @psalm-suppress X */`, превратить его в `/** @psalm-suppress X, <type> */`. Psalm поддерживает несколько типов через запятую; сверить с документацией.
- Иначе вставить над строкой проблемы `/** @psalm-suppress <type> */` с отступом строки проблемы.
- Многострочный докблок над функцией или классом в v0.1 **не трогать**: вставлять отдельную строку.
- `startInWriteAction() = true`; правка через `Document`.

## Тесты

- Парсер на фикстурах: пустой массив; error и info вперемешку; несколько файлов в выводе (берётся только нужный); мусор перед JSON; пустой stdout с кодом 0.
- Расчёт диапазона: многострочная проблема (`line_from != line_to`), колонки за пределами строки (clamp).
- Логика quick-fix как чистая функция: новая строка и дописывание к существующему однострочному `@psalm-suppress`.
- Сборка аргументов: `showInfo` true/false, с конфигом и без.

## Ручная проверка (в `playground/`)

1. В сохранённом файле вызвать несуществующий метод: подсветка точно на вызове, с колонками.
2. Начать печатать: подсветка Psalm исчезает. Сохранить (Ctrl+S): подсветка возвращается без дополнительных действий.
3. Включить `showInfo`: появились weak warning-и для info-проблем.
4. Quick-fix вставляет `/** @psalm-suppress UndefinedMethod */`, после сохранения проблема уходит.
5. Ошибка в `psalm.xml`: одно уведомление со stderr, не спам.
6. Docker-режим: «Проверить» показывает версию Psalm из контейнера, подсветка работает.

## Не делать

- Не запускать `psalm --alter` и другие автоисправления.
- Не трогать `psalm.xml` и baseline пользователя.
- Не ставить `--threads` больше 1 по умолчанию: на каждое сохранение это лишняя нагрузка.

## Критерии готовности

- [ ] Все пункты ручной проверки проходят.
- [ ] Юнит-тесты зелёные.
- [ ] README модуля: настройки, ограничение «анализ после сохранения», Docker-пример.
