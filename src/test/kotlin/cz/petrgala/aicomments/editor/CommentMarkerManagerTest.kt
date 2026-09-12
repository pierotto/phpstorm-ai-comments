package cz.petrgala.aicomments.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.ui.UIUtil
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

        store.resolve(c.threadId)

        assertSame(AiCommentsIcons.Resolved, markers.markers(myFixture.editor).single().gutterIconRenderer!!.icon)
    }

    fun testTwoCommentsOnOneLineYieldOneMarkerWithHighestStatus() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val first = store.add("Foo.php", 2, "first")!!
        store.resolve(first.threadId)
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

    fun testReplyKeepsOneMarkerWithTheThreadStatus() {
        myFixture.configureByText("Foo.php", "<?php\nline 2\n")
        val first = store.add("Foo.php", 2, "first")!!
        store.resolve(first.threadId)

        store.reply(first.threadId, "again")

        val marker = markers.markers(myFixture.editor).single()
        assertSame(AiCommentsIcons.Open, marker.gutterIconRenderer!!.icon)
        assertEquals(2, marker.getUserData(CommentMarkerManager.COMMENT_IDS_KEY)!!.size)
        assertEquals(1, (marker.gutterIconRenderer as CommentGutterIconRenderer).threads.size)
    }

    fun testResolvedThreadIsStillClickable() {
        myFixture.configureByText("Foo.php", "<?php\n")
        val c = store.add("Foo.php", 1, "text")!!

        store.resolve(c.threadId)

        val renderer = markers.markers(myFixture.editor).single().gutterIconRenderer as CommentGutterIconRenderer
        assertNotNull(renderer.clickAction)
        assertTrue(renderer.isNavigateAction)
    }

    fun testReloadFromDiskReanchorsTheMarkerAndWritesTheLine() {
        myFixture.configureByText("Foo.php", "a\nb\n'winstrom' => [\nc\n")
        val c = store.add("Foo.php", 3, "text")!!
        val document = myFixture.editor.document
        val file = FileDocumentManager.getInstance().getFile(document)!!

        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(file, "<?php\nuse X;\na\nb\nClientInterface::ENVELOPE => [\nc\n")
        }
        UIUtil.dispatchAllInvocationEvents()

        val marker = markers.markers(myFixture.editor).single()
        assertTrue(marker.isValid)
        assertEquals(4, document.getLineNumber(marker.startOffset))
        assertEquals(5, store.commentsFor("Foo.php").single { it.id == c.id }.line)
    }

    fun testWholeTextReplaceInEditorReanchorsTheMarkerAndSyncsOnSave() {
        myFixture.configureByText("Foo.php", "a\nb\n'winstrom' => [\nc\n")
        val c = store.add("Foo.php", 3, "text")!!
        val document = myFixture.editor.document as DocumentEx

        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceText("<?php\na\nb\nClientInterface::ENVELOPE => [\nc\n", document.modificationStamp + 1)
        }
        UIUtil.dispatchAllInvocationEvents()

        val marker = markers.markers(myFixture.editor).single()
        assertTrue(marker.isValid)
        assertEquals(3, document.getLineNumber(marker.startOffset))
        assertEquals(3, store.commentsFor("Foo.php").single { it.id == c.id }.line)

        FileDocumentManager.getInstance().saveDocument(document)

        assertEquals(4, store.commentsFor("Foo.php").single { it.id == c.id }.line)
    }
}
