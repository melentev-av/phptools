package dev.phptools.core.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import dev.phptools.core.PhpToolsBundle.message
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.notify.ToolNotifier
import javax.swing.JCheckBox

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

                lateinit var dockerBox: JBCheckBox
                row {
                    dockerBox = checkBox(message("settings.docker"))
                        .bindSelected({ working.useDocker }, { working.useDocker = it })
                        .component
                }
                val dockerOn = CheckBoxSelected(dockerBox)
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

/** Видимость полей Docker по чекбоксу. Свой предикат, без хелперов из `com.intellij.ui.layout`. */
private class CheckBoxSelected(private val checkBox: JCheckBox) : ComponentPredicate() {
    override fun invoke(): Boolean = checkBox.isSelected

    override fun addListener(listener: (Boolean) -> Unit) {
        checkBox.addItemListener { listener(invoke()) }
    }
}
