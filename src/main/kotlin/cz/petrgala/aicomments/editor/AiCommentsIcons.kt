package cz.petrgala.aicomments.editor

import com.intellij.openapi.util.IconLoader
import cz.petrgala.aicomments.model.CommentStatus
import javax.swing.Icon

object AiCommentsIcons {
    @JvmField val Open: Icon = IconLoader.getIcon("/icons/comment-open.svg", AiCommentsIcons::class.java)
    @JvmField val Processed: Icon = IconLoader.getIcon("/icons/comment-processed.svg", AiCommentsIcons::class.java)
    @JvmField val Resolved: Icon = IconLoader.getIcon("/icons/comment-resolved.svg", AiCommentsIcons::class.java)
    @JvmField val ToolWindow: Icon = IconLoader.getIcon("/icons/toolwindow.svg", AiCommentsIcons::class.java)

    fun forStatus(status: CommentStatus): Icon = when (status) {
        CommentStatus.OPEN -> Open
        CommentStatus.PROCESSED -> Processed
        CommentStatus.RESOLVED -> Resolved
    }
}
