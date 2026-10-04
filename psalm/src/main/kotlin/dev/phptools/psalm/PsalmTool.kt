package dev.phptools.psalm

import dev.phptools.core.ToolSpec
import dev.phptools.core.startup.BuiltInAnalyzerCheck

object PsalmTool {
    val SPEC = ToolSpec(
        id = "psalm",
        displayName = "Psalm",
        binaryName = "psalm",
        configurableId = "dev.phptools.psalm",
        builtInInspectionShortName = "PsalmExternalAnalyzer",
    )
}

class PsalmBuiltInCheck : BuiltInAnalyzerCheck(PsalmTool.SPEC)
