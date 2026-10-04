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
