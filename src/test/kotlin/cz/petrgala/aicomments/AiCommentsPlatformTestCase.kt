package cz.petrgala.aicomments

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

abstract class AiCommentsPlatformTestCase : BasePlatformTestCase() {

    protected lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(ProjectPaths.commentsFile(project))
        store = project.service<CommentStore>()
        store.reload()
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(ProjectPaths.commentsFile(project))
        } finally {
            super.tearDown()
        }
    }
}
