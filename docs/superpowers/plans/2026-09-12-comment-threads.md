# Comment Threads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a comment into a thread (human message → Claude reply → human reply → …) shown in one dialog, resolvable and reopenable by hand, and make the Claude Code skill trigger on the phrases the user actually says.

**Architecture:** Records in `.claude/comments.json` stay flat (format version 1); each gets a `threadId`, and a thread is the list of records sharing it, ordered by `created`. The thread's status is the status of its newest record; older records are always `resolved`. The plugin groups records into `CommentThread` values in the model layer; gutter, tool window, dialog and store work with threads. The skill answers the newest open record of each thread and never resolves.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (PhpStorm 2024.1+), Gson, JUnit 5 for unit tests, `BasePlatformTestCase` for platform tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-12-comment-threads-design.md`

## Global Constraints

- File format version stays `1`; a record without `threadId` decodes with `threadId == id`.
- The plugin writes `threadId` on every record it saves; key order in a record: `id, threadId, line, author, text, status, created, processed, processedAt, claudeResponse`.
- Claude never sets `resolved`; only the human does, from the dialog.
- Everything in the repo (code, comments, commit messages, docs) is in English.
- No comments in code unless they explain a non-obvious why.
- Run tests with `./gradlew test --tests '<fqcn>'` (JDK 21). Unit tests (`@Test`, JUnit 5) live next to platform tests (`fun testX()`, `BasePlatformTestCase`) — keep each new test in the style of the file it goes into.
- Commit after every task; commit messages end with the attribution lines from the session (`Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` and the `Claude-Session:` line).

## File structure

| File | Responsibility |
|---|---|
| `model/Comment.kt` | one record; gains `threadId` |
| `model/CommentThread.kt` (new) | a thread: path, ordered records, derived line/status/newest |
| `model/CommentsFileOps.kt` | pure functions on `CommentsFile`: grouping into threads, `withReply`, `countThreads` |
| `storage/CommentsFileCodec.kt` | JSON ↔ model; `threadId` decode/encode |
| `storage/CommentStore.kt` | project service; `reply`, `resolve` per thread |
| `ui/CommentTextInput.kt` (new) | text area + counter + length validation, shared by both dialogs |
| `ui/AddCommentDialog.kt` | new comment only (no follow-up flag) |
| `ui/ThreadDialog.kt` (new, replaces `ViewCommentDialog.kt`) | shows the thread, reply field, Reply / Resolve / Close |
| `ui/CommentWorkflow.kt` | `addComment`, `openThread` |
| `editor/CommentMarkerManager.kt`, `editor/CommentGutterIconRenderer.kt` | one marker per line, one icon for the threads on it |
| `toolwindow/CommentsTreeModel.kt`, `toolwindow/AiCommentsPanel.kt` | one node per thread, thread counts |
| `claude/skills/process-ai-comments/SKILL.md` | triggers + thread procedure |
| `README.md`, `src/test/resources/comments.sample.json` | docs and sample |

---

### Task 1: `threadId` on the record and in the codec

**Files:**
- Modify: `src/main/kotlin/cz/petrgala/aicomments/model/Comment.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/storage/CommentsFileCodec.kt`
- Modify: `src/test/resources/comments.sample.json`
- Test: `src/test/kotlin/cz/petrgala/aicomments/storage/CommentsFileCodecTest.kt`

**Interfaces:**
- Produces: `Comment.threadId: String` (last constructor parameter, default `= id`), so every existing positional `Comment(...)` call keeps compiling.

- [ ] **Step 1: Write the failing tests**

Add to `CommentsFileCodecTest`:

```kotlin
    @Test
    fun `threadId round trips and defaults to id when missing`() {
        val json = """
            {"version":1,"comments":{"a.php":[
                {"id":"c_1_0","line":1,"text":"first","status":"resolved","created":"2026-09-11T10:00:00Z"},
                {"id":"c_1_1","threadId":"c_1_0","line":1,"text":"again","status":"open","created":"2026-09-11T10:05:00Z"}
            ]}}
        """.trimIndent()
        val codec = CommentsFileCodec()

        val file = codec.decode(json)
        val encoded = codec.encode(file)

        val records = file.comments.getValue("a.php")
        assertEquals("c_1_0", records[0].threadId)
        assertEquals("c_1_0", records[1].threadId)
        assertTrue(encoded.contains("\"threadId\": \"c_1_0\""))
        assertEquals(file, codec.decode(encoded))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileCodecTest'`
Expected: compilation error — `threadId` is not a member of `Comment`.

- [ ] **Step 3: Add the field and the codec support**

`Comment.kt`:

```kotlin
data class Comment(
    val id: String,
    val line: Int,
    val author: String,
    val text: String,
    val status: CommentStatus,
    val created: String,
    val processedAt: String?,
    val claudeResponse: String?,
    val threadId: String = id,
) {
```

`CommentsFileCodec.decodeComment` — after `val created = …`:

```kotlin
        return Comment(
            id = id,
            line = line,
            author = o.optString("author") ?: Comment.HUMAN,
            text = text,
            status = status,
            created = created,
            processedAt = o.optString("processedAt"),
            claudeResponse = o.optString("claudeResponse"),
            threadId = o.optString("threadId") ?: id,
        )
```

`CommentsFileCodec.encodeComment` — insert right after `addProperty("id", c.id)`:

```kotlin
        addProperty("threadId", c.threadId)
```

`comments.sample.json`: add `"threadId"` after `"id"` in every record. The second record on line 45 is a reply to the first, so it gets the first one's id and the first one becomes `resolved`:

```json
      {
        "id": "c_1726061700_0",
        "threadId": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "zde je potřeba FQN",
        "status": "resolved",
        "created": "2026-09-11T10:05:00Z",
        "processed": true,
        "processedAt": "2026-09-11T10:10:00Z",
        "claudeResponse": "Replaced the short class name with the fully qualified one."
      },
      {
        "id": "c_1726061700_1",
        "threadId": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "to OK není, FQN nemusí být tady",
        "status": "open",
        "created": "2026-09-11T10:15:00Z",
        "processed": false,
        "processedAt": null,
        "claudeResponse": null
      },
```

The other two records get `"threadId"` equal to their own `"id"`.

- [ ] **Step 4: Run the codec tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentsFileCodecTest'`
Expected: PASS. (`decodes the sample file` asserts index 0 is `OPEN` and `processed == false` — update those two assertions to `RESOLVED` / `assertTrue(first.processed)` / `assertEquals("Replaced the short class name with the fully qualified one.", first.claudeResponse)` since the sample changed.)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/model/Comment.kt src/main/kotlin/cz/petrgala/aicomments/storage/CommentsFileCodec.kt src/test/resources/comments.sample.json src/test/kotlin/cz/petrgala/aicomments/storage/CommentsFileCodecTest.kt
git commit -m "Add threadId to comment records"
```

---

### Task 2: `CommentThread` and thread operations

**Files:**
- Create: `src/main/kotlin/cz/petrgala/aicomments/model/CommentThread.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/model/CommentsFileOps.kt`
- Test: `src/test/kotlin/cz/petrgala/aicomments/model/CommentsFileOpsTest.kt`

**Interfaces:**
- Consumes: `Comment.threadId` (Task 1).
- Produces:
  - `data class CommentThread(val path: String, val records: List<Comment>)` with `id`, `line`, `newest`, `first`, `status`.
  - `CommentsFile.threadsFor(path: String): List<CommentThread>` — threads of one file, records ordered by `created` then `id`.
  - `CommentsFile.threads(): List<CommentThread>` — all files.
  - `CommentsFile.thread(threadId: String): CommentThread?`
  - `CommentsFile.withReply(reply: Comment): CommentsFile` — resolves the newest record of `reply.threadId` and appends `reply` to the same path; unchanged file when the thread does not exist.
  - `CommentsFile.countThreads(status: CommentStatus): Int` — replaces `count`.

- [ ] **Step 1: Write the failing tests**

Replace the `find and count` test in `CommentsFileOpsTest` and add thread tests. Update the helper so records can carry a `threadId`:

```kotlin
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN, threadId: String = id, created: String = now) =
        Comment(id, line, Comment.HUMAN, "text $id", status, created, null, null, threadId)

    @Test
    fun `find and countThreads`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 1, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
            .withComment("a.php", comment("c_1_2", 2))

        assertEquals("a.php", file.find("c_1_1")!!.path)
        assertNull(file.find("nope"))
        assertEquals(1, file.countThreads(CommentStatus.OPEN))
        assertEquals(1, file.countThreads(CommentStatus.PROCESSED))
        assertEquals(0, file.countThreads(CommentStatus.RESOLVED))
    }

    @Test
    fun `threadsFor groups by threadId and orders records by created`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_1", 1, CommentStatus.OPEN, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
            .withComment("a.php", comment("c_1_0", 1, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_2", 5))

        val threads = file.threadsFor("a.php")

        assertEquals(listOf("c_1_0", "c_1_2"), threads.map { it.id })
        val first = threads[0]
        assertEquals(listOf("c_1_0", "c_1_1"), first.records.map { it.id })
        assertEquals("c_1_1", first.newest.id)
        assertEquals("c_1_0", first.first.id)
        assertEquals(CommentStatus.OPEN, first.status)
        assertEquals(1, first.line)
        assertEquals("a.php", first.path)
        assertEquals(emptyList<CommentThread>(), file.threadsFor("missing.php"))
    }

    @Test
    fun `threads and thread cover every file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 1))
            .withComment("b.php", comment("c_1_1", 2))

        assertEquals(listOf("c_1_0", "c_1_1"), file.threads().map { it.id }.sorted())
        assertEquals("b.php", file.thread("c_1_1")!!.path)
        assertNull(file.thread("nope"))
    }

    @Test
    fun `withReply resolves the newest record and appends the reply to the same file`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 4, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 4, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))
        val reply = comment("c_1_2", 4, CommentStatus.OPEN, threadId = "c_1_0", created = "2026-09-11T10:02:00Z")

        val updated = file.withReply(reply)

        val thread = updated.thread("c_1_0")!!
        assertEquals(listOf("c_1_0", "c_1_1", "c_1_2"), thread.records.map { it.id })
        assertEquals(CommentStatus.RESOLVED, updated.find("c_1_1")!!.comment.status)
        assertEquals(CommentStatus.OPEN, thread.status)
        assertEquals(file, file.withReply(comment("c_9_0", 1, threadId = "nope")))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.model.CommentsFileOpsTest'`
Expected: compilation error — `CommentThread`, `threadsFor`, `withReply`, `countThreads` do not exist.

- [ ] **Step 3: Implement**

`CommentThread.kt`:

```kotlin
package cz.petrgala.aicomments.model

data class CommentThread(val path: String, val records: List<Comment>) {
    val id: String get() = first.threadId
    val first: Comment get() = records.first()
    val newest: Comment get() = records.last()
    val line: Int get() = newest.line
    val status: CommentStatus get() = newest.status
}
```

`CommentsFileOps.kt` — replace `count` with `countThreads` and add the thread functions:

```kotlin
fun CommentsFile.threadsFor(path: String): List<CommentThread> =
    comments[path].orEmpty()
        .groupBy { it.threadId }
        .map { (_, records) -> CommentThread(path, records.sortedWith(compareBy({ it.created }, { it.id }))) }
        .sortedWith(compareBy({ it.first.created }, { it.id }))

fun CommentsFile.threads(): List<CommentThread> = comments.keys.flatMap { threadsFor(it) }

fun CommentsFile.thread(threadId: String): CommentThread? = threads().firstOrNull { it.id == threadId }

fun CommentsFile.countThreads(status: CommentStatus): Int = threads().count { it.status == status }

fun CommentsFile.withReply(reply: Comment): CommentsFile {
    val thread = thread(reply.threadId) ?: return this
    return withStatus(thread.newest.id, CommentStatus.RESOLVED).withComment(thread.path, reply)
}
```

Keep `Located`, `all`, `find`, `withComment`, `withStatus`, `withLines`, `nextId` as they are. Delete `count`. `AiCommentsPanel.render` still calls `count` — change it now so the project compiles:

```kotlin
        summary.text = "Open ${snapshot.countThreads(CommentStatus.OPEN)} · Processed ${snapshot.countThreads(CommentStatus.PROCESSED)} · Resolved ${snapshot.countThreads(CommentStatus.RESOLVED)}"
```

and its import `cz.petrgala.aicomments.model.count` → `cz.petrgala.aicomments.model.countThreads`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.model.CommentsFileOpsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/model src/main/kotlin/cz/petrgala/aicomments/toolwindow/AiCommentsPanel.kt src/test/kotlin/cz/petrgala/aicomments/model/CommentsFileOpsTest.kt
git commit -m "Group comment records into threads"
```

---

### Task 3: Store — reply and resolve per thread

**Files:**
- Modify: `src/main/kotlin/cz/petrgala/aicomments/storage/CommentStore.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/ui/CommentWorkflow.kt` (only the `followUp` call, so the project compiles — the dialog is redone in Task 4)
- Test: `src/test/kotlin/cz/petrgala/aicomments/storage/CommentStoreTest.kt`

**Interfaces:**
- Consumes: `thread`, `withReply` (Task 2).
- Produces:
  - `CommentStore.reply(threadId: String, text: String): Comment?` — replaces `followUp`.
  - `CommentStore.resolve(threadId: String)` — resolves the newest record of the thread (same name as before; for a single-record thread `threadId == id`, so existing callers keep working).
  - `CommentStore.threadsFor(relativePath: String): List<CommentThread>`.

- [ ] **Step 1: Write the failing test**

Replace `testResolveAndFollowUp` in `CommentStoreTest`:

```kotlin
    fun testResolveAndReplyWorkOnTheThread() {
        val first = store.add("src/Foo.php", 3, "first")!!
        store.resolve(first.threadId)
        assertEquals(CommentStatus.RESOLVED, store.snapshot().find(first.id)!!.comment.status)

        val reply = store.reply(first.threadId, "not good enough")!!

        assertEquals(first.id, reply.threadId)
        assertEquals(CommentStatus.OPEN, reply.status)
        assertEquals(3, reply.line)
        val thread = store.threadsFor("src/Foo.php").single()
        assertEquals(listOf(first.id, reply.id), thread.records.map { it.id })
        assertEquals(CommentStatus.OPEN, thread.status)

        store.resolve(first.threadId)

        assertEquals(CommentStatus.RESOLVED, store.threadsFor("src/Foo.php").single().status)
        assertNull(store.reply("nope", "text"))
    }
```

Add `import cz.petrgala.aicomments.model.CommentThread` only if the file needs it (it does not; `threadsFor` returns it inferred).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentStoreTest'`
Expected: compilation error — `reply` / `threadsFor` do not exist.

- [ ] **Step 3: Implement**

In `CommentStore`, replace `resolve` and `followUp`, add `threadsFor`, extend `newComment`:

```kotlin
    fun threadsFor(relativePath: String): List<CommentThread> = snapshot.threadsFor(relativePath)

    fun resolve(threadId: String) {
        mutate { file ->
            val thread = file.thread(threadId) ?: return@mutate file to Unit
            file.withStatus(thread.newest.id, CommentStatus.RESOLVED) to Unit
        }
    }

    fun reply(threadId: String, text: String): Comment? = mutate { file ->
        val thread = file.thread(threadId) ?: return@mutate file to null
        val comment = newComment(file, thread.line, text, threadId)
        file.withReply(comment) to comment
    }
```

```kotlin
    private fun newComment(file: CommentsFile, line: Int, text: String, threadId: String? = null): Comment {
        val id = file.nextId(repository.epochSeconds())
        return Comment(
            id = id,
            line = line,
            author = Comment.HUMAN,
            text = text,
            status = CommentStatus.OPEN,
            created = repository.now(),
            processedAt = null,
            claudeResponse = null,
            threadId = threadId ?: id,
        )
    }
```

Imports: add `cz.petrgala.aicomments.model.CommentThread`, `cz.petrgala.aicomments.model.thread`, `cz.petrgala.aicomments.model.threadsFor`, `cz.petrgala.aicomments.model.withReply`; remove `cz.petrgala.aicomments.model.find` if no longer used.

`CommentWorkflow.openComment` — the `FOLLOW_UP` branch calls `store.followUp(comment.id, text)`; change it to `store.reply(comment.threadId, text)` so the project compiles. Task 4 replaces this whole function.

- [ ] **Step 4: Run the store tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.storage.CommentStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/storage/CommentStore.kt src/main/kotlin/cz/petrgala/aicomments/ui/CommentWorkflow.kt src/test/kotlin/cz/petrgala/aicomments/storage/CommentStoreTest.kt
git commit -m "Reply to and resolve comment threads in the store"
```

---

### Task 4: Thread dialog and workflow

**Files:**
- Create: `src/main/kotlin/cz/petrgala/aicomments/ui/CommentTextInput.kt`
- Create: `src/main/kotlin/cz/petrgala/aicomments/ui/ThreadDialog.kt`
- Delete: `src/main/kotlin/cz/petrgala/aicomments/ui/ViewCommentDialog.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/ui/AddCommentDialog.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/ui/CommentWorkflow.kt`

**Interfaces:**
- Consumes: `CommentThread`, `CommentStore.reply/resolve/threadsFor`, `CommentsFile.thread`.
- Produces:
  - `CommentWorkflow.openThread(project: Project, thread: CommentThread)` — used by the gutter (Task 5) and tool window (Task 6).
  - `CommentWorkflow.openComment(project, comment)` stays as a thin adapter (`store.snapshot().thread(comment.threadId)?.let { openThread(project, it) }`) until Task 5 removes it, so `CommentGutterIconRenderer` compiles in between.
  - `ThreadDialog(project, thread, maxLength)` with `sealed interface Outcome { Close; Resolve; data class Reply(val text: String) }` and `val outcome: Outcome` valid after `show()`.

Dialogs have no unit tests in this repo (they need a running IDE); verification is a manual run in Step 4.

- [ ] **Step 1: Extract the text input shared by both dialogs**

`CommentTextInput.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.UIUtil
import javax.swing.event.DocumentEvent

class CommentTextInput(private val maxLength: Int, rows: Int) {

    val textArea = JBTextArea(rows, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val counter = JBLabel("0 / $maxLength").apply { foreground = UIUtil.getContextHelpForeground() }

    val text: String get() = textArea.text.trim()

    init {
        textArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                counter.text = "${text.length} / $maxLength"
            }
        })
    }

    fun addTo(panel: Panel) {
        panel.row { cell(JBScrollPane(textArea)).align(AlignX.FILL) }
        panel.row { cell(counter).align(AlignX.RIGHT) }
    }

    fun validate(requiredMessage: String): ValidationInfo? {
        val length = text.length
        return when {
            length == 0 -> ValidationInfo(requiredMessage, textArea)
            length > maxLength -> ValidationInfo("Text is longer than $maxLength characters ($length)", textArea)
            length >= maxLength * WARN_RATIO -> ValidationInfo("Long text ($length of $maxLength characters)", textArea).asWarning().withOKEnabled()
            else -> null
        }
    }

    private companion object {
        const val WARN_RATIO = 0.8
    }
}
```

`AddCommentDialog.kt` becomes:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AddCommentDialog(project: Project, line: Int, maxLength: Int) : DialogWrapper(project) {

    private val input = CommentTextInput(maxLength, rows = 6)

    init {
        title = "Add AI Comment – Line $line"
        setOKButtonText("Add Comment")
        init()
    }

    fun showAndGetText(): String? = if (showAndGet()) input.text else null

    override fun createCenterPanel(): JComponent = panel {
        input.addTo(this)
        row {
            comment("Be concise. Claude will see this line, 5 lines before and after, and the file path.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = input.textArea

    override fun doValidate(): ValidationInfo? = input.validate("Comment text is required")
}
```

- [ ] **Step 2: Write the thread dialog**

`ThreadDialog.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class ThreadDialog(project: Project, private val thread: CommentThread, maxLength: Int) : DialogWrapper(project) {

    sealed interface Outcome {
        data object Close : Outcome
        data object Resolve : Outcome
        data class Reply(val text: String) : Outcome
    }

    private val input = CommentTextInput(maxLength, rows = 4)

    val outcome: Outcome
        get() = when (exitCode) {
            OK_EXIT_CODE -> Outcome.Reply(input.text)
            RESOLVE_EXIT_CODE -> Outcome.Resolve
            else -> Outcome.Close
        }

    init {
        title = "AI Comment Thread – Line ${thread.line}"
        setOKButtonText("Reply")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBScrollPane(messagesPanel()).apply { preferredSize = JBUI.size(600, 320) }).align(AlignX.FILL)
        }
        separator()
        row { label("Reply") }
        input.addTo(this)
    }

    private fun messagesPanel(): JComponent = panel {
        thread.records.forEach { record ->
            message("You", record.created, record.text)
            record.claudeResponse?.let { message("Claude", record.processedAt ?: record.created, it) }
        }
    }

    private fun Panel.message(author: String, timestamp: String, text: String) {
        row { label("$author · ${timestamp.take(16).replace('T', ' ')}").bold() }
        row {
            cell(JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(2, 8, 8, 0)
            }).align(AlignX.FILL)
        }
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf(okAction)
        if (thread.status != CommentStatus.RESOLVED) actions += resolveAction()
        actions += cancelAction.also { it.putValue(Action.NAME, "Close") }
        return actions.toTypedArray()
    }

    private fun resolveAction(): Action = object : DialogWrapperAction("Resolve") {
        override fun doAction(e: ActionEvent) = close(RESOLVE_EXIT_CODE)
    }

    override fun getPreferredFocusedComponent(): JComponent = input.textArea

    override fun doValidate(): ValidationInfo? = input.validate("Reply text is required")

    private companion object {
        const val RESOLVE_EXIT_CODE = NEXT_USER_EXIT_CODE
    }
}
```

`Comment` import is unused if the compiler complains — drop it. Delete `ViewCommentDialog.kt` (`git rm`).

- [ ] **Step 3: Rewrite the workflow**

`CommentWorkflow.kt`:

```kotlin
package cz.petrgala.aicomments.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.Comment
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.model.thread
import cz.petrgala.aicomments.settings.AiCommentsSettings
import cz.petrgala.aicomments.storage.CommentStore

object CommentWorkflow {

    fun addComment(project: Project, relativePath: String, line: Int) {
        val text = AddCommentDialog(project, line, maxLength(project)).showAndGetText() ?: return
        project.service<CommentStore>().add(relativePath, line, text)
    }

    fun openComment(project: Project, comment: Comment) {
        project.service<CommentStore>().snapshot().thread(comment.threadId)?.let { openThread(project, it) }
    }

    fun openThread(project: Project, thread: CommentThread) {
        val dialog = ThreadDialog(project, thread, maxLength(project))
        dialog.show()
        val store = project.service<CommentStore>()
        when (val outcome = dialog.outcome) {
            ThreadDialog.Outcome.Close -> Unit
            ThreadDialog.Outcome.Resolve -> store.resolve(thread.id)
            is ThreadDialog.Outcome.Reply -> store.reply(thread.id, outcome.text)
        }
    }

    private fun maxLength(project: Project): Int = AiCommentsSettings.getInstance(project).state.maxCommentLength
}
```

- [ ] **Step 4: Compile, run the whole suite, then check the dialog by hand**

Run: `./gradlew test`
Expected: PASS (nothing else changed behaviour yet).

Run: `./gradlew runIde`, open any project, add a comment on a line, click the blue dot:
- the dialog shows "You · <time>" and the text, a Reply field, buttons **Reply**, **Resolve**, **Close**;
- **Reply** with an empty field is disabled; typing enables it; **Reply** adds a record (`.claude/comments.json` shows two records with the same `threadId`, the first `resolved`);
- **Resolve** turns the dot gray. Clicking a gray check does nothing yet (Task 5).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/ui
git commit -m "Show a comment thread with reply and resolve"
```

---

### Task 5: Gutter — one marker per line, threads behind it

**Files:**
- Modify: `src/main/kotlin/cz/petrgala/aicomments/editor/CommentMarkerManager.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/editor/CommentGutterIconRenderer.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/ui/CommentWorkflow.kt` (remove `openComment`)
- Test: `src/test/kotlin/cz/petrgala/aicomments/editor/CommentMarkerManagerTest.kt`

**Interfaces:**
- Consumes: `CommentStore.threadsFor`, `CommentThread`, `CommentWorkflow.openThread`.
- Produces: `CommentGutterIconRenderer(project, threads: List<CommentThread>)` with `primary: CommentThread`; `COMMENT_IDS_KEY` still holds every record id on that line, so `LineSyncOnSave` is untouched.

- [ ] **Step 1: Write the failing tests**

Add to `CommentMarkerManagerTest`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.editor.CommentMarkerManagerTest'`
Expected: compilation error — `threads` is not a member of `CommentGutterIconRenderer`.

- [ ] **Step 3: Implement**

`CommentGutterIconRenderer.kt`:

```kotlin
package cz.petrgala.aicomments.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.model.priority
import cz.petrgala.aicomments.ui.CommentWorkflow
import javax.swing.Icon

class CommentGutterIconRenderer(
    private val project: Project,
    val threads: List<CommentThread>,
) : GutterIconRenderer(), DumbAware {

    val primary: CommentThread = threads.sortedWith(compareBy({ it.status.priority() }, { it.first.created })).first()
    val status: CommentStatus = primary.status

    override fun getIcon(): Icon = AiCommentsIcons.forStatus(status)

    override fun getTooltipText(): String {
        val text = primary.first.text
        val preview = text.take(TOOLTIP_TEXT_LENGTH) + if (text.length > TOOLTIP_TEXT_LENGTH) "…" else ""
        return when (status) {
            CommentStatus.OPEN -> "AI comment (open): $preview"
            CommentStatus.PROCESSED -> "AI comment (processed): response available"
            CommentStatus.RESOLVED -> "AI comment (resolved): $preview"
        }
    }

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            CommentWorkflow.openThread(project, primary)
        }
    }

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean =
        other is CommentGutterIconRenderer && other.threads == threads

    override fun hashCode(): Int = threads.hashCode()

    private companion object {
        const val TOOLTIP_TEXT_LENGTH = 80
    }
}
```

`CommentMarkerManager.refresh` — replace the `markers` computation:

```kotlin
        val markers = project.service<CommentStore>().threadsFor(relativePath)
            .groupBy { thread -> if (unsaved) currentLines[thread.newest.id] ?: thread.line else thread.line }
            .filterKeys { it in 1..document.lineCount }
            .map { (line, threads) ->
                editor.markupModel.addLineHighlighter(null, line - 1, HighlighterLayer.LAST).apply {
                    gutterIconRenderer = CommentGutterIconRenderer(project, threads)
                    putUserData(COMMENT_IDS_KEY, threads.flatMap { thread -> thread.records.map { it.id } })
                }
            }
```

`CommentWorkflow`: delete `openComment` and the now-unused imports (`Comment`, `thread`).

- [ ] **Step 4: Run the editor tests**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.editor.*'`
Expected: PASS, including the existing `LineSyncOnSaveTest` and `testTwoCommentsOnOneLineYieldOneMarkerWithHighestStatus` (two independent threads on one line still give one marker with the open icon and two ids).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/editor src/main/kotlin/cz/petrgala/aicomments/ui/CommentWorkflow.kt src/test/kotlin/cz/petrgala/aicomments/editor/CommentMarkerManagerTest.kt
git commit -m "Render gutter markers per thread and open resolved threads"
```

---

### Task 6: Tool window — one node per thread

**Files:**
- Modify: `src/main/kotlin/cz/petrgala/aicomments/toolwindow/CommentsTreeModel.kt`
- Modify: `src/main/kotlin/cz/petrgala/aicomments/toolwindow/AiCommentsPanel.kt`
- Test: `src/test/kotlin/cz/petrgala/aicomments/toolwindow/CommentsTreeModelTest.kt`

**Interfaces:**
- Consumes: `CommentsFile.threadsFor`, `CommentThread`, `CommentWorkflow.openThread`.
- Produces: `data class ThreadNode(val path: String, val thread: CommentThread, val outOfRange: Boolean)` replacing `CommentNode`.

- [ ] **Step 1: Write the failing tests**

In `CommentsTreeModelTest`, change the helper and every `CommentNode` → `ThreadNode`, `.comment.line` → `.thread.line`, and add one test:

```kotlin
    private fun comment(id: String, line: Int, status: CommentStatus = CommentStatus.OPEN, threadId: String = id, created: String = now) =
        Comment(id, line, Comment.HUMAN, "text", status, created, null, null, threadId)

    @Test
    fun `a thread is one node with the status of its newest record`() {
        val file = CommentsFile.empty("p", now)
            .withComment("a.php", comment("c_1_0", 5, CommentStatus.RESOLVED))
            .withComment("a.php", comment("c_1_1", 5, CommentStatus.PROCESSED, threadId = "c_1_0", created = "2026-09-11T10:01:00Z"))

        val root = CommentsTreeModel.build(file, hideResolved = true) { 100 }

        val nodes = children(children(root)[0]).map { it.userObject as ThreadNode }
        assertEquals(1, nodes.size)
        assertEquals(CommentStatus.PROCESSED, nodes[0].thread.status)
        assertEquals(1, (children(root)[0].userObject as FileNode).count)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'cz.petrgala.aicomments.toolwindow.CommentsTreeModelTest'`
Expected: compilation error — `ThreadNode` does not exist.

- [ ] **Step 3: Implement**

`CommentsTreeModel.kt`:

```kotlin
package cz.petrgala.aicomments.toolwindow

import cz.petrgala.aicomments.model.CommentStatus
import cz.petrgala.aicomments.model.CommentThread
import cz.petrgala.aicomments.model.CommentsFile
import cz.petrgala.aicomments.model.threadsFor
import javax.swing.tree.DefaultMutableTreeNode

data class FileNode(val path: String, val count: Int)

data class ThreadNode(val path: String, val thread: CommentThread, val outOfRange: Boolean)

object CommentsTreeModel {

    fun build(file: CommentsFile, hideResolved: Boolean, lineCountOf: (String) -> Int?): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        file.comments.keys.sorted().forEach { path ->
            val visible = file.threadsFor(path)
                .filter { !hideResolved || it.status != CommentStatus.RESOLVED }
                .sortedBy { it.line }
            if (visible.isEmpty()) return@forEach
            val lineCount = lineCountOf(path)
            val fileNode = DefaultMutableTreeNode(FileNode(path, visible.size))
            visible.forEach { thread ->
                val outOfRange = lineCount != null && thread.line > lineCount
                fileNode.add(DefaultMutableTreeNode(ThreadNode(path, thread, outOfRange)))
            }
            root.add(fileNode)
        }
        return root
    }
}
```

`AiCommentsPanel.kt` — `navigateToSelection` and the renderer:

```kotlin
    private fun navigateToSelection(): Boolean {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return false
        val target = node.userObject as? ThreadNode ?: return false
        val file = ProjectPaths.findFile(project, target.path) ?: return false
        OpenFileDescriptor(project, file, target.thread.line - 1, 0).navigate(true)
        return true
    }
```

```kotlin
                is ThreadNode -> {
                    val thread = data.thread
                    val text = thread.first.text
                    icon = AiCommentsIcons.forStatus(thread.status)
                    append("L${thread.line}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" · ${thread.status.json}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (thread.records.size > 1) append(" · ${thread.records.size} messages", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(" · ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(text.take(TEXT_PREVIEW_LENGTH) + if (text.length > TEXT_PREVIEW_LENGTH) "…" else "")
                    if (data.outOfRange) append("  (line out of range)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
```

- [ ] **Step 4: Run the tool-window tests and the whole suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cz/petrgala/aicomments/toolwindow src/test/kotlin/cz/petrgala/aicomments/toolwindow/CommentsTreeModelTest.kt
git commit -m "List one tool window node per comment thread"
```

---

### Task 7: Skill triggers and thread procedure, README

**Files:**
- Modify: `claude/skills/process-ai-comments/SKILL.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the file format from Task 1 (`threadId`), the status rules from the spec.

- [ ] **Step 1: Rewrite `SKILL.md`**

```markdown
---
name: process-ai-comments
description: Process the AI comments the PhpStorm "AI Comments" plugin stores in .claude/comments.json — read each open thread, apply the requested change or answer, and write the reply back so the IDE shows it. Use for "AI comments", "AI komentáře", "phpstorm comments", "komentáře v PhpStormu", "komentáře z PhpStormu", "process / answer the comments", "zpracuj / vyřiď / projdi / odpověz na komentáře", "comments.json", or /process-ai-comments. In a project that has .claude/comments.json, a bare "comments" / "komentáře" means this skill — not GitHub PR comments and not the JetBrains MCP.
---

# Process AI comments

The PhpStorm plugin stores per-line notes in `.claude/comments.json` at the project root.
Notes on one line form a **thread**: the user writes, you answer, the user replies to your
answer, and so on. Your job: answer every thread whose newest record is `"status": "open"`
and write the result back so the plugin can show it in the editor.

## File format

```json
{
  "version": 1,
  "metadata": { "projectName": "…", "created": "…", "lastModified": "…" },
  "comments": {
    "src/Service/UserService.php": [
      {
        "id": "c_1726061700_0",
        "threadId": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "use FQN here",
        "status": "resolved",
        "created": "2026-09-11T10:05:00Z",
        "processed": true,
        "processedAt": "2026-09-11T10:25:00Z",
        "claudeResponse": "Replaced the short class name with the fully qualified one."
      },
      {
        "id": "c_1726140000_0",
        "threadId": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "no, keep the short name and add a use statement instead",
        "status": "open",
        "created": "2026-09-12T12:00:00Z",
        "processed": false,
        "processedAt": null,
        "claudeResponse": null
      }
    ]
  }
}
```

Paths are relative to the project root. `line` is 1-based. Timestamps are UTC ISO 8601
with seconds (`2026-09-11T10:25:00Z`).

A **thread** is every record with the same `threadId`, ordered by `created`. A record
without `threadId` is a thread of its own. In each record `text` is the user's message and
`claudeResponse` your answer to it. Older records of a thread are always `resolved`; the
thread's state is the state of its newest record:

- `open` — the user wrote something you have not answered yet → this is your work;
- `processed` — you answered, the user has not reacted → leave it;
- `resolved` — the user closed the thread → leave it.

## Procedure

1. Read `.claude/comments.json`. Group records by `threadId`. If the file is missing or no
   thread has an `open` newest record, say so and stop.
2. For each thread with an open newest record, in file order:
   - Read the whole thread oldest to newest — every `text` and `claudeResponse` — as the
     conversation. The newest `text` is the message to act on; earlier ones are context
     (the user may be saying your previous answer was wrong).
   - Open the file and read the commented line plus 5 lines before and after. Read more of
     the file only if the instruction needs it.
   - Decide what the message is:
     - an **instruction** ("use FQN here", "extract this to a method", "no, revert that")
       → change the code;
     - a **question** (ends with `?`, or starts with "is/does/why/should/can") → answer, do
       not change the code.
   - If your edit moved the commented code (e.g. you inserted lines above it), set `line`
     on every record of the thread to the line where that code now is.
   - Write `claudeResponse` on the newest record: 1–3 sentences in the language of the
     message saying what you changed (with the new name / shape) or the answer. No
     preamble.
   - If the thread has several `open` records (the user wrote twice before you ran), read
     their texts together as one message, answer on the newest, and set the older open
     ones to `"status": "processed"`, `"processed": true`, `"processedAt": "<now>"` with
     `"claudeResponse": null`.
3. Update the newest record: `"status": "processed"`, `"processed": true`,
   `"processedAt": "<now, UTC ISO 8601>"`, `"claudeResponse": "<text>"`.
4. Set `metadata.lastModified` to the same timestamp.
5. Write the whole file once, after all threads are handled, pretty-printed with two-space
   indentation, keys in the order shown above. The IDE watches the file and refreshes on
   every write; a single write means a single refresh and never a half-processed file.
6. Report: how many threads were answered and which source files changed.

## Rules

- Change only `status`, `processed`, `processedAt`, `claudeResponse` and
  `metadata.lastModified`. Change `line` only when your own edit moved the commented code;
  otherwise leave it. Never delete a record, never change `id`, `threadId`, `text`,
  `created` or `author`, never add records.
- Never set `"status": "resolved"` — only the user closes a thread, from the IDE.
- Leave `processed` and `resolved` records untouched.
- If a line number no longer matches the code the thread describes (the file changed),
  look for the described code nearby; if you cannot find it, answer in `claudeResponse`
  that the line could not be matched and still mark the record processed.
- When an instruction is ambiguous, pick the most conservative reading and say what you
  assumed in `claudeResponse`.
- Do not stage or commit anything.
```

- [ ] **Step 2: Update `README.md`**

Replace the **Workflow** section:

```markdown
## Workflow

1. In the editor: right-click a line number (or press `Cmd+Alt+Shift+A`, `Ctrl+Alt+Shift+A`
   on Linux/Windows) → **Add AI Comment** → type the note. A blue dot appears in the gutter.
2. In Claude Code, in the same project: `/process-ai-comments` (or "process AI comments",
   "zpracuj komentáře"). Claude edits the code or answers, and writes its reply back into
   the file.
3. The dot turns green. Click it to open the thread: your note, Claude's reply, and a
   **Reply** field. **Resolve** closes the thread (gray check); **Reply** sends a new message
   to Claude (blue dot again). A resolved thread can be opened and replied to at any time —
   the reply reopens it. Claude never resolves a thread.

Gutter icons: blue dot = waiting for Claude, green dot = Claude replied, gray check =
resolved. When a line has several threads the icon shows the most important state
(open > processed > resolved).
```

In the **Smoke checklist**, replace the two lines about *Mark as Resolved* / *OK* / *Follow-up*:

```markdown
- [ ] Open thread → *Resolve* → gray check; open it again → *Reply* → blue dot
- [ ] Edit the JSON externally (open → processed with a response) → green dot within ~1 s
- [ ] Processed thread → dialog shows the note and the reply → *Reply* → new record with the same `threadId`, previous one resolved
```

and in the **File format** section add after the first sentence: `Records of one line form a
thread by \`threadId\`; files written by an older plugin version (no \`threadId\`) load as
one thread per record.`

- [ ] **Step 3: Check the skill file parses and the sample in it is valid JSON**

Run: `sed -n '/^```json$/,/^```$/p' claude/skills/process-ai-comments/SKILL.md | sed '1d;$d' | python3 -m json.tool > /dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 4: Commit**

```bash
git add claude/skills/process-ai-comments/SKILL.md README.md
git commit -m "Process comment threads in the skill and widen its triggers"
```

---

### Task 8: Full verification and release check

**Files:** none new.

- [ ] **Step 1: Run the whole suite and the plugin verifier**

Run: `./gradlew test verifyPlugin`
Expected: BUILD SUCCESSFUL, no compatibility errors.

- [ ] **Step 2: Manual smoke in the sandbox**

Run: `./gradlew runIde` and walk the spec's smoke path in a scratch project:
comment → (edit `.claude/comments.json` by hand: newest record `processed` with a
`claudeResponse`) → green dot → click → dialog shows both messages → **Reply** → blue dot,
JSON has a second record with the same `threadId` and the first `resolved` → **Resolve** →
gray check → click gray check → **Reply** → blue dot again. Tool window shows one node per
thread with `2 messages`, counts match.

- [ ] **Step 3: Install the updated skill globally**

Run: `cp -r claude/skills/process-ai-comments ~/.claude/skills/`
This is what makes the new triggers active in other projects.
