package dev.phptools.phpstan

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.settings.ToolConfigurable
import dev.phptools.phpstan.PhpStanBundle.message
import javax.swing.JLabel

class PhpStanConfigurable(project: Project) : ToolConfigurable<PhpStanState>(project, PhpStanTool.SPEC) {

    override fun storedState(): PhpStanState = PhpStanSettings.getInstance(project).state

    override fun createState() = PhpStanState()

    override fun afterApply() {
        PhpStanRuntime.reset(project)
    }

    override fun Panel.toolSpecificSettings(state: PhpStanState) {
        lateinit var levelBox: ComboBox<String>
        lateinit var levelInfo: JLabel
        group(message("settings.group")) {
            row(message("settings.memory")) {
                textField()
                    .bindText({ state.memoryLimit.orEmpty() }, { state.memoryLimit = it })
                    .comment(message("settings.memory.comment"))
            }
            row(message("settings.level")) {
                levelBox = comboBox(LEVELS, textListCellRenderer { it?.ifEmpty { message("settings.level.none") } })
                    .bindItem({ state.level.orEmpty() }, { state.level = it.orEmpty() })
                    .component
            }
            row {
                levelInfo = label(message("settings.level.detecting")).component
            }
        }
        detectConfigLevel(state, levelBox, levelInfo)
    }

    /** Уровень из конфига читается в фоне; выбор в настройках доступен, только если в конфиге его нет. */
    private fun detectConfigLevel(state: PhpStanState, levelBox: ComboBox<String>, levelInfo: JLabel) {
        val snapshot = createState().also { it.copyFrom(state) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val level = when (val lookup = ToolLocator.locate(project, spec, snapshot)) {
                is ToolLocator.Lookup.Ready -> PhpStanLevels.detect(lookup.tool, PhpStanLevels.configTarget(project, lookup.tool, snapshot))
                else -> ConfigLevel.Unknown("")
            }
            ApplicationManager.getApplication().invokeLater({
                when (level) {
                    is ConfigLevel.Set -> {
                        levelBox.isEnabled = false
                        levelInfo.text = message("settings.level.from.config", level.level)
                    }
                    ConfigLevel.NotSet -> levelInfo.text = message("settings.level.not.set")
                    is ConfigLevel.Unknown -> levelInfo.text = message("settings.level.unknown")
                }
            }, ModalityState.any())
        }
    }

    private companion object {
        val LEVELS = listOf("") + (0..10).map { it.toString() } + "max"
    }
}
