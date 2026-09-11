package cz.petrgala.aicomments.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import cz.petrgala.aicomments.storage.CommentStore

class AiCommentsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CommentStore>().reload()
    }
}
