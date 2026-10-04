package dev.phptools.phpcsfixer

import dev.phptools.core.ToolSpec
import dev.phptools.core.startup.BuiltInAnalyzerCheck

object PhpCsFixerTool {
    val SPEC = ToolSpec(
        id = "php-cs-fixer",
        displayName = "PHP-CS-Fixer",
        binaryName = "php-cs-fixer",
        configurableId = "dev.phptools.phpcsfixer",
        builtInInspectionShortName = "CsFixerExternalAnalyzer",
    )
}

class PhpCsFixerBuiltInCheck : BuiltInAnalyzerCheck(PhpCsFixerTool.SPEC)
