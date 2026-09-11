package cz.petrgala.aicomments.watcher

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths

@Service(Service.Level.PROJECT)
class CommentsFileWatcher(private val project: Project) : Disposable {

    private val watchedPath = FileUtil.toSystemIndependentName(ProjectPaths.commentsFile(project).toString())
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        VirtualFileManager.getInstance().addAsyncFileListener(AsyncFileListener { events ->
            if (!AiCommentsSettings.getInstance(project).state.autoRefresh) return@AsyncFileListener null
            if (events.none(::isCommentsFileEvent)) return@AsyncFileListener null
            object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() = scheduleReload()
            }
        }, this)
    }

    fun isCommentsFileEvent(event: VFileEvent): Boolean {
        if (FileUtil.pathsEqual(event.path, watchedPath)) return true
        // The VFS reports a newly created .claude directory as one event without child events.
        if (event is VFileCreateEvent && event.isDirectory) {
            val dirPath = FileUtil.toSystemIndependentName(event.path)
            if (watchedPath.startsWith("$dirPath/")) return true
        }
        val oldPath = when (event) {
            is VFileMoveEvent -> event.oldPath
            is VFilePropertyChangeEvent -> if (event.isRename) event.oldPath else null
            else -> null
        }
        return oldPath != null && FileUtil.pathsEqual(oldPath, watchedPath)
    }

    fun scheduleReload() {
        alarm.cancelAllRequests()
        alarm.addRequest({ project.service<CommentStore>().reload() }, AiCommentsSettings.getInstance(project).state.debounceMs)
    }

    override fun dispose() = Unit
}
