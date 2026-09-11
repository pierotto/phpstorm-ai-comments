package cz.petrgala.aicomments.editor

import com.intellij.openapi.components.service
import cz.petrgala.aicomments.AiCommentsPlatformTestCase
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class CommentMarkerManagerTest : AiCommentsPlatformTestCase() {

    private lateinit var markers: CommentMarkerManager

    override fun setUp() {
        super.setUp()
        markers = project.service<CommentMarkerManager>()
    }

    fun testOpenCommentGetsMarkerOnItsLine() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\nline 3\n")

        store.add("Foo.php", 2, "use FQN")

        val list = markers.markers(myFixture.editor)
        assertEquals(1, list.size)
        assertEquals(1, myFixture.editor.document.getLineNumber(list[0].startOffset))
        assertSame(AiCommentsIcons.Open, list[0].gutterIconRenderer!!.icon)
    }

    fun testResolvedCommentShowsResolvedIcon() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val c = store.add("Foo.php", 1, "text")!!

        store.resolve(c.id)

        assertSame(AiCommentsIcons.Resolved, markers.markers(myFixture.editor).single().gutterIconRenderer!!.icon)
    }

    fun testTwoCommentsOnOneLineYieldOneMarkerWithHighestStatus() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val first = store.add("Foo.php", 2, "first")!!
        store.resolve(first.id)
        store.add("Foo.php", 2, "second")

        val list = markers.markers(myFixture.editor)
        assertEquals(1, list.size)
        assertSame(AiCommentsIcons.Open, list[0].gutterIconRenderer!!.icon)
        assertEquals(2, list[0].getUserData(CommentMarkerManager.COMMENT_IDS_KEY)!!.size)
    }

    fun testLineBeyondDocumentGetsNoMarker() {
        myFixture.configureByText("Foo.php", "<?php\n")

        store.add("Foo.php", 40, "text")

        assertTrue(markers.markers(myFixture.editor).isEmpty())
    }

    fun testDeletingTheFileRemovesMarkers() {
        myFixture.configureByText("Foo.php", "<?php\n")
        store.add("Foo.php", 1, "text")
        assertEquals(1, markers.markers(myFixture.editor).size)

        Files.delete(ProjectPaths.commentsFile(project))
        store.reload()

        assertTrue(markers.markers(myFixture.editor).isEmpty())
    }
}
