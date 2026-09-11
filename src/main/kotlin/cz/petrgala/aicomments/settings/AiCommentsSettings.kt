package cz.petrgala.aicomments.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "AiCommentsSettings", storages = [Storage("aiComments.xml")])
class AiCommentsSettings : SimplePersistentStateComponent<AiCommentsSettings.State>(State()) {

    class State : BaseState() {
        var showGutterIcons by property(true)
        var autoRefresh by property(true)
        var debounceMs by property(500)
        var maxCommentLength by property(500)
        var showToolWindow by property(true)
        var hideResolved by property(false)
    }

    fun publishChanged(project: Project) {
        project.messageBus.syncPublisher(AiCommentsSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val TOOL_WINDOW_ID = "AI Comments"

        fun getInstance(project: Project): AiCommentsSettings = project.service()
    }
}
