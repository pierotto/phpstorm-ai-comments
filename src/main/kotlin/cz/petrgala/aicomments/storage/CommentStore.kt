package cz.petrgala.aicomments.storage

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.find
import cz.petrgala.aicomments.model.nextId
import cz.petrgala.aicomments.model.withComment
import cz.petrgala.aicomments.model.withLines
import cz.petrgala.aicomments.model.withStatus
import java.io.IOException

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) {

    private val log = thisLogger()
    private val repository = CommentsFileRepository(
        path = ProjectPaths.commentsFile(project),
        projectName = project.name,
        onSkippedRecord = { log.warn("comments.json: skipped record ($it)") },
    )

    @Volatile
    private var snapshot: CommentsFile = CommentsFile.empty(project.name, repository.now())

    @Volatile
    var isReadOnly: Boolean = false
        private set

    fun snapshot(): CommentsFile = snapshot

    fun commentsFor(relativePath: String): List<Comment> = snapshot.comments[relativePath].orEmpty()

    fun add(relativePath: String, line: Int, text: String): Comment? = mutate { file ->
        val comment = newComment(file, line, text)
        file.withComment(relativePath, comment) to comment
    }

    fun resolve(id: String) {
        mutate { file -> file.withStatus(id, CommentStatus.RESOLVED) to Unit }
    }

    fun followUp(originalId: String, text: String): Comment? = mutate { file ->
        val original = file.find(originalId) ?: return@mutate file to null
        val comment = newComment(file, original.comment.line, text)
        file.withStatus(originalId, CommentStatus.RESOLVED).withComment(original.path, comment) to comment
    }

    fun updateLines(relativePath: String, idToLine: Map<String, Int>) {
        if (idToLine.isEmpty()) return
        mutate { file -> file.withLines(relativePath, idToLine) to Unit }
    }

    fun reload() {
        try {
            when (val result = repository.load()) {
                is LoadResult.Loaded -> update(result.file, readOnly = false)
                LoadResult.Missing -> update(CommentsFile.empty(project.name, repository.now()), readOnly = false)
                is LoadResult.Malformed -> {
                    update(CommentsFile.empty(project.name, repository.now()), readOnly = true)
                    notifyBroken("comments.json is malformed: ${result.reason}", offerReset = true)
                }
                is LoadResult.UnsupportedVersion -> {
                    update(CommentsFile.empty(project.name, repository.now()), readOnly = true)
                    notifyBroken("comments.json version ${result.version} is not supported by this plugin", offerReset = false)
                }
            }
        } catch (e: IOException) {
            log.warn("comments.json read failed", e)
            notify("Could not read comments.json: ${e.message}", NotificationType.ERROR)
        }
    }

    fun reset() {
        try {
            val backup = repository.backupBroken()
            refreshVfs()
            reload()
            if (backup != null) notify("Previous file kept as ${backup.fileName}", NotificationType.INFORMATION)
        } catch (e: IOException) {
            log.warn("comments.json reset failed", e)
            notify("Could not reset comments.json: ${e.message}", NotificationType.ERROR)
        }
    }

    private fun newComment(file: CommentsFile, line: Int, text: String) = Comment(
        id = file.nextId(repository.epochSeconds()),
        line = line,
        author = Comment.HUMAN,
        text = text,
        status = CommentStatus.OPEN,
        created = repository.now(),
        processedAt = null,
        claudeResponse = null,
    )

    private fun <T> mutate(change: (CommentsFile) -> Pair<CommentsFile, T>): T? {
        if (isReadOnly) {
            notify("comments.json cannot be written until it is fixed or reset", NotificationType.WARNING)
            return null
        }
        var produced: T? = null
        return try {
            val written = repository.mutate { file ->
                val (updated, result) = change(file)
                produced = result
                updated
            }
            update(written, readOnly = false)
            refreshVfs()
            produced
        } catch (e: CommentsFileUnavailableException) {
            reload()
            null
        } catch (e: IOException) {
            log.warn("comments.json write failed", e)
            notify("Could not write comments.json: ${e.message}", NotificationType.ERROR)
            null
        }
    }

    private fun update(file: CommentsFile, readOnly: Boolean) {
        snapshot = file
        isReadOnly = readOnly
        val publish = Runnable {
            if (!project.isDisposed) project.messageBus.syncPublisher(CommentsChangedListener.TOPIC).commentsChanged(file)
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) publish.run() else app.invokeLater(publish, project.disposed)
    }

    private fun refreshVfs() {
        LocalFileSystem.getInstance().refreshIoFiles(listOf(repository.path.parent.toFile()), true, true, null)
    }

    private fun notifyBroken(message: String, offerReset: Boolean) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI Comments", message, NotificationType.ERROR)
        if (offerReset) {
            notification.addAction(NotificationAction.createSimpleExpiring("Reset file") { reset() })
        }
        notification.addAction(NotificationAction.createSimpleExpiring("Open file") {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repository.path)?.let {
                OpenFileDescriptor(project, it).navigate(true)
            }
        })
        notification.notify(project)
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("AI Comments", message, type)
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "AI Comments"
    }
}
