package cz.petrgala.aicomments.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.settings.AiCommentsSettingsListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.CommentsChangedListener
import cz.petrgala.aicomments.storage.ProjectPaths

@Service(Service.Level.PROJECT)
class CommentMarkerManager(private val project: Project) : Disposable {

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh(event.editor)
            }
        }, this)
        val connection = project.messageBus.connect(this)
        connection.subscribe(CommentsChangedListener.TOPIC, object : CommentsChangedListener {
            override fun commentsChanged(snapshot: CommentsFile) = refreshAll()
        })
        connection.subscribe(AiCommentsSettingsListener.TOPIC, object : AiCommentsSettingsListener {
            override fun settingsChanged() = refreshAll()
        })
        refreshAll()
    }

    fun refreshAll() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({ refreshAll() }, project.disposed)
            return
        }
        EditorFactory.getInstance().allEditors
            .filter { it.project == project }
            .forEach { refresh(it) }
    }

    fun refresh(editor: Editor) {
        val document = editor.document
        val currentLines = currentLines(editor)
        clear(editor)
        if (!AiCommentsSettings.getInstance(project).state.showGutterIcons) return
        val fileDocumentManager = FileDocumentManager.getInstance()
        val file = fileDocumentManager.getFile(document) ?: return
        val relativePath = ProjectPaths.relativePath(project, file) ?: return
        val unsaved = fileDocumentManager.isDocumentUnsaved(document)
        val markers = project.service<CommentStore>().threadsFor(relativePath)
            .groupBy { thread -> if (unsaved) currentLines[thread.newest.id] ?: thread.line else thread.line }
            .filterKeys { it in 1..document.lineCount }
            .map { (line, threads) ->
                editor.markupModel.addLineHighlighter(null, line - 1, HighlighterLayer.LAST).apply {
                    gutterIconRenderer = CommentGutterIconRenderer(project, threads)
                    putUserData(COMMENT_IDS_KEY, threads.flatMap { thread -> thread.records.map { it.id } })
                }
            }
        editor.putUserData(MARKERS_KEY, markers)
    }

    fun markers(editor: Editor): List<RangeHighlighter> = editor.getUserData(MARKERS_KEY).orEmpty()

    private fun currentLines(editor: Editor): Map<String, Int> = markers(editor)
        .filter { it.isValid }
        .flatMap { marker ->
            val line = editor.document.getLineNumber(marker.startOffset) + 1
            marker.getUserData(COMMENT_IDS_KEY).orEmpty().map { it to line }
        }
        .toMap()

    private fun clear(editor: Editor) {
        markers(editor).forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(MARKERS_KEY, null)
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { clear(it) }
    }

    companion object {
        val COMMENT_IDS_KEY: Key<List<String>> = Key.create("cz.petrgala.aicomments.commentIds")
        private val MARKERS_KEY: Key<List<RangeHighlighter>> = Key.create("cz.petrgala.aicomments.markers")
    }
}
