# Бриф 01 — Плагин PHPStan

> Предусловие: выполнен бриф 00 (скелет и `core`). Работать в модуле `phpstan/`.

> **Целевая платформа — только OpenIDE.** Не PhpStorm и не другие IDE JetBrains: сборка, тесты, ручная проверка и публикация (marketplace.openide.ru) только под OpenIDE; никаких зависимостей от PHP-плагина JetBrains (`com.jetbrains.php`). Любое API платформы проверять по jar'ам и исходникам OpenIDE, а не по документации JetBrains, и записывать в `docs/api-notes.md`. Подробно — разделы «Целевая платформа» и «Как проверять API» в брифе 00.

## Цель

Плагин подсвечивает ошибки PHPStan прямо в редакторе OpenIDE, в том числе в несохранённом файле, и даёт quick-fix «игнорировать ошибку». Работает и локально, и через Docker Compose. Подхватывает Larastan автоматически, потому что это просто конфиг PHPStan.

## Метаданные

- Plugin ID: `dev.phptools.phpstan`, имя: **PHPStan Integration** (for OpenIDE)
- `<depends>com.intellij.modules.platform</depends>`
- Группа уведомлений: `phpstan`
- Инспекция: shortName `PhpStanInspection`, displayName «PHPStan validation», groupName «PHP tools», `enabledByDefault="true"`, `level="WARNING"`, **без** атрибута `language`. Класс: `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. Описание лежит в `resources/inspectionDescriptions/PhpStanInspection.html`.
- Аннотатор: `<externalAnnotator language="PHP" implementationClass="...PhpStanAnnotator"/>` (ID языка `PHP` подтверждён, см. бриф 00).
- Настройки: `<projectConfigurable parentId="tools" id="dev.phptools.phpstan" displayName="PHPStan" .../>`.
- Слушатель `RehighlightOnSave` из `core` регистрируется в `<applicationListeners>`.

## Настройки (поверх `ToolState`)

| Поле | Тип | По умолчанию | Аргумент CLI |
|---|---|---|---|
| `memoryLimit` | String | `"1G"` | `--memory-limit=<v>` (пусто — не передавать) |
| `level` | String | `""` | `--level=<v>` (пусто — берётся из конфига) |
| `editorMode` | Boolean | `true` | включает `--tmp-file`/`--instead-of` для несохранённых файлов |

`timeoutSeconds` по умолчанию **60**: PHPStan на больших проектах медленный.

## Запуск

```
<phpstan> analyse
    --error-format=json
    --no-progress
    --no-interaction
    [--memory-limit=<memoryLimit>]
    [-c <toTarget(configPath)>]
    [--level=<level>]
    <extraArgs...>
    [--tmp-file=<tmp> --instead-of=<toTarget(file)>]   # только в editor mode и если буфер несохранён
    <toTarget(file)>
```

### Editor mode (анализ несохранённого буфера)

PHPStan умеет анализировать временный файл так, будто это файл проекта: `--tmp-file` и `--instead-of`. По нашим данным это появилось в **PHPStan 2.1.17 и 1.12.27** (сверить с документацией phpstan.org → «Editor mode»).

- `canAnalyzeUnsaved(project) = state.editorMode && !unsupported[project]`.
- Если буфер несохранён: `tool.runWithTempFile(input.text, "php") { tmp -> args(..., tmp) }`.
- Если в stderr есть `tmp-file` и признак неизвестной опции (`does not exist`), записать в кэш `unsupported[project] = true` (сбрасывается при Apply настроек), положить файл в `PendingRehighlight` и вернуть `null`. Один раз за сессию показать информационное уведомление: «Ваша версия PHPStan не поддерживает editor mode, анализ будет после сохранения. Обновите PHPStan до 2.1.17+ / 1.12.27+».
- Если буфер сохранён, запускать без временного файла.

## Разбор вывода

Коды выхода: **0** — ошибок нет, **1** — найдены ошибки (это нормально); всё остальное — сбой.

JSON в stdout. Перед JSON может оказаться мусор, поэтому парсить с первого `{`:

```json
{
  "totals": { "errors": 0, "file_errors": 2 },
  "files": {
    "/abs/path/src/Foo.php": {
      "errors": 2,
      "messages": [
        { "message": "Call to an undefined method Foo::bar().", "line": 12,
          "ignorable": true, "identifier": "method.notFound", "tip": "..." }
      ]
    }
  },
  "errors": []
}
```

- Ключи в `files` — пути на стороне инструмента (в Docker это пути контейнера). **Не маппить обратно**: анализируется один файл, поэтому берём запись, чей ключ оканчивается на относительный путь файла, а если запись одна, то её.
- `line` бывает `null` (ошибка уровня файла), тогда ставить на строку 1.
- Непустой верхнеуровневый `errors` (ошибки конфига, autoload и т.п.) — это `ToolNotifier.notifyFailure` с текстом ошибок.
- JSON не распарсился или код выхода не 0/1 — тоже `notifyFailure` с хвостом stderr; аннотаций нет.

## Отображение

- Сообщение: `PHPStan: <message>`.
- Tooltip (HTML, всё экранировать): сообщение, затем `tip` (если есть) отдельной строкой серым, затем идентификатор `<code>identifier</code>`.
- Колонок PHPStan не даёт: подсвечивается строка от первого непробельного символа.

## Quick-fix: `PhpStanIgnoreFix(line, identifier)`

Показывать, только если `ignorable == true` и `identifier != null`.

- Текст: `Ignore '<identifier>' with @phpstan-ignore`, family: `PHPStan: ignore error`.
- Если предыдущая строка, без ведущих пробелов, начинается с `// @phpstan-ignore ` (именно `-ignore `, а не `-ignore-line`/`-ignore-next-line`), дописать `, <identifier>` в её конец.
- Иначе вставить над строкой ошибки новую строку `// @phpstan-ignore <identifier>` с тем же отступом, что у строки ошибки.
- `startInWriteAction() = true`; правка через `Document`, без PSI.

## Тесты

- Парсер на фикстурах JSON: ошибки есть; ошибок нет; `line: null`; непустой `errors`; мусор перед JSON; пустой stdout.
- Сборка аргументов: с конфигом и без; с уровнем и без; editor mode в local (абсолютный tmp-путь) и в docker (`sh -c`-обёртка из `core`).
- Логика quick-fix на строках документа (чистая функция «текст + номер строки → новый текст»): вставка новой строки и дописывание к существующему комментарию.

## Ручная проверка (в `playground/`)

1. Написать в контроллере вызов несуществующего метода и **не сохранять**: подсветка появляется (editor mode).
2. Применить quick-fix: появляется `// @phpstan-ignore method.notFound`, после сохранения подсветка уходит.
3. Сломать `phpstan.neon` (несуществующий параметр): одно уведомление со stderr и кнопкой «Открыть настройки», не спам на каждое нажатие клавиши.
4. Включить Docker-режим, нажать «Проверить», получить версию PHPStan из контейнера, подсветка работает.
5. Выключить инспекцию в Settings → Inspections: процесс PHPStan больше не запускается.
6. Code → Inspect Code по папке `app/`: ошибки PHPStan попадают в отчёт.

## Не делать

- Не анализировать весь проект целиком на каждое изменение: только текущий файл.
- Не писать baseline и не править `phpstan.neon` пользователя.
- Не показывать уведомление, если PHPStan в проекте просто не установлен.

## Критерии готовности

- [ ] Все пункты ручной проверки проходят.
- [ ] Юнит-тесты зелёные.
- [ ] README модуля: требования (PHPStan ≥ 1.12.27 или 2.1.17 для анализа без сохранения), настройки, Docker/Sail-пример (`composeService=laravel.test`).
