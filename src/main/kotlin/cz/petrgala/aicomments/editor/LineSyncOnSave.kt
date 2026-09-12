package cz.petrgala.aicomments.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManagerListener

class LineSyncOnSave : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        EditorFactory.getInstance().getEditors(document)
            .mapNotNull { editor -> editor.project?.let { it to editor } }
            .distinctBy { (project, _) -> project }
            .forEach { (project, editor) ->
                if (!project.isDisposed) project.service<CommentMarkerManager>().syncLines(editor)
            }
    }
}
