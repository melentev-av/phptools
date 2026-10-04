package dev.phptools.phpcsfixer

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.phptools.core.annotator.AnnotationInput
import dev.phptools.core.annotator.FileProblemsBanner
import dev.phptools.core.annotator.ToolExternalAnnotator
import dev.phptools.core.annotator.ToolFindings
import dev.phptools.core.annotator.ToolProblem
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.phpcsfixer.CsFixerBundle.message

/**
 * Подсветка мест, которые PHP-CS-Fixer поменял бы. Код всегда передаётся через stdin (`fix --dry-run -`),
 * поэтому diff соответствует содержимому документа один в один — и для несохранённого файла тоже.
 */
class CsFixerAnnotator : ToolExternalAnnotator() {
    override val spec = PhpCsFixerTool.SPEC
    override val pairedInspectionShortName = PhpCsFixerInspection.SHORT_NAME

    override fun state(project: Project): CsFixerState = CsFixerSettings.getInstance(project).state

    override fun canAnalyzeUnsaved(project: Project): Boolean = true

    override fun analyze(input: AnnotationInput, tool: PreparedTool): ToolFindings? {
        val project = input.project
        val state = state(project)
        // Через stdin Finder не работает — исключённые в конфиге файлы отсеиваем сами.
        if (CsFixerFinder.contains(project, tool, state, input.path) == false) return ToolFindings(emptyList())

        val args = CsFixerCommand.dryRunArgs(
            listOf(CsFixerCommand.STDIN),
            CsFixerRun.configTarget(project, tool, state),
            state.allowRisky,
            CsFixerRun.extraArgs(state),
        )
        val output = tool.run(args, stdin = input.text)
        if (output.cancelled) return null
        if (output.timedOut) {
            ToolNotifier.notifyFailure(project, spec, message("timeout.title", state.timeoutSeconds), ToolNotifier.excerpt(output.stderr))
            return null
        }
        return when (val result = CsFixerOutput.parse(output.exitCode, output.stdout, output.stderr)) {
            is CsFixerResult.Ok -> findings(result.files.firstOrNull(), input.stamp)
            CsFixerResult.SyntaxError -> ToolFindings(emptyList())
            is CsFixerResult.Failed -> {
                val exit = if (output.startFailed) "" else "exit ${output.exitCode}\n"
                ToolNotifier.notifyFailure(project, spec, message("failure.title"), exit + ToolNotifier.excerpt(result.details))
                null
            }
        }
    }

    private fun findings(file: CsFixerFile?, stamp: Long): ToolFindings {
        file ?: return ToolFindings(emptyList())
        val hunks = CsFixerDiff.parse(file.diff)
        val fixers = file.fixers.joinToString(", ").ifEmpty { message("fixers.unknown") }
        val applyAll = ApplyAllHunksFix(hunks, stamp)
        val problems = hunks.flatMap { hunk ->
            val tooltip = "<html>PHP-CS-Fixer: ${StringUtil.escapeXmlEntities(fixers)}<pre>${StringUtil.escapeXmlEntities(hunk.text)}</pre></html>"
            hunk.changedOldLines.map { range ->
                ToolProblem(
                    line = range.first,
                    endLine = range.last,
                    message = "PHP-CS-Fixer: $fixers",
                    tooltipHtml = tooltip,
                    fixes = listOf(ApplyHunkFix(hunk, stamp), applyAll),
                )
            }
        }
        return ToolFindings(problems)
    }
}

/** Применить один кусок diff прямо из результата анализа, без повторного запуска процесса. */
class ApplyHunkFix(private val hunk: Hunk, private val expectedStamp: Long) : IntentionAction {
    override fun getText(): String = message("fix.hunk")

    override fun getFamilyName(): String = message("fix.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        document(project, editor, file)?.modificationStamp == expectedStamp

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = document(project, editor, file) ?: return
        if (document.modificationStamp != expectedStamp) return
        val r = CsFixerDiff.replacementFor(document.charsSequence, hunk) ?: return
        document.replaceString(r.start, r.end, r.text)
    }

    override fun startInWriteAction(): Boolean = true
}

/** Применить все куски файла с конца к началу одной правкой (один шаг Undo). */
class ApplyAllHunksFix(private val hunks: List<Hunk>, private val expectedStamp: Long) : IntentionAction {
    override fun getText(): String = message("fix.all")

    override fun getFamilyName(): String = message("fix.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        hunks.isNotEmpty() && document(project, editor, file)?.modificationStamp == expectedStamp

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = document(project, editor, file) ?: return
        if (document.modificationStamp != expectedStamp) return
        for (hunk in hunks.sortedByDescending { it.oldStart }) {
            val r = CsFixerDiff.replacementFor(document.charsSequence, hunk) ?: return
            document.replaceString(r.start, r.end, r.text)
        }
    }

    override fun startInWriteAction(): Boolean = true
}

private fun document(project: Project, editor: Editor?, file: PsiFile?): Document? =
    editor?.document ?: file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

/** Парная инспекция [CsFixerAnnotator]. */
class PhpCsFixerInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String = SHORT_NAME

    companion object {
        const val SHORT_NAME = "PhpCsFixerInspection"
    }
}

class CsFixerFileProblemsBanner : FileProblemsBanner(PhpCsFixerTool.SPEC)
