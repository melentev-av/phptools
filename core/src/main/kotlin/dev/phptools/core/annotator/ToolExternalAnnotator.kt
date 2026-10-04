package dev.phptools.core.annotator

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.settings.ToolState

/**
 * Базовый аннотатор внешнего инструмента. Включается и выключается парной инспекцией
 * ([pairedInspectionShortName]), severity тоже берётся из профиля инспекций.
 */
abstract class ToolExternalAnnotator : ExternalAnnotator<AnnotationInput, AnnotationResult>() {

    abstract val spec: ToolSpec

    /** shortName парной инспекции `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. */
    abstract val pairedInspectionShortName: String

    abstract fun state(project: Project): ToolState

    /** Умеет ли инструмент анализировать несохранённый буфер (через [PreparedTool.runWithTempFile] и т.п.). */
    open fun canAnalyzeUnsaved(project: Project): Boolean = false

    /** Запуск инструмента и разбор вывода. Вызывается в фоне, без read action. `null` — результата нет. */
    abstract fun analyze(input: AnnotationInput, tool: PreparedTool): List<ToolProblem>?

    override fun getPairedBatchInspectionShortName(): String = pairedInspectionShortName

    override fun collectInformation(file: PsiFile): AnnotationInput? = collect(file)

    // hasErrors игнорируем: PHP-плагин в бете и может давать ложные ошибки парсинга.
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): AnnotationInput? = collect(file)

    private fun collect(psiFile: PsiFile): AnnotationInput? {
        val file = psiFile.originalFile.virtualFile ?: return null
        if (!file.isInLocalFileSystem || !file.extension.equals("php", ignoreCase = true)) return null
        if (file.path.contains("/vendor/")) return null

        val project = psiFile.project
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(file) || index.isInLibrary(file) || index.isExcluded(file)) return null

        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file) ?: return null
        val unsaved = documentManager.isDocumentUnsaved(document)
        if (unsaved && !canAnalyzeUnsaved(project)) {
            PendingRehighlight.add(file)
            return null
        }
        return AnnotationInput(project, file, file.toNioPath(), document.text, unsaved, document.modificationStamp)
    }

    override fun doAnnotate(input: AnnotationInput?): AnnotationResult? {
        if (input == null || input.project.isDisposed) return null
        val tool = ToolLocator.prepare(input.project, spec, state(input.project), input.file) ?: return null
        val problems = analyze(input, tool)
        ProgressManager.checkCanceled()
        return problems?.let { AnnotationResult(it, input.stamp) }
    }

    override fun apply(file: PsiFile, result: AnnotationResult?, holder: AnnotationHolder) {
        if (result == null || result.problems.isEmpty()) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        if (document.modificationStamp != result.stamp) return // результат устарел

        val severity = profileSeverity(file)
        val ranges = ProblemRanges(document.charsSequence)
        for (problem in result.problems) {
            val range = ranges.compute(problem.line, problem.endLine, problem.column, problem.endColumn)
            val problemSeverity = if (problem.weak && severity > HighlightSeverity.WEAK_WARNING) HighlightSeverity.WEAK_WARNING else severity
            var builder = holder.newAnnotation(problemSeverity, problem.message)
                .range(TextRange(range.first, range.last + 1))
            problem.tooltipHtml?.let { builder = builder.tooltip(it) }
            for (fix in problem.fixes) builder = builder.withFix(fix)
            builder.create()
        }
    }

    private fun profileSeverity(file: PsiFile): HighlightSeverity {
        val key = HighlightDisplayKey.find(pairedInspectionShortName) ?: return HighlightSeverity.WARNING
        return InspectionProjectProfileManager.getInstance(file.project).currentProfile.getErrorLevel(key, file).severity
    }
}
