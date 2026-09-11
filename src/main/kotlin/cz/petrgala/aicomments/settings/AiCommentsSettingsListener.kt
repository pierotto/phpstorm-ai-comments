package cz.petrgala.aicomments.settings

import com.intellij.util.messages.Topic

interface AiCommentsSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AiCommentsSettingsListener> =
            Topic.create("AI comments settings changed", AiCommentsSettingsListener::class.java)
    }
}
