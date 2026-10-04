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
| В Docker ключи `files` — пути контейнера (`/var/www/html/app/...`) | `docker compose exec` с `php:8.5-cli` | Сопоставление по концу относительного пути |

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
