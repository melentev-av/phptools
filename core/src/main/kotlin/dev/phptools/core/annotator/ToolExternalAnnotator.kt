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
import com.intellij.ui.EditorNotifications
import dev.phptools.core.ToolSpec
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.exec.ToolLocator
import dev.phptools.core.settings.ToolState

/**
 * Базовый аннотатор внешнего инструмента. Включается и выключается парной инспекцией
 * ([pairedInspectionShortName]), severity тоже берётся из профиля инспекций.
 * Когда запускаться в редакторе, решает `ToolState.checkMode` (см. [CheckPolicy]).
 */
abstract class ToolExternalAnnotator : ExternalAnnotator<AnnotationInput, AnnotationResult>() {

    abstract val spec: ToolSpec

    /** shortName парной инспекции `LocalInspectionTool(), ExternalAnnotatorBatchInspection`. */
    abstract val pairedInspectionShortName: String

    abstract fun state(project: Project): ToolState

    /**
     * Умеет ли инструмент сейчас анализировать несохранённый буфер (через [PreparedTool.runWithTempFile] и т.п.).
     * Режим проверки учитывает база; здесь — только возможности инструмента.
     */
    open fun canAnalyzeUnsaved(project: Project): Boolean = false

    /** Запуск инструмента и разбор вывода. Вызывается в фоне, без read action. `null` — результата нет. */
    abstract fun analyze(input: AnnotationInput, tool: PreparedTool): ToolFindings?

    override fun getPairedBatchInspectionShortName(): String = pairedInspectionShortName

    override fun collectInformation(file: PsiFile): AnnotationInput? = collect(file, inEditor = false)

    // hasErrors игнорируем: PHP-плагин в бете и может давать ложные ошибки парсинга.
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): AnnotationInput? =
        collect(file, inEditor = true)

    private fun collect(psiFile: PsiFile, inEditor: Boolean): AnnotationInput? {
        val file = psiFile.originalFile.virtualFile ?: return null
        if (!file.isInLocalFileSystem || !file.extension.equals("php", ignoreCase = true)) return null
        if (file.path.contains("/vendor/")) return null

        val project = psiFile.project
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(file) || index.isInLibrary(file) || index.isExcluded(file)) return null

        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file) ?: return null
        val unsaved = documentManager.isDocumentUnsaved(document)

        val mode = state(project).checkMode
        return when (CheckPolicy.decide(mode, inEditor, unsaved, canAnalyzeUnsaved(project))) {
            CheckDecision.SKIP -> null
            CheckDecision.DEFER -> {
                PendingRehighlight.add(file)
                null
            }
            CheckDecision.ANALYZE ->
                AnnotationInput(project, file, file.toNioPath(), document.text, unsaved, document.modificationStamp)
        }
    }

    override fun doAnnotate(input: AnnotationInput?): AnnotationResult? {
        if (input == null || input.project.isDisposed) return null
        val tool = ToolLocator.prepare(input.project, spec, state(input.project), input.file) ?: return null
        val findings = analyze(input, tool)
        ProgressManager.checkCanceled()
        return findings?.let { AnnotationResult(it, input.stamp) }
    }

    override fun apply(file: PsiFile, result: AnnotationResult?, holder: AnnotationHolder) {
        if (result == null) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        if (document.modificationStamp != result.stamp) return // результат устарел

        file.virtualFile?.let { virtualFile ->
            if (FileProblems.set(spec, virtualFile, result.findings.fileMessages)) {
                EditorNotifications.getInstance(file.project).updateNotifications(virtualFile)
            }
        }
        if (result.findings.problems.isEmpty()) return

        val severity = profileSeverity(file)
        val ranges = ProblemRanges(document.charsSequence)
        for (problem in result.findings.problems) {
            val range = ranges.compute(problem.line, problem.endLine, problem.column, problem.endColumn, problem.byteColumns)
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
