package dev.phptools.phpstan

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.phptools.phpstan.PhpStanBundle.message

/** Quick-fix «игнорировать ошибку»: `// @phpstan-ignore <identifier>` над строкой (см. [IgnoreComment]). */
class PhpStanIgnoreFix(private val line: Int, private val identifier: String) : IntentionAction {
    override fun getText(): String = message("fix.text", identifier)

    override fun getFamilyName(): String = message("fix.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: file?.let { PsiDocumentManager.getInstance(project).getDocument(it) } ?: return
        val insert = IgnoreComment.edit(document.charsSequence, line, identifier) ?: return
        document.insertString(insert.offset, insert.text)
    }

    override fun startInWriteAction(): Boolean = true
}
