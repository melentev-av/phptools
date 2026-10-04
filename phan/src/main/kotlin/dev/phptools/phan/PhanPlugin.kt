package dev.phptools.phan

import com.intellij.DynamicBundle
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.ToolSpec
import dev.phptools.core.annotator.AnnotationInput
import dev.phptools.core.annotator.FileProblemsBanner
import dev.phptools.core.annotator.ToolExternalAnnotator
import dev.phptools.core.annotator.ToolFindings
import dev.phptools.core.annotator.ToolProblem
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.exec.ToolOutput
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.core.settings.CheckMode
import dev.phptools.core.settings.ToolState
import dev.phptools.phan.PhanBundle.message
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.nio.file.Path

object PhanTool {
    /** У PHP-плагина OpenIDE встроенного Phan нет — `builtInInspectionShortName` не нужен. */
    val SPEC = ToolSpec(
        id = "phan",
        displayName = "Phan",
        binaryName = "phan",
        configurableId = "dev.phptools.phan",
    )
}

/**
 * Phan анализирует всю программу и без php-ast медленный (~11 с на файл в маленьком Laravel),
 * а несохранённый буфер в CLI не умеет — поэтому по умолчанию «При сохранении» и таймаут 120 с.
 */
class PhanState : ToolState(defaultTimeoutSeconds = 120, defaultCheckMode = CheckMode.ON_SAVE) {
    /** `--allow-polyfill-parser`: без php-ast Phan иначе не запустится; с php-ast флаг ни на что не влияет. */
    var allowPolyfillParser by property(true)
    /** `-j`, только для панели. */
    var processes by property(1)
    /** `--memory-limit`; пусто — не передавать. */
    var memoryLimit by string("")
}

/** Настройки проекта; файл `.idea/phan-tools.xml` можно коммитить. */
@Service(Service.Level.PROJECT)
@State(name = "PhanSettings", storages = [Storage("phan-tools.xml")])
class PhanSettings : SimplePersistentStateComponent<PhanState>(PhanState()) {
    companion object {
        fun getInstance(project: Project): PhanSettings = project.service()

        /** Путь к конфигу на стороне инструмента или `null` (Phan берёт `.phan/config.php` из рабочей папки). */
        fun configTarget(project: Project, tool: PreparedTool, state: PhanState): String? {
            val configured = state.configPath.orEmpty().trim().ifEmpty { return null }
            val path = Path.of(configured)
            if (path.isAbsolute) return tool.toTarget(path)
            val base = project.basePath ?: return configured
            return tool.toTarget(Path.of(base).resolve(path))
        }

        /** Путь файла относительно рабочей папки Phan (`-I` и `location.path`); `null` — файл вне её. */
        fun relativeToWorkDir(tool: PreparedTool, path: Path): String? {
            val workDir = tool.plan.workDir.toAbsolutePath().normalize()
            val file = path.toAbsolutePath().normalize()
            return if (file.startsWith(workDir)) workDir.relativize(file).joinToString("/") else null
        }

        fun notifyFailed(project: Project, output: ToolOutput, result: PhanResult) {
            when (result) {
                PhanResult.PhpAstMissing ->
                    ToolNotifier.notifyFailure(project, PhanTool.SPEC, message("php.ast.title"), message("php.ast.details"))
                is PhanResult.Failed -> {
                    val exit = if (output.startFailed) "" else "exit ${output.exitCode}\n"
                    ToolNotifier.notifyFailure(project, PhanTool.SPEC, message("failure.title"), exit + ToolNotifier.excerpt(result.details))
                }
                is PhanResult.Ok -> Unit
            }
        }
    }
}

private const val BUNDLE = "messages.PhanBundle"

object PhanBundle : DynamicBundle(PhanBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}

/** Аннотатор Phan: только сохранённые файлы (в CLI нет подмены файла временным), `-I <файл>`. */
class PhanAnnotator : ToolExternalAnnotator() {
    override val spec = PhanTool.SPEC
    override val pairedInspectionShortName = PhanInspection.SHORT_NAME

    override fun state(project: Project): PhanState = PhanSettings.getInstance(project).state

    override fun analyze(input: AnnotationInput, tool: PreparedTool): ToolFindings? {
        val project = input.project
        val state = state(project)
        val relative = PhanSettings.relativeToWorkDir(tool, input.path) ?: return ToolFindings(emptyList())
        val args = PhanCommand.args(
            files = listOf(relative),
            allowPolyfillParser = state.allowPolyfillParser,
            memoryLimit = state.memoryLimit,
            config = PhanSettings.configTarget(project, tool, state),
            extraArgs = ParametersListUtil.parse(state.extraArgs.orEmpty()),
        )
        val output = tool.run(args)
        if (output.cancelled) return null
        if (output.timedOut) {
            ToolNotifier.notifyFailure(project, spec, message("timeout.title", state.timeoutSeconds), message("timeout.details"))
            return null
        }
        return when (val result = PhanOutput.parse(output.exitCode, output.stdout, output.stderr)) {
            is PhanResult.Ok -> ToolFindings(PhanOutput.issuesFor(result.issues, relative).map(::toProblem))
            else -> {
                PhanSettings.notifyFailed(project, output, result)
                null
            }
        }
    }

    private fun toProblem(issue: PhanIssue) = ToolProblem(
        line = issue.lineBegin,
        endLine = issue.lineEnd,
        column = issue.column,
        message = "Phan: ${issue.message}",
        tooltipHtml = tooltip(issue),
        weak = issue.isLow,
        fixes = if (issue.checkName.isNotBlank()) listOf(PhanSuppressFix(issue.lineBegin, issue.checkName)) else emptyList(),
    )

    companion object {
        fun tooltip(issue: PhanIssue): String = buildString {
            append("<html>Phan: ").append(StringUtil.escapeXmlEntities(issue.message))
            if (issue.checkName.isNotBlank()) {
                val name = StringUtil.escapeXmlEntities(issue.checkName)
                append("<br>")
                val url = PhanOutput.docUrl(issue.checkName)
                if (url != null) append("<a href=\"").append(url).append("\"><code>").append(name).append("</code></a>")
                else append("<code>").append(name).append("</code>")
                append(" · ").append(severityText(issue.severity))
            }
            append("</html>")
        }

        fun severityText(severity: Int): String = when {
            severity >= 10 -> message("severity.critical")
            severity >= 5 -> message("severity.normal")
            else -> message("severity.low")
        }
    }
}

/** Quick-fix `// @phan-suppress-next-line <Check>` (см. [PhanSuppress]). */
class PhanSuppressFix(private val line: Int, private val checkName: String) : IntentionAction {
    override fun getText(): String = message("fix.text", checkName)

    override fun getFamilyName(): String = message("fix.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.let { PsiDocumentManager.getInstance(project).getDocument(it) } ?: return
        val insert = PhanSuppress.edit(document.charsSequence, line, checkName) ?: return
        document.insertString(insert.offset, insert.text)
    }

    override fun startInWriteAction(): Boolean = true
}

/** Парная инспекция [PhanAnnotator]. */
class PhanInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String = SHORT_NAME

    companion object {
        const val SHORT_NAME = "PhanInspection"
    }
}

class PhanFileProblemsBanner : FileProblemsBanner(PhanTool.SPEC)

/** Windows: проект в WSL, а инструмент запускается локально — предложить WSL. */
class PhanWslSuggestion : dev.phptools.core.startup.WslSuggestion(PhanTool.SPEC) {
    override fun state(project: com.intellij.openapi.project.Project) = PhanSettings.getInstance(project).state
}

/** Проект в Docker Compose, а локально инструмент не запустится — предложить Docker-режим. */
class PhanDockerSuggestion : dev.phptools.core.docker.DockerSuggestion(PhanTool.SPEC) {
    override fun state(project: com.intellij.openapi.project.Project) = PhanSettings.getInstance(project).state
}
