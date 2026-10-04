# Заметки по API OpenIDE

Всё ниже проверено по jar-файлам установленной **OpenIDE 2026.2.3, build 262.10968.63.1**
(`/Applications/OpenIDE.app/Contents/lib`, `javap`, `META-INF/*.xml`). Номер сборки взят из
`Contents/Resources/product-info.json`: на macOS файла `build.txt` в бандле нет.

## Ответы на открытые вопросы брифа 00

| # | Вопрос | Ответ |
|---|---|---|
| 1 | ID языка PHP | `PHP`. В `plugin.xml` плагина `ru.openide.openphp` 0.9.3: `<fileType name="PHP" language="PHP" extensions="php;phtml">`. |
| 2 | Пакет `ExternalAnnotatorBatchInspection` | `com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection` (`lib/intellij.platform.analysis.impl.jar`). Интерфейс, наследует `PairedUnfairLocalInspectionTool`; абстрактный только `getShortName()`. |
| 3 | Сигнатуры UI DSL | Совпадают с ожидаемыми, адаптация не нужна. `Row.textFieldWithBrowseButton(FileChooserDescriptor, Project?, (VirtualFile) -> String)`, `bindText(getter, setter)` есть и для `JTextComponent`, и для `TextFieldWithBrowseButton`; `bindIntText(getter, setter)`, `bindSelected(getter, setter)`; `Row.visibleIf(ComponentPredicate)`. |
| 4 | Собственный product-модуль OpenIDE | **Нет.** В `lib/*.jar` объявлены только стандартные `com.intellij.modules.*` (`platform`, `lang`, `idea`, `idea.community`, …). Отличительные признаки OpenIDE — пакет `ru.openide.*` в ядре и essential-плагин `ru.openide.pro.updater` (в `idea/IdeaApplicationInfo.xml`), но зависеть от них для ограничения установки неправильно. Используем `<depends>com.intellij.modules.platform</depends>`. |
| 5 | Требования маркетплейса OpenIDE | **Не выяснено.** На marketplace.openide.ru есть раздел «Опубликовать свой плагин», публичной страницы с требованиями или офертой без входа не найдено. Проверить вручную до первой публикации. |

## Платформа и сборка

| API / факт | Где найдено | Особенности |
|---|---|---|
| `product-info.json`: `productCode = "IC"`, `minRequiredJavaVersion = 25` | `Contents/Resources/product-info.json` | Gradle-плагин видит OpenIDE как IntelliJ IDEA Community (`IC-2026.2.3` в пути песочницы). |
| `local(openidePath)` принимает путь к `.app` | IntelliJ Platform Gradle Plugin 2.19.0 | На macOS указывать `/Applications/OpenIDE.app`, а не `Contents`. |
| `pluginComposedModule(implementation(project(":core")))` | IntelliJ Platform Gradle Plugin 2.19.0 | С обычным `implementation(project(":core"))` модуль `org.jetbrains.intellij.platform.module` кладётся в `lib/modules/php-tools.core.jar`, а это место для content-модулей plugin model v2, и без `<content>` в `plugin.xml` его нет в classpath. `pluginComposedModule` вшивает классы `core` в jar плагина. |
| PHP-плагин | `~/Library/Application Support/OpenIDE/OpenIDE2026.2/plugins/php-for-openide/lib/php-for-openide-0.9.3.jar` | ID `ru.openide.openphp`, `since-build="262" until-build="262.*"`. Не в бандле IDE, ставится в пользовательские плагины. С 0.9.3 есть **своя** интеграция PHPStan, Psalm, Mago, PHP_CodeSniffer и PHP CS Fixer (Settings → PHP → Анализ и форматирование). |

## API, используемое в `core`

| Класс / метод | Jar | Особенности |
|---|---|---|
| `com.intellij.lang.annotation.ExternalAnnotator` | `intellij.platform.analysis.jar` | `collectInformation(PsiFile)`, `collectInformation(PsiFile, Editor, boolean)`, `doAnnotate`, `apply`, `getPairedBatchInspectionShortName()`. |
| `AnnotationBuilder.range(TextRange)`, `.tooltip`, `.withFix(IntentionAction)`, `.create()` | `intellij.platform.analysis.jar` | — |
| `com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.restart(PsiFile, Object)` | `intellij.platform.analysis.jar` | `restart()` и `restart(PsiFile)` — `@Deprecated`, используем вариант с `reason`. |
| `HighlightDisplayKey.find(String)` | `intellij.platform.analysis.jar` | Конструктор и `register(String, String, String)` — `@Deprecated`/`@Internal`, нам не нужны. |
| `InspectionProjectProfileManager.getInstance(project).currentProfile.getErrorLevel(key, element).severity` | `intellij.platform.analysis.impl.jar` | `getInspectionProfile()` — `@Deprecated`, используем `currentProfile`. |
| `GeneralCommandLine(List<String>)`, `withWorkingDirectory(Path)`, `withCharset`, `withParentEnvironmentType(CONSOLE)` | `util.jar` | `withWorkDirectory(String/File)` — `@ApiStatus.Obsolete`, используем `withWorkingDirectory(Path)`. |
| `CapturingProcessHandler.runProcessWithProgressIndicator(indicator, timeoutMs, destroyOnTimeout)` | `util.jar` | `ProcessOutput.isTimeout()`, `isCancelled()`. Отмена индикатора убивает процесс. |
| `com.intellij.util.execution.ParametersListUtil.parse(String)` | `util-8.jar` | Есть одноимённый класс в `com.pty4j.util` — не путать. |
| `FileUtil.createTempFile(prefix, suffix, deleteOnExit)` | `util.jar` | Без пометок. |
| `BaseState.copyFrom(BaseState)`, `equals` | `intellij.platform.projectModel.jar` | `string(default)` возвращает `String?`. |
| `SimplePersistentStateComponent` | `intellij.platform.projectModel.jar` | — |
| `BoundConfigurable(displayName)`, `createPanel(): DialogPanel` | `intellij.platform.ide.jar` | — |
| `com.intellij.ui.layout.ComponentPredicate` | `intellij.platform.ide.jar` | Абстрактный `invoke()` + `addListener`; наследуемся сами, хелперы из `com.intellij.ui.layout` не используем. |
| `FileChooserDescriptorFactory.singleFile()` | `intellij.platform.ide.core.jar` | — |
| `ShowSettingsUtil.showSettingsDialog(Project, Predicate<Configurable>, Consumer?)` | `intellij.platform.ide.jar` | Перегрузка `(Project, String)` выбирает по отображаемому имени, поэтому ищем `SearchableConfigurable` по id. |
| `NotificationGroupManager.getNotificationGroup(id)`, `NotificationAction.createSimpleExpiring` | `intellij.platform.ide.core.jar` / `intellij.platform.ide.jar` | Группа регистрируется в `plugin.xml` каждого плагина. |
| `FileDocumentManagerListener.afterDocumentSaved(Document)` | `intellij.platform.core.jar` | Регистрация через `<applicationListeners>`. |
| `ProjectFileIndex.isInContent / isInLibrary / isExcluded` | `intellij.platform.projectModel.jar` | — |
| `com.google.gson.Gson` | `intellij.libraries.gson.jar` | Есть в платформе, бандлить не нужно. |
| `com.intellij.openapi.startup.ProjectActivity` (`suspend execute(project)`) | `intellij.platform.core.jar` | Регистрация `<postStartupActivity>`. |
| `InspectionProfileImpl.getInspectionTool(shortName, project)?.extension?.pluginDescriptor` | `intellij.platform.analysis.impl.jar` / `intellij.platform.analysis.jar` | `InspectionEP` → `BaseKeyedLazyInstance.getPluginDescriptor()`: так проверяем, что инспекция принадлежит `ru.openide.openphp`. |
| `com.intellij.codeInspection.ex.modifyAndCommitProjectProfile(project) { it.setToolEnabled(shortName, false, project) }` | `intellij.platform.analysis.impl.jar` | Выключение инспекции в профиле проекта с сохранением. Без пометок. |
| `PropertiesComponent.getInstance(project).isTrueValue / setValue(key, true)` | `intellij.platform.core.jar` | Флаг «Больше не спрашивать». |
| `com.intellij.openapi.application.readAction { }` | `CoroutinesKt` | Для чтения профиля из `ProjectActivity`. |

## API, добавленное в брифе 01a

| Класс / метод | Jar | Особенности |
|---|---|---|
| `GeneralCommandLine.getParentEnvironment()` + `withParentEnvironmentType(NONE)` + `withEnvironment(map)` | `util.jar` | Так из окружения консоли убираются переменные AI-агентов (`AgentEnvironment`). |
| `com.intellij.util.EnvironmentUtil.getEnvironmentMap()` | `util.jar` | Не путать с `com.intellij.ide.environment.impl.EnvironmentUtil`. |
| `EditorNotificationProvider.collectNotificationData(project, file): Function<in FileEditor, out JComponent?>?` | `intellij.platform.ide.jar` | Регистрация `<editorNotificationProvider>`. |
| `EditorNotificationPanel(FileEditor, Status)`, `.text()`, `.createActionLabel(String, Runnable)`, `Status.Warning` | `intellij.platform.ide.jar` | — |
| `EditorNotifications.getInstance(project).updateNotifications(file)` | `intellij.platform.ide.jar` | — |
| `BaseState.enum(default)` | `intellij.platform.projectModel.jar` | Для `CheckMode`. |
| `Panel.buttonsGroup(title) { row { radioButton(text, value) } }.bind(getter, setter)` | `intellij.platform.ide.impl.jar` | `ButtonsGroupKt.bind`. |
| `Row.comboBox(items, renderer).bindItem(getter, setter)` | `intellij.platform.ide.impl.jar` | — |
| `com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { }` | `intellij.platform.ide.impl.jar` | `SimpleListCellRenderer.create(String, Function)` — `@Deprecated`. |
| `LocalInspectionTool` + `ExternalAnnotatorBatchInspection` | `intellij.platform.analysis.jar` / `.impl.jar` | `getShortName()` переопределён. |
| `IntentionAction` (`getText`, `getFamilyName`, `isAvailable`, `invoke`, `startInWriteAction`) | `intellij.platform.analysis.jar` | — |

## API, добавленное в брифе 01b (панель и отчёт)

| Класс / метод | Jar | Особенности |
|---|---|---|
| `ToolWindowFactory.createToolWindowContent(project, toolWindow)`, `<toolWindow id anchor icon factoryClass>` | `intellij.platform.ide.jar` | Иконка панели — PNG 20×20 (+@2x) для нового UI. |
| `ContentFactory.getInstance().createContent(component, title, false)` | `intellij.platform.ide.core.jar` | — |
| `ChangeListManager.getAllChanges()`, `getUnversionedFilesPaths()`, `ChangeListListener.TOPIC` / `changeListUpdateDone()` | `intellij.platform.vcs.jar` | Модуль `com.intellij.modules.vcs` — добавлен `<depends>` в plugin.xml. |
| `CheckBoxList.setItems/setItemSelected/isItemSelected/setCheckBoxListListener` | `intellij.platform.ide.jar` | — |
| `ColoredTreeCellRenderer.append(text, attrs, tag)` + `SimpleColoredComponent.getFragmentTagAt(x)` | `intellij.platform.ide.jar` | Кликабельные ссылки в дереве отчёта. |
| `Task.Backgroundable(project, title, true)` + `queue()`, `onSuccess/onFinished` | `intellij.platform.core.jar` | `BackgroundableProcessIndicator` — impl-класс, не используем; индикатор берём внутри `run`. |
| `FileDocumentManager.saveAllDocuments()` | `intellij.platform.core.jar` | **Требует write-intent lock.** Обработчики Swing-кнопок в 262 его не имеют → «Access is allowed from write thread only». `WriteIntentReadAction` целиком `@ApiStatus.Experimental`, поэтому вызов переносится в `Application.invokeLater` (выполняется под write-intent lock). |
| `ActionManager.createActionToolbar(place, group, horizontal)`, `ToggleAction`, `DumbAwareAction`, `getActionUpdateThread()` | `intellij.platform.ide.jar` | — |
| `PopupHandler.installPopupMenu(component, group, place)` | `intellij.platform.ide.jar` | — |
| `FileChooserFactory.createSaveFileDialog(FileSaverDescriptor, project).save(name)` | `intellij.platform.ide.jar` | — |
| `WriteCommandAction.runWriteCommandAction(project, name, groupId, runnable, psiFile)` | `intellij.platform.core.jar` | Quick-fix из отчёта. |
| `CommonDataKeys.VIRTUAL_FILE_ARRAY`, группа `ProjectViewPopupMenu` | — | Действие «Проверить PHPStan». |
| Горячая перезагрузка плагина в песочнице (`idea.auto.reload.plugins`) | — | Для плагина с панелью и сервисами не срабатывает («new plugins state did not meet expectations») — песочницу нужно перезапускать. |

## PHPStan (проверено на 2.2.16 + Larastan 3.12, PHP 8.5)

| Факт | Как проверено | Что сделано в плагине |
|---|---|---|
| При переменных AI-агента (`PHPStan\Internal\AgentDetector::ENV_VARS`: `AI_AGENT`, `CLAUDECODE`, `CLAUDE_CODE`, `CURSOR_AGENT`, `CURSOR_TRACE_ID`, `CODEX_SANDBOX`, `CODEX_THREAD_ID`, `GEMINI_CLI`, `AUGMENT_AGENT`, `AMP_CURRENT_THREAD_ID`, `OPENCODE`, `OPENCODE_CLIENT`, `REPL_ID`; ещё файл `/opt/.devin`) JSON меняется на `{"tool","result","errors","error_details","instructions"}` и в stderr добавляются инструкции для агента | Запуск из Claude Code и с `env -i`; исходник `src/Internal/AgentDetector.php` в phar | `AgentEnvironment` убирает переменные; парсер понимает оба формата |
| `Note: Using configuration file …` пишется в stderr, stdout — чистый JSON | Раздельный вывод потоков | Парсер всё равно ищет JSON с первой `{` |
| Ошибка конфига (`Invalid configuration: …`): код 1, stdout пустой, текст в stderr | `-c` с лишним параметром | Код 0/1 без JSON — сбой, уведомление со stderr |
| Файл из `excludePaths`: код 1, stdout пустой, stderr `[ERROR] No files found to analyse.` | `app/Excluded.php` | Молча, без аннотаций |
| Без `level` в конфиге и без `--level`: `analyse` и `dump-parameters` падают с `No rules detected` (код 1) | Конфиг без `level` | Уровень из настроек — только при его отсутствии в конфиге; подсказка в уведомлении |
| `dump-parameters --json` отдаёт плоский объект параметров: `"level": 6` (число или `"max"`), `"usedLevel": "6"` | Вывод команды | `PhpStanConfigLevel` |
| `--tmp-file`/`--instead-of` работают в 2.2.16; в 1.12.0 — stderr `The "--tmp-file" option does not exist.`, код 1 | PHPStan 1.12.0 во временной папке | `PhpStanResult.TmpFileUnsupported` |
| Синтаксическая ошибка: `identifier: "phpstan.parse"`, `ignorable: false`, со строкой | `app/Syntax.php` | Quick-fix не предлагается |
| Пакетный запуск: файл из `excludePaths` в списке путей просто пропускается; без путей берутся `paths` из конфига; папки поддерживаются | `analyse a.php Excluded.php b.php`, `analyse`, `analyse app/Http` | Пакетный режим панели, `LaunchPlan.fromTarget` для путей из контейнера |
| В Docker ключи `files` — пути контейнера (`/var/www/html/app/...`) | `docker compose exec` с `php:8.5-cli` | Сопоставление по концу относительного пути |

## Psalm (проверено на 6.19.1, PHP 8.5)

| Факт | Что сделано в плагине |
|---|---|
| Коды выхода: 0 — чисто (`[]`), 2 — есть проблемы, 1 — ошибка конфига (stdout пустой, `Problem parsing …` в stderr) | `PsalmOutput` |
| **`column_from`/`column_to` — в байтах UTF-8** (кириллица левее в строке сдвигает колонку), конец не включается; `from`/`to` тоже байтовые | `ToolProblem.byteColumns` + пересчёт в `ProblemRanges` |
| `@psalm-suppress A, B` — несколько типов через запятую; через пробел второй тип не подавляется | `PsalmSuppress` |
| Файл вне `projectFiles` — код 0, `[]` | Молча |
| `CliUtils::runningUnderAiAgent` (те же переменные, плюс `CLINE_ACTIVE`, `COPILOT_CLI`, `TRAE_AI_SHELL_ID`, `ANTIGRAVITY_AGENT`, `PI_CODING_AGENT`, `AGENT=goose`) выключает только прогресс | Переменные добавлены в `AgentEnvironment` |
| Аналога `--tmp-file` нет | `canAnalyzeUnsaved = false` |

## PHP-CS-Fixer (проверено на 3.95.27, PHP 8.5)

| Факт | Что сделано в плагине |
|---|---|
| Код выхода — битовая маска: 1 общая ошибка, 4 синтаксис, 8 есть что исправить, 16/32 конфигурация, 64 исключение | `CsFixerOutput` |
| `appliedFixers` в JSON только с `-v` | `-v` всегда |
| **stdin: путь `-`** — конфиг из рабочей папки применяется, `name` = `php://stdin`, diff по содержимому stdin; работает и через `docker compose exec -T` | Анализ всегда через stdin, `canAnalyzeUnsaved = true` |
| Для stdin `Finder` не применяется (`notPath` не исключает) | `CsFixerFinder` по `list-files` (пути `'./app/…'`, ~100 мс, кэш 30 с) |
| Вне `Finder` при `--path-mode=intersection` — код 0, `files: []`; синтаксис — код 4, `files: []` | Молча |
| В stderr бывает предупреждение о версии PHP проекта при нормальной работе | stderr — только при сбое |
| Применение всех кусков dry-run diff = результат `fix` (фикстуры в `php-cs-fixer/src/test/resources/fixtures`) | Главный тест |
| `VfsUtil.markDirtyAndRefresh(async, recursive, reloadChildren, VirtualFile...)` | Перечитывание файлов после `fix` |

## Phan (проверено на 6.0.7, PHP 8.5, без php-ast)

| Факт | Что сделано в плагине |
|---|---|
| Без `php-ast`: `ERROR: The php-ast extension must be loaded…`; с `--allow-polyfill-parser` работает медленнее (~11 с на файл локально, ~15 с в `php:8.5-cli`) | `allowPolyfillParser = true` по умолчанию; `PhanResult.PhpAstMissing` → понятное уведомление |
| Анализ одного файла: `-I <file>` (через запятую — несколько), парсится весь `directory_list` | Редактор: `-I <относительный путь>`; панель: `-I a,b,…` (папки раскрываются в PHP-файлы) |
| Нет `--tmp-file`: подмена файла только в демоне/LSP | `canAnalyzeUnsaved = false`, `checkMode` по умолчанию `ON_SAVE` (`ToolState(defaultCheckMode)`) |
| Коды: 0 — `[]`, 1 — проблемы; ошибка конфига — 1 без JSON (`ERROR: …` в stderr); файл вне `directory_list` — 0 и пустой stdout | `PhanOutput` |
| `location.path` — относительно рабочей папки; `lines.begin`/`end`, иногда `begin_column` | Сопоставление по относительному пути, колонка если есть |
| `description` = `<Category> <check_name> <текст>` | Префикс срезается |
| `@phan-suppress-next-line A, B` — через запятую | `PhanSuppress` |
| Документация по проверкам переехала из wiki в `internal/Issue-Types-Caught-by-Phan.md` (заголовки `## PhanXxx`) | `docUrl` → `…/blob/v6/internal/Issue-Types-Caught-by-Phan.md#phanxxx` |

## WSL (бриф 05)

| API / факт | Где | Особенности |
|---|---|---|
| `WslDistributionManager.getInstance().getInstalledDistributionsFuture()`, `WSLDistribution.getMsId()` | `intellij.platform.ide.impl.jar` | Без пометок в 262. Используется только для списка дистрибутивов в настройках (асинхронно) |
| `WSLDistribution.patchCommandLine(…, String, boolean)` | там же | `@Internal` + `@Deprecated` — **не используем**; команду `wsl.exe` собираем сами (`LaunchPlan`) |
| `WSLDistribution.getUNCRootPath()` | там же | `@Experimental` — не используем; UNC-корень строим сами (`WslPaths.uncRoot`) |
| `WslPath.parseWindowsUncPath`, `WSLDistribution.getWslPath(Path)` / `getWindowsPath(String)` | там же | Без пометок; можно использовать для сверки, основная логика — своя (`WslPaths`) |
| `wsl.exe -d <distro> --cd <linux> --exec <cmd…>` | — | `--exec` без оболочки; `bash -lc` — одной строкой (`ShellQuote`) |
| `WSL_UTF8=1` | — | Переключает собственные сообщения `wsl.exe` из UTF-16LE в UTF-8; запасной вариант — `WslOutput.fixUtf16` |

**Проверить на Windows + WSL2 (не проверено, macOS):**

1. Как выглядит `project.basePath` для проекта в WSL (`//wsl$/…`, `//wsl.localhost/…`?).
2. Работает ли `WSL_UTF8=1` в установленной версии WSL; читаемы ли ошибки «дистрибутив не найден» по-русски.
3. `PATH` у `--exec` без оболочки: находится ли `php` из пакетов дистрибутива; asdf/phpenv — только с login shell.
4. Накладные расходы `wsl.exe` на запуск и «холодный» старт дистрибутива — не срабатывают ли таймауты.
5. Отмена: убивает ли завершение `wsl.exe` процесс внутри WSL.
6. `BinaryResolver` по UNC-путям (`Files.isRegularFile(\\wsl.localhost\…\vendor\bin\phpstan)`).
7. Docker внутри WSL: работает ли `composeCommand = wsl.exe -d <distro> docker compose`.

## Встроенные анализаторы PHP-плагина (`ru.openide.openphp` 0.9.3)

Из его `META-INF/plugin.xml`. Это внутренние ID чужого плагина: если они поменяются, `BuiltInAnalyzerCheck`
просто перестанет срабатывать, без ошибок. При обновлении PHP-плагина сверять заново.

| Инструмент | shortName инспекции | Аннотатор |
|---|---|---|
| PHPStan | `PhpStanExternalAnalyzer` | `ru.openide.openphp.analyzer.backend.PhpStanAnnotator` |
| Psalm | `PsalmExternalAnalyzer` | `…PsalmAnnotator` |
| PHP-CS-Fixer | `CsFixerExternalAnalyzer` | `…CsFixerAnnotator` (+ `PhpCsFixerFormattingService` для Reformat Code) |
| Mago / PHP_CodeSniffer | `MagoExternalAnalyzer` / `PhpcsExternalAnalyzer` | нам не нужны |

Все инспекции зарегистрированы с `enabledByDefault="true"`, а реально инструмент запускается только при
включённом выключателе «Проверять код через …» в Settings → PHP → Анализ и форматирование. Этот выключатель
хранится во внутреннем состоянии PHP-плагина, мы его не читаем. Поэтому вопрос о выключении встроенной
инспекции может появиться и тогда, когда встроенный инструмент фактически не запускается.
