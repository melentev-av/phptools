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

/** Windows: проект в WSL, а инструмент запускается локально — предложить WSL. */
class PhpStanWslSuggestion : dev.phptools.core.startup.WslSuggestion(PhpStanTool.SPEC) {
    override fun state(project: com.intellij.openapi.project.Project) = PhpStanSettings.getInstance(project).state
}
