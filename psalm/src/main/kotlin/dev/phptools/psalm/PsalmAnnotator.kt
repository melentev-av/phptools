package dev.phptools.psalm

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.annotator.AnnotationInput
import dev.phptools.core.annotator.FileProblemsBanner
import dev.phptools.core.annotator.ToolExternalAnnotator
import dev.phptools.core.annotator.ToolFindings
import dev.phptools.core.annotator.ToolProblem
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.psalm.PsalmBundle.message
import java.nio.file.Path

/**
 * Аннотатор Psalm. Несохранённый буфер Psalm CLI анализировать не умеет (нет аналога `--tmp-file`),
 * поэтому `canAnalyzeUnsaved = false`: режим «При вводе» работает как «При сохранении».
 */
class PsalmAnnotator : ToolExternalAnnotator() {
    override val spec = PsalmTool.SPEC
    override val pairedInspectionShortName = PsalmInspection.SHORT_NAME

    override fun state(project: Project): PsalmState = PsalmSettings.getInstance(project).state

    override fun analyze(input: AnnotationInput, tool: PreparedTool): ToolFindings? {
        val project = input.project
        val state = state(project)
        val args = PsalmCommand.args(
            targets = listOf(tool.toTarget(input.path)),
            threads = state.threads,
            showInfo = state.showInfo,
            config = PsalmSettings.configTarget(project, tool, state),
            extraArgs = ParametersListUtil.parse(state.extraArgs.orEmpty()),
        )
        val output = tool.run(args)
        if (output.cancelled) return null
        if (output.timedOut) {
            ToolNotifier.notifyFailure(project, spec, message("timeout.title", state.timeoutSeconds), ToolNotifier.excerpt(output.stderr))
            return null
        }
        return when (val result = PsalmOutput.parse(output.exitCode, output.stdout, output.stderr)) {
            is PsalmResult.Ok -> ToolFindings(PsalmOutput.issuesFor(result.issues, relativePath(project, input.path)).map(::toProblem))
            is PsalmResult.Failed -> {
                val exit = if (output.startFailed) "" else "exit ${output.exitCode}\n"
                ToolNotifier.notifyFailure(project, spec, message("failure.title"), exit + ToolNotifier.excerpt(result.details))
                null
            }
        }
    }

    private fun toProblem(issue: PsalmIssue) = ToolProblem(
        line = issue.lineFrom,
        endLine = issue.lineTo,
        column = issue.columnFrom,
        endColumn = issue.columnTo,
        byteColumns = true, // Psalm считает колонки в байтах UTF-8
        message = "Psalm: ${issue.message}",
        tooltipHtml = tooltip(issue),
        weak = issue.isInfo,
        fixes = if (issue.type.isNotBlank()) listOf(PsalmSuppressFix(issue.lineFrom, issue.type)) else emptyList(),
    )

    private fun relativePath(project: Project, path: Path): String {
        val base = project.basePath?.let { Path.of(it) } ?: return path.fileName.toString()
        return if (path.startsWith(base)) base.relativize(path).joinToString("/") else path.toString()
    }

    companion object {
        fun tooltip(issue: PsalmIssue): String = buildString {
            append("<html>Psalm: ").append(StringUtil.escapeXmlEntities(issue.message))
            if (issue.type.isNotBlank()) {
                val type = StringUtil.escapeXmlEntities(issue.type)
                append("<br>")
                if (issue.link != null) {
                    append("<a href=\"").append(StringUtil.escapeXmlEntities(issue.link)).append("\"><code>").append(type).append("</code></a>")
                } else {
                    append("<code>").append(type).append("</code>")
                }
            }
            append("</html>")
        }
    }
}

/** Quick-fix `@psalm-suppress <type>` над строкой проблемы (см. [PsalmSuppress]). */
class PsalmSuppressFix(private val line: Int, private val type: String) : IntentionAction {
    override fun getText(): String = message("fix.text", type)

    override fun getFamilyName(): String = message("fix.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.let { PsiDocumentManager.getInstance(project).getDocument(it) } ?: return
        val insert = PsalmSuppress.edit(document.charsSequence, line, type) ?: return
        document.insertString(insert.offset, insert.text)
    }

    override fun startInWriteAction(): Boolean = true
}

/** Парная инспекция [PsalmAnnotator]: Settings | Editor | Inspections и Code | Inspect Code. */
class PsalmInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String = SHORT_NAME

    companion object {
        const val SHORT_NAME = "PsalmInspection"
    }
}

class PsalmFileProblemsBanner : FileProblemsBanner(PsalmTool.SPEC)
