package dev.phptools.phpstan

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import dev.phptools.core.annotator.FileProblemsBanner

/**
 * Парная инспекция [PhpStanAnnotator]: включает и выключает PHPStan в Settings | Editor | Inspections
 * и подключает его к Code | Inspect Code.
 */
class PhpStanInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    override fun getShortName(): String = SHORT_NAME

    companion object {
        const val SHORT_NAME = "PhpStanInspection"
    }
}

/** Полоса над редактором для ошибок PHPStan уровня файла. */
class PhpStanFileProblemsBanner : FileProblemsBanner(PhpStanTool.SPEC)
