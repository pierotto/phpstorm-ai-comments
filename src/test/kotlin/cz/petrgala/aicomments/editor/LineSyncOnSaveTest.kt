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
