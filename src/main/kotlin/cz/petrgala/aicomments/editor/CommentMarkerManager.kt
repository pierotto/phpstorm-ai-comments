package cz.petrgala.aicomments.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.settings.AiCommentsSettingsListener
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.CommentsChangedListener
import cz.petrgala.aicomments.storage.ProjectPaths

@Service(Service.Level.PROJECT)
class CommentMarkerManager(private val project: Project) : Disposable {

    private val log = thisLogger()

    init {
        val editorFactory = EditorFactory.getInstance()
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh(event.editor)
            }
        }, this)
        editorFactory.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) = rememberLines(event.document)
            override fun documentChanged(event: DocumentEvent) = reanchorInvalidMarkers(event)
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

    fun refresh(editor: Editor, lineOverrides: Map<String, Int>? = null) {
        val document = editor.document
        val fileDocumentManager = FileDocumentManager.getInstance()
        val lines = lineOverrides ?: if (fileDocumentManager.isDocumentUnsaved(document)) currentLines(editor) else emptyMap()
        clear(editor)
        if (!AiCommentsSettings.getInstance(project).state.showGutterIcons) return
        val file = fileDocumentManager.getFile(document) ?: return
        val relativePath = ProjectPaths.relativePath(project, file) ?: return
        val markers = project.service<CommentStore>().threadsFor(relativePath)
            .groupBy { thread -> thread.records.firstNotNullOfOrNull { lines[it.id] } ?: thread.line }
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

    fun syncLines(editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relativePath = ProjectPaths.relativePath(project, file) ?: return
        val store = project.service<CommentStore>()
        val stored = store.commentsFor(relativePath).associate { it.id to it.line }
        val moved = currentLines(editor).filter { (id, line) -> stored[id] != null && stored[id] != line }
        store.updateLines(relativePath, moved)
    }

    private fun rememberLines(document: Document) {
        val editor = editorsWithMarkers(document).firstOrNull() ?: return
        document.putUserData(LINES_BEFORE_CHANGE_KEY, currentLines(editor))
    }

    private fun reanchorInvalidMarkers(event: DocumentEvent) {
        val document = event.document
        val editors = editorsWithMarkers(document)
            .filter { editor -> event.isWholeTextReplaced || markers(editor).any { !it.isValid } }
        if (editors.isEmpty()) return
        val before = document.getUserData(LINES_BEFORE_CHANGE_KEY).orEmpty()
        val newText = document.immutableCharSequence
        val oldText = StringBuilder(newText).replace(event.offset, event.offset + event.newLength, event.oldFragment.toString())
        val translated = HashMap<String, Int>()
        for (editor in editors) {
            val lines = currentLines(editor) + markers(editor)
                .filter { !it.isValid }
                .flatMap { it.getUserData(COMMENT_IDS_KEY).orEmpty() }
                .mapNotNull { id -> before[id]?.let { line -> id to translateLine(oldText, newText, line) } }
            translated.putAll(lines)
        }
        ApplicationManager.getApplication().invokeLater({
            editors.filter { !it.isDisposed }.forEach { editor ->
                refresh(editor, translated)
                if (!FileDocumentManager.getInstance().isDocumentUnsaved(document)) syncLines(editor)
            }
        }, project.disposed)
    }

    private fun translateLine(oldText: CharSequence, newText: CharSequence, line: Int): Int = try {
        Diff.translateLine(oldText, newText, line - 1, true) + 1
    } catch (e: FilesTooBigForDiffException) {
        log.warn("comment line could not be translated after a document change", e)
        line
    }

    private fun editorsWithMarkers(document: Document): List<Editor> =
        EditorFactory.getInstance().getEditors(document, project).filter { markers(it).isNotEmpty() }

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
        private val LINES_BEFORE_CHANGE_KEY: Key<Map<String, Int>> = Key.create("cz.petrgala.aicomments.linesBeforeChange")
    }
}
