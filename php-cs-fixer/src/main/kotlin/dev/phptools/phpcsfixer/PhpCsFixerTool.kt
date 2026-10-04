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

/** Windows: проект в WSL, а инструмент запускается локально — предложить WSL. */
class CsFixerWslSuggestion : dev.phptools.core.startup.WslSuggestion(PhpCsFixerTool.SPEC) {
    override fun state(project: com.intellij.openapi.project.Project) = CsFixerSettings.getInstance(project).state
}

/** Проект в Docker Compose, а локально инструмент не запустится — предложить Docker-режим. */
class CsFixerDockerSuggestion : dev.phptools.core.docker.DockerSuggestion(PhpCsFixerTool.SPEC) {
    override fun state(project: com.intellij.openapi.project.Project) = CsFixerSettings.getInstance(project).state
}
