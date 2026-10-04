package dev.phptools.core.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

data class AnnotationInput(
    val project: Project,
    val file: VirtualFile,
    val path: Path,
    val text: String,
    /** Документ не сохранён: анализировать нужно [text], а не файл на диске. */
    val unsaved: Boolean,
    val stamp: Long,
)

data class ToolProblem(
    /** 1-based. */
    val line: Int,
    val endLine: Int = line,
    /** 1-based; `null` — подсветить строку без ведущих пробелов. */
    val column: Int? = null,
    /** 1-based, не включительно (символ на этой позиции уже не подсвечивается). `null` — до конца [endLine]. */
    val endColumn: Int? = null,
    /** Колонки в байтах UTF-8, а не в символах (так считает, например, Psalm). */
    val byteColumns: Boolean = false,
    val message: String,
    val tooltipHtml: String? = null,
    /** Info-уровень: severity не выше WEAK_WARNING. */
    val weak: Boolean = false,
    val fixes: List<IntentionAction> = emptyList(),
)

/** Результат одного запуска инструмента по файлу. */
data class ToolFindings(
    val problems: List<ToolProblem>,
    /** Ошибки уровня файла, без строки: показываются полосой над редактором. */
    val fileMessages: List<String> = emptyList(),
)

data class AnnotationResult(val findings: ToolFindings, val stamp: Long)
