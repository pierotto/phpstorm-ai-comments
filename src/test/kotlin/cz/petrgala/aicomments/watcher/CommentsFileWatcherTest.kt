package cz.petrgala.aicomments.watcher

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import cz.petrgala.aicomments.AiCommentsPlatformTestCase
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class CommentsFileWatcherTest : AiCommentsPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        project.service<CommentsFileWatcher>()
    }

    fun testExternalWriteIsPickedUpAfterDebounce() {
        val path = ProjectPaths.commentsFile(project)
        Files.createDirectories(path.parent)
        Files.writeString(path, """
            {"version":1,"metadata":{"projectName":"p","created":"2026-09-11T10:00:00Z","lastModified":"2026-09-11T10:00:00Z"},
             "comments":{"Foo.php":[{"id":"c_1_0","line":1,"author":"human","text":"external","status":"open","created":"2026-09-11T10:00:00Z","processed":false,"processedAt":null,"claudeResponse":null}]}}
        """.trimIndent())
        assertTrue(store.commentsFor("Foo.php").isEmpty())

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        PlatformTestUtil.waitWithEventsDispatching("comments not reloaded", { store.commentsFor("Foo.php").size == 1 }, 5)

        assertEquals("external", store.commentsFor("Foo.php")[0].text)
    }
}
