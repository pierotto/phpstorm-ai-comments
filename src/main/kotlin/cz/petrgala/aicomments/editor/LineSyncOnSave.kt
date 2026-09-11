package cz.petrgala.aicomments.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths

class LineSyncOnSave : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        EditorFactory.getInstance().getEditors(document)
            .mapNotNull { editor -> editor.project?.let { it to editor } }
            .distinctBy { (project, _) -> project }
            .forEach { (project, editor) ->
                if (project.isDisposed) return@forEach
                val relativePath = ProjectPaths.relativePath(project, file) ?: return@forEach
                val store = project.service<CommentStore>()
                val stored = store.commentsFor(relativePath).associate { it.id to it.line }
                val moved = project.service<CommentMarkerManager>().markers(editor)
                    .filter { it.isValid }
                    .flatMap { marker ->
                        val line = document.getLineNumber(marker.startOffset) + 1
                        marker.getUserData(CommentMarkerManager.COMMENT_IDS_KEY).orEmpty()
                            .filter { stored[it] != null && stored[it] != line }
                            .map { it to line }
                    }
                    .toMap()
                store.updateLines(relativePath, moved)
            }
    }
}
