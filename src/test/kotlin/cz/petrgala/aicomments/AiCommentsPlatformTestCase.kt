package cz.petrgala.aicomments

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.petrgala.aicomments.storage.CommentStore
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files
import java.nio.file.Path

abstract class AiCommentsPlatformTestCase : BasePlatformTestCase() {

    protected lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        val path = ProjectPaths.commentsFile(project)
        Files.deleteIfExists(path)
        refreshVfs(path)
        store = project.service<CommentStore>()
        store.reload()
    }

    override fun tearDown() {
        try {
            val path = ProjectPaths.commentsFile(project)
            Files.deleteIfExists(path)
            refreshVfs(path)
        } finally {
            super.tearDown()
        }
    }

    // The light fixture is shared between test classes and keeps a stale VFS entry for the deleted file otherwise.
    private fun refreshVfs(path: Path) {
        LocalFileSystem.getInstance().refreshIoFiles(listOf(path.toFile()), false, false, null)
    }
}
