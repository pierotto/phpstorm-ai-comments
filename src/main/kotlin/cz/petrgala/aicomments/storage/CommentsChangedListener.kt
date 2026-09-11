package cz.petrgala.aicomments.storage

import com.intellij.util.messages.Topic
import cz.petrgala.aicomments.model.CommentsFile

interface CommentsChangedListener {
    fun commentsChanged(snapshot: CommentsFile)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<CommentsChangedListener> =
            Topic.create("AI comments changed", CommentsChangedListener::class.java)
    }
}
