package dev.phptools.phpstan

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.execution.ParametersListUtil
import dev.phptools.core.annotator.AnnotationInput
import dev.phptools.core.annotator.PendingRehighlight
import dev.phptools.core.annotator.ToolExternalAnnotator
import dev.phptools.core.annotator.ToolFindings
import dev.phptools.core.annotator.ToolProblem
import dev.phptools.core.exec.PreparedTool
import dev.phptools.core.exec.ToolOutput
import dev.phptools.core.notify.ToolNotifier
import dev.phptools.phpstan.PhpStanBundle.message
import java.nio.file.Path

class PhpStanAnnotator : ToolExternalAnnotator() {
    override val spec = PhpStanTool.SPEC
    override val pairedInspectionShortName = PhpStanInspection.SHORT_NAME

    override fun state(project: Project): PhpStanState = PhpStanSettings.getInstance(project).state

    override fun canAnalyzeUnsaved(project: Project): Boolean = !PhpStanRuntime.isTmpFileUnsupported(project)

    override fun analyze(input: AnnotationInput, tool: PreparedTool): ToolFindings? {
        val project = input.project
        val state = state(project)
        val target = tool.toTarget(input.path)
        val config = PhpStanLevels.configTarget(project, tool, state)
        val level = PhpStanLevels.levelArgument(project, tool, state, config)
        val extra = ParametersListUtil.parse(state.extraArgs.orEmpty())

        fun args(tmp: String?) = PhpStanCommand.analyseArgs(target, state.memoryLimit, config, level, extra, tmp)

        val output = if (input.unsaved) tool.runWithTempFile(input.text, "php") { tmp -> args(tmp) } else tool.run(args(null))
        if (output.cancelled) return null
        if (output.timedOut) {
            ToolNotifier.notifyFailure(project, spec, message("timeout.title", state.timeoutSeconds), ToolNotifier.excerpt(output.stderr))
            return null
        }
        return when (val result = PhpStanOutput.parse(output.exitCode, output.stdout, output.stderr)) {
            is PhpStanResult.Ok -> {
                if (result.errors.isNotEmpty()) {
                    ToolNotifier.notifyFailure(project, spec, message("config.errors.title"), ToolNotifier.excerpt(result.errors.joinToString("\n")))
                }
                toFindings(PhpStanOutput.messagesFor(result.files, relativePath(project, input.path)))
            }
            PhpStanResult.NoFiles -> ToolFindings(emptyList())
            PhpStanResult.TmpFileUnsupported -> {
                PhpStanRuntime.markTmpFileUnsupported(project)
                PendingRehighlight.add(input.file)
                ToolNotifier.notifyInfo(project, spec, message("tmpfile.title"), message("tmpfile.details"))
                null
            }
            is PhpStanResult.Failed -> {
                notifyFailed(project, output, result.details)
                null
            }
        }
    }

    private fun notifyFailed(project: Project, output: ToolOutput, details: String) {
        val text = if ("No rules detected" in details) message("no.level.hint") + "\n\n" + details else details
        val exit = if (output.startFailed) "" else "exit ${output.exitCode}\n"
        ToolNotifier.notifyFailure(project, spec, message("failure.title"), exit + ToolNotifier.excerpt(text))
    }

    private fun toFindings(messages: List<PhpStanMessage>): ToolFindings {
        val (lineLess, withLine) = messages.partition { it.line == null }
        val problems = withLine.map { m ->
            val fixes = if (m.ignorable && m.identifier != null) listOf(PhpStanIgnoreFix(m.line!!, m.identifier)) else emptyList()
            ToolProblem(line = m.line!!, message = "PHPStan: ${m.message}", tooltipHtml = tooltip(m), fixes = fixes)
        }
        return ToolFindings(problems, lineLess.map { it.message })
    }

    private fun relativePath(project: Project, path: Path): String {
        val base = project.basePath?.let { Path.of(it) } ?: return path.fileName.toString()
        return if (path.startsWith(base)) base.relativize(path).joinToString("/") else path.toString()
    }

    companion object {
        /** HTML: сообщение; `tip` серым; идентификатор ссылкой на документацию phpstan.org. */
        fun tooltip(m: PhpStanMessage): String = buildString {
            append("<html>PHPStan: ").append(StringUtil.escapeXmlEntities(m.message))
            m.tip?.takeIf { it.isNotBlank() }?.let {
                append("<br><span style=\"color:gray\">").append(StringUtil.escapeXmlEntities(it)).append("</span>")
            }
            m.identifier?.let {
                val id = StringUtil.escapeXmlEntities(it)
                append("<br><a href=\"").append(identifierUrl(it)).append("\"><code>").append(id).append("</code></a>")
            }
            append("</html>")
        }

        fun identifierUrl(identifier: String) =
            "https://phpstan.org/error-identifiers/" + java.net.URLEncoder.encode(identifier, Charsets.UTF_8)
    }
}
