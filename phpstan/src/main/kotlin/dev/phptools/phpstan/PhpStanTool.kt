package dev.phptools.phpstan

import dev.phptools.core.ToolSpec
import dev.phptools.core.startup.BuiltInAnalyzerCheck

object PhpStanTool {
    val SPEC = ToolSpec(
        id = "phpstan",
        displayName = "PHPStan",
        binaryName = "phpstan",
        configurableId = "dev.phptools.phpstan",
        builtInInspectionShortName = "PhpStanExternalAnalyzer",
    )
}

class PhpStanBuiltInCheck : BuiltInAnalyzerCheck(PhpStanTool.SPEC)
