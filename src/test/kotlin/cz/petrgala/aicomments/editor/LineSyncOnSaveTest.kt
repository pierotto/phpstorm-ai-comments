package cz.petrgala.aicomments.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import cz.petrgala.aicomments.AiCommentsPlatformTestCase
import cz.petrgala.aicomments.storage.ProjectPaths
import java.nio.file.Files

class LineSyncOnSaveTest : AiCommentsPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        project.service<CommentMarkerManager>()
    }

    fun testInsertingLinesAboveACommentUpdatesItsLineOnSave() {
        myFixture.configureByText("Foo.php", "a\nb\nc\n")
        val comment = store.add("Foo.php", 3, "text")!!

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "x\ny\n")
        }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        assertEquals(5, store.commentsFor("Foo.php").single { it.id == comment.id }.line)
    }

    fun testRefreshWhileUnsavedKeepsMovedMarkers() {
        myFixture.configureByText("Foo.php", "a\nb\nc\n")
        val moved = store.add("Foo.php", 3, "text")!!

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "x\ny\n")
        }
        val other = store.add("Foo.php", 1, "other")!!
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        val comments = store.commentsFor("Foo.php")
        assertEquals(5, comments.single { it.id == moved.id }.line)
        assertEquals(1, comments.single { it.id == other.id }.line)
    }

    fun testReplyWhileUnsavedKeepsTheThreadOnTheMovedLine() {
        myFixture.configureByText("Foo.php", "a\nb\nc\n")
        val first = store.add("Foo.php", 3, "text")!!

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "x\ny\n")
        }
        val reply = store.reply(first.threadId, "again")!!
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        val comments = store.commentsFor("Foo.php")
        assertEquals(5, comments.single { it.id == first.id }.line)
        assertEquals(5, comments.single { it.id == reply.id }.line)
    }

    fun testUnchangedLinesDoNotWrite() {
        myFixture.configureByText("Foo.php", "a\nb\n")
        store.add("Foo.php", 2, "text")
        val before = Files.readString(ProjectPaths.commentsFile(project))

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "z\n")
        }
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)

        assertEquals(before, Files.readString(ProjectPaths.commentsFile(project)))
    }
}
