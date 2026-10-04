package dev.phptools.core.settings

import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.notify.ToolNotifier
import javax.swing.AbstractButton

/**
 * Базовый экран настроек инструмента. Работает с рабочей копией состояния: UI привязан к [working],
 * в сохранённое состояние ([storedState]) она копируется только при Apply.
 *
 * Регистрация в плагине: `<projectConfigurable parentId="tools" id="<spec.configurableId>" instance="..."/>`.
 */
abstract class ToolConfigurable<S : ToolState>(
    protected val project: Project,
    protected val spec: ToolSpec,
) : BoundConfigurable(spec.displayName) {

    /** Сохранённое состояние из сервиса проекта. */
    protected abstract fun storedState(): S

    /** Новый экземпляр состояния (для рабочей копии). */
    protected abstract fun createState(): S

    /** Поля конкретного инструмента. */
    protected abstract fun Panel.toolSpecificSettings(state: S)

    /** Вызывается после Apply: сбросить кэши инструмента. */
    protected open fun afterApply() {}

    protected val working: S by lazy { createState().also { it.copyFrom(storedState()) } }

    private lateinit var dialogPanel: DialogPanel

    override fun createPanel(): DialogPanel {
        dialogPanel = panel {
            buttonsGroup(message("settings.mode")) {
                row { radioButton(message("settings.mode.typing"), CheckMode.ON_TYPING) }
                row { radioButton(message("settings.mode.save"), CheckMode.ON_SAVE) }
                row { radioButton(message("settings.mode.manual"), CheckMode.MANUAL) }
            }.bind({ working.checkMode }, { working.checkMode = it })

            group(message("settings.group.run")) {
                row(message("settings.executable")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), project)
                        .bindText({ working.executable.orEmpty() }, { working.executable = it })
                        .align(AlignX.FILL)
                        .comment(message("settings.executable.comment", spec.binaryName))
                }
                row(message("settings.config")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), project)
                        .bindText({ working.configPath.orEmpty() }, { working.configPath = it })
                        .align(AlignX.FILL)
                        .comment(message("settings.config.comment"))
                }
                row(message("settings.php")) {
                    textField()
                        .bindText({ working.phpInterpreter.orEmpty() }, { working.phpInterpreter = it })
                        .align(AlignX.FILL)
                        .comment(message("settings.php.comment"))
                }
                row(message("settings.extra.args")) {
                    textField()
                        .bindText({ working.extraArgs.orEmpty() }, { working.extraArgs = it })
                        .align(AlignX.FILL)
                }
                row(message("settings.timeout")) {
                    intTextField(1..3600)
                        .bindIntText({ working.timeoutSeconds }, { working.timeoutSeconds = it })
                }

                lateinit var dockerRadio: JBRadioButton
                lateinit var wslRadio: JBRadioButton
                buttonsGroup(message("settings.run.mode")) {
                    row { radioButton(message("settings.run.local"), RunMode.LOCAL) }
                    row { dockerRadio = radioButton(message("settings.docker"), RunMode.DOCKER).component }
                    val dockerOn = ButtonSelected(dockerRadio)
                    indent {
                        row(message("settings.compose.command")) {
                            textField()
                                .bindText({ working.composeCommand.orEmpty() }, { working.composeCommand = it })
                                .align(AlignX.FILL)
                        }.visibleIf(dockerOn)
                        row(message("settings.compose.service")) {
                            textField()
                                .bindText({ working.composeService.orEmpty() }, { working.composeService = it })
                        }.visibleIf(dockerOn)
                        row(message("settings.container.path")) {
                            textField()
                                .bindText({ working.containerProjectPath.orEmpty() }, { working.containerProjectPath = it })
                                .align(AlignX.FILL)
                        }.visibleIf(dockerOn)
                    }
                    // WSL — только когда IDE запущена на Windows.
                    row { wslRadio = radioButton(message("settings.run.wsl"), RunMode.WSL).component }.visible(SystemInfo.isWindows)
                    val wslOn = ButtonSelected(wslRadio)
                    indent {
                        row(message("settings.wsl.distribution")) {
                            val box = comboBox(listOf(working.wslDistribution.orEmpty()))
                                .bindItem({ working.wslDistribution.orEmpty() }, { working.wslDistribution = it.orEmpty() })
                                .comment(message("settings.wsl.distribution.comment"))
                                .component
                            box.isEditable = true
                            if (SystemInfo.isWindows) loadWslDistributions(box)
                        }.visibleIf(wslOn)
                        row {
                            checkBox(message("settings.wsl.login.shell"))
                                .bindSelected({ working.wslLoginShell }, { working.wslLoginShell = it })
                                .comment(message("settings.wsl.login.shell.comment"))
                        }.visibleIf(wslOn)
                    }
                }.bind({ working.effectiveRunMode() }, { working.applyRunMode(it) })

                row {
                    button(message("settings.check")) { checkTool() }
                }
            }
            toolSpecificSettings(working)
        }
        return dialogPanel
    }

    override fun reset() {
        working.copyFrom(storedState())
        super.reset()
    }

    override fun apply() {
        super.apply()
        storedState().copyFrom(working)
        ToolNotifier.reset(project, spec)
        afterApply()
    }

    override fun isModified(): Boolean = super.isModified() || working != storedState()

    /** «Проверить»: применить панель к рабочей копии и запустить `<exe> --version` в фоне с прогрессом. */
    private fun checkTool() {
        dialogPanel.apply()
        val snapshot = createState().also { it.copyFrom(working) }
        val (ok, text) = ProgressManager.getInstance().runProcessWithProgressSynchronously<Pair<Boolean, String>, RuntimeException>(
            { runCheck(snapshot) },
            message("settings.check.progress", spec.displayName),
            true,
            project,
        )
        val title = message("settings.check.title", spec.displayName)
        if (ok) Messages.showInfoMessage(dialogPanel, text, title) else Messages.showErrorDialog(dialogPanel, text, title)
    }

    private fun runCheck(state: S): Pair<Boolean, String> =
        when (val lookup = ToolLocator.locate(project, spec, state)) {
            is ToolLocator.Lookup.Misconfigured -> false to "${lookup.title}\n${lookup.details}"
            ToolLocator.Lookup.NotInstalled -> false to message("notification.not.installed", spec.binaryName)
            is ToolLocator.Lookup.Ready -> {
                val out = lookup.tool.run(spec.versionArgs)
                when {
                    out.cancelled -> false to message("settings.check.cancelled")
                    out.timedOut -> false to message("settings.check.timeout", state.timeoutSeconds)
                    out.exitCode == 0 && !out.startFailed -> true to message("settings.check.ok", out.stdout.trim().ifEmpty { out.stderr.trim() })
                    else -> false to message("settings.check.failed", out.exitCode, ToolNotifier.excerpt(out.stderr.ifBlank { out.stdout }))
                }
            }
        }
}

/** Видимость полей режима по выбранной радиокнопке. Свой предикат, без хелперов из `com.intellij.ui.layout`. */
private class ButtonSelected(private val button: AbstractButton) : ComponentPredicate() {
    override fun invoke(): Boolean = button.isSelected

    override fun addListener(listener: (Boolean) -> Unit) {
        button.addItemListener { listener(invoke()) }
    }
}

/** Список установленных дистрибутивов WSL — асинхронно, чтобы не держать UI на вызове `wsl.exe --list`. */
private fun loadWslDistributions(box: ComboBox<String>) {
    WslDistributionManager.getInstance().installedDistributionsFuture.thenAccept { distributions ->
        ApplicationManager.getApplication().invokeLater({
            val current = box.selectedItem as? String
            distributions.map { it.msId }.filter { it != current }.forEach(box::addItem)
        }, ModalityState.any())
    }
}
