package dev.phptools.core.annotator

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Файлы, анализ которых пропущен, потому что документ не был сохранён.
 * У каждого плагина свой classloader, поэтому и свой экземпляр.
 */
object PendingRehighlight {
    private val urls = ConcurrentHashMap.newKeySet<String>()

    fun add(file: VirtualFile) {
        urls.add(file.url)
    }

    fun remove(file: VirtualFile): Boolean = urls.remove(file.url)
}

/** После сохранения перезапускает подсветку файлов из [PendingRehighlight]. Регистрируется в plugin.xml каждого плагина. */
class RehighlightOnSave : FileDocumentManagerListener {
    override fun afterDocumentSaved(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!PendingRehighlight.remove(file)) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed || !ProjectFileIndex.getInstance(project).isInContent(file)) continue
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: continue
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "PHP tools: file saved")
        }
    }
}
