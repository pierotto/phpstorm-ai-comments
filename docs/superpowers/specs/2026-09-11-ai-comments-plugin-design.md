# AI Comments — PhpStorm plugin design

Date: 2026-09-11
Status: approved design, ready for implementation planning

## 1. Goal

A PhpStorm plugin that lets a developer attach short notes ("AI comments") to code lines,
stores them in `.claude/comments.json` at the project root, and shows their state in the
editor gutter. Claude Code processes the open comments on request (via a companion skill)
and writes its responses back into the same file; the plugin picks the change up and lets
the developer acknowledge the response or ask a follow-up — all without leaving the editor.

The original requirements document is `docs/requirements/CLAUDE_AI_COMMENTS_PLUGIN_SPEC.md` (v1.0). This
design follows it and records where and why it deviates.

## 2. Scope (MVP)

In scope:

- Add a comment to any line of a file inside the project.
- Gutter icon per line reflecting the comment status.
- View / resolve / follow-up dialogs.
- File watcher: external edits of `comments.json` refresh the UI (debounced).
- Tool window with counts, a file → comment tree and navigation.
- Settings page.
- Line tracking: gutter markers follow text edits within an editor session and the
  stored line numbers are updated when the document is saved.
- Claude Code skill `process-ai-comments` that defines how Claude processes the file.

Out of scope (see spec §12): batch resolve/reprocess, GitHub export, response history,
comment templates, multi-file context, direct API integration.

## 3. Decisions and deviations from the requirements document

| Topic | Decision | Reason |
|---|---|---|
| Gutter rendering | `RangeHighlighter` + `GutterIconRenderer` on the editor `MarkupModel`, not `LineMarkerProvider` | Independent of PSI (works in PHP, NEON, YAML, Twig, plain text), refresh is under plugin control, click/tooltip live on the renderer, markers move with text edits for free. |
| JSON library | Gson (bundled in the platform) instead of kotlinx.serialization | No compiler plugin, no version-conflict risk between 2024.1 and 2026.2. File format is unchanged. |
| Follow-up | Original `processed` comment becomes `resolved` when the follow-up is saved | Keeps at most one active comment per line; Claude still sees the history (same line, older resolved entries). |
| Click on `open` icon | Opens a read-only view dialog with *Mark as resolved* / *Close*, not an edit dialog | Editing a stored comment that Claude may be reading at that moment is a trap; the state machine only needs `open → resolved`. |
| Files outside the project | Action is not available | A project-relative path cannot be produced. |
| Line tracking | Included (markers move in-session; line numbers written back on save) | Nearly free with the highlighter approach; removes the "comment on the wrong line" edge case. |
| Documentation | Single `README.md` | Three documents are excessive for a project of this size. |
| Sample project | Replaced by a test fixture `comments.json` | Same value, no extra artifact to maintain. |
| UI language | English | Repository content is English. |
| Write threading | Mutations run synchronously on the caller's thread, not on a pooled thread | The file is small and local; the EDT callers are the dialogs and the save listener, and a synchronous result lets them react immediately. |
| Reset file | Renames the broken file; the empty file is created lazily by the first write | A missing file already means "empty", so an eager write would only add a second write path. |

## 4. Project layout

```
ai-comments/
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew
├── src/main/kotlin/cz/petrgala/aicomments/
│   ├── model/        Comment, CommentStatus, CommentsFile, FileMetadata
│   ├── storage/      CommentStore (project service), CommentsFileCodec
│   ├── editor/       CommentMarkerManager, CommentGutterIconRenderer, LineSyncOnSave
│   ├── actions/      AddCommentAction, ReloadCommentsAction
│   ├── ui/           AddCommentDialog, ViewCommentDialog
│   ├── toolwindow/   AiCommentsToolWindowFactory, AiCommentsPanel
│   ├── settings/     AiCommentsSettings, AiCommentsConfigurable
│   └── watcher/      CommentsFileWatcher
├── src/main/resources/
│   ├── META-INF/plugin.xml
│   ├── icons/  comment-open.svg, comment-open_dark.svg, comment-processed.svg,
│   │           comment-processed_dark.svg, comment-resolved.svg, comment-resolved_dark.svg,
│   │           toolwindow.svg
│   └── messages/AiCommentsBundle.properties
├── src/test/kotlin/cz/petrgala/aicomments/…
├── src/test/resources/comments.sample.json
├── claude/skills/process-ai-comments/SKILL.md
├── docs/superpowers/specs/…
└── README.md
```

Build: Gradle with IntelliJ Platform Gradle Plugin 2.x, Kotlin, JDK 21 toolchain, JVM
target 17. `sinceBuild=241`, no `untilBuild`. Built against PhpStorm 2024.1; plugin
verifier runs against 2024.1 and 2026.2.2. Plugin id `cz.petrgala.aicomments`, name
"AI Comments". Local prerequisite: `brew install --cask temurin@21` (no JDK is installed
on the development machine).

## 5. Data model

Matches spec §3.1 exactly.

```kotlin
enum class CommentStatus { OPEN, PROCESSED, RESOLVED }   // serialized lowercase

data class Comment(
    val id: String,              // c_<epochSeconds>_<index>
    val line: Int,               // 1-based
    val author: String,          // "human" (Claude reserved for future)
    val text: String,
    val status: CommentStatus,
    val created: String,         // ISO 8601 UTC
    val processed: Boolean,      // redundant; written as status != OPEN
    val processedAt: String?,
    val claudeResponse: String?,
)

data class FileMetadata(val projectName: String, val created: String, val lastModified: String)

data class CommentsFile(
    val version: Int = 1,
    val metadata: FileMetadata,
    val comments: Map<String, List<Comment>>,   // key = project-relative path, '/' separators
)
```

`id` index = number of comments created in the same second within one store call, so a
follow-up batch never collides. `processed` is derived on write; on read the `status` field
wins.

## 6. Storage — `CommentStore`

Project-level service, the single owner of comment data.

- Holds an in-memory `CommentsFile` snapshot; publishes `CommentsChanged(snapshot)` on the
  project `MessageBus` after every reload or write.
- Location: `<project.basePath>/.claude/comments.json`. Directory and file are created on
  the first write with empty `comments` and `metadata.projectName = project.name`.
- Every mutation (`add`, `resolve`, `followUp`, `updateLines`) runs under a lock as
  **reload from disk → apply change → write**. The snapshot in memory is never used as the
  base for a write, so an external change by Claude between two plugin operations is kept.
- Write: pretty-printed JSON, atomic (write to `comments.json.tmp`, then move with
  `ATOMIC_MOVE`), `metadata.lastModified` updated, followed by
  `VfsUtil.markDirtyAndRefresh` so the VFS and the watcher see it.
- Read: Gson into the model. A record with a missing required field or an unknown
  `status` is skipped with a `Logger.warn`; the rest of the file loads, but the store is
  read-only (a write would drop the skipped record) and a notification with *Open file*
  says so. `version > 1` sets the store read-only and shows a notification.
- Malformed JSON: snapshot becomes empty, store is read-only, notification with actions
  *Reset file* (renames the broken file to `comments.json.broken-<timestamp>`; the empty
  file is created lazily by the first write) and *Open file*.
- I/O failure on write: notification with the error, snapshot unchanged.

Public API (all callable from any thread; mutations run synchronously on the caller's
thread — the file is small and local, and the EDT callers are the dialogs and the save
listener):

```kotlin
fun snapshot(): CommentsFile
fun commentsFor(relativePath: String): List<Comment>
fun add(relativePath: String, line: Int, text: String): Comment
fun resolve(id: String)
fun followUp(originalId: String, text: String): Comment   // resolves original, adds new OPEN
fun updateLines(relativePath: String, idToLine: Map<String, Int>)
fun reload()
fun reset()
```

## 7. Editor integration

### CommentMarkerManager

Project service; subscribes to `EditorFactoryListener` and `CommentsChanged`.

- For every editor whose file is inside the project: remove the highlighters recorded in
  `editor.getUserData(MARKERS_KEY)`, group the file's comments by line, and add one
  `RangeHighlighter` per line (line-level, `HighlighterLayer.LAST`) with a
  `CommentGutterIconRenderer`. Lines beyond the document end get no marker.
- Line icon = highest status among the line's comments: `open > processed > resolved`.
- Runs on the EDT; data is read from the store snapshot.
- Disabled entirely when the *Show gutter icons* setting is off (existing markers are
  removed).

### CommentGutterIconRenderer

- Icons: 12×12 SVG, light + dark variants: blue circle (`open`), green circle
  (`processed`), gray check (`resolved`).
- Tooltip: `AI comment (open): <text truncated to 80 chars>`,
  `AI comment (processed): response available`, `AI comment (resolved, <date>)`.
- Click: `open` → `ViewCommentDialog` (read-only, *Mark as resolved* / *Close*);
  `processed` → `ViewCommentDialog` (*OK* / *Follow-up*); `resolved` → no action.
- Alignment: left. `equals`/`hashCode` based on line + status so the gutter repaints only
  when something changed.

### LineSyncOnSave

`FileDocumentManagerListener.beforeDocumentSaving`: for the document's editors, read the
current line of each marker (marker `startOffset` → line) and call
`store.updateLines(path, idToLine)` for the ids whose line changed. Markers that became
invalid (their line was deleted) are left untouched in the JSON.

### AddCommentAction

Registered in `EditorGutterPopupMenu`, `EditorPopupMenu`, and with shortcut
`ctrl alt shift C` (macOS: `meta alt shift C`). Line = gutter click position when invoked
from the gutter (via `EditorGutterComponentEx.getLineAtPoint` from the mouse event in the
data context), otherwise caret line. Disabled when the file is not under
`project.basePath`. Opens `AddCommentDialog`.

## 8. Dialogs

`DialogWrapper` + Kotlin UI DSL v2.

**AddCommentDialog** — title `Add AI comment – line N`, multi-line text area (6 rows),
character counter, tip text (Claude sees this line, 5 lines before and after, and the file
path). Validation: 1–`maxCommentLength` characters; a warning is shown from 80 % of the
limit; over the limit blocks OK. OK calls `store.add(...)` (or `store.followUp(...)` when
opened from the view dialog).

**ViewCommentDialog** — original text, Claude's response (read-only, wrapped, scrollable;
hidden for `open` comments). Buttons depend on status:

- `open`: *Mark as resolved* → `store.resolve(id)`; *Close*.
- `processed`: *OK* → `store.resolve(id)`; *Follow-up* → closes, opens
  `AddCommentDialog` for the same line in follow-up mode; confirming calls
  `store.followUp(originalId, text)`; cancelling leaves the original `processed`.

## 9. File watcher — `CommentsFileWatcher`

`AsyncFileListener` registered on `VirtualFileManager`, project-scoped, disposed with the
project. Filters events (create, content change, delete, move) whose path equals
`<basePath>/.claude/comments.json`. Matching events schedule `store.reload()` on an
`Alarm` (pooled thread) with the configured debounce (default 500 ms); repeated events
reset the timer. The plugin's own writes go through the same path — reload is idempotent,
so no echo detection is needed. A deleted file yields an empty snapshot and removes all
markers. The watcher is inactive when *Auto-refresh on file change* is off; the
*Reload* action in the tool window remains available.

## 10. Tool window

"AI Comments", right side, plugin icon. Content is stateless and rebuilt from
`CommentsChanged`.

- Header: `Open N · Processed N · Resolved N`, a *Reload* button and a *Hide resolved*
  toggle (persisted in settings).
- Tree: file node (relative path) → comment nodes ordered by line, labelled
  `L45 · open · <text truncated to 60 chars>` with the status icon; comments whose line
  exceeds the file's current line count get the suffix `(line out of range)`.
- Double-click / Enter navigates via `OpenFileDescriptor(project, file, line-1, 0)`.

## 11. Settings

`Preferences → Tools → AI Comments`; `PersistentStateComponent` stored per project in
`.idea/aiComments.xml`.

| Setting | Default | Effect |
|---|---|---|
| Show gutter icons | on | off removes all markers |
| Auto-refresh on file change | on | off deactivates the watcher |
| Debounce (ms) | 500 | watcher delay; 0–5000 |
| Max comment length | 500 | validation limit; 1–5000 |
| Show tool window | on | off hides the tool window |
| Hide resolved in tool window | off | tree filter (also toggled from the tool window) |

Changes apply immediately (the configurable notifies the marker manager and watcher).

## 12. Claude Code skill — `process-ai-comments`

Source: `claude/skills/process-ai-comments/SKILL.md`; installed by copying the directory to
`~/.claude/skills/` (one `cp -r` command in the README). Triggers: `/process-ai-comments`,
"process AI comments", "zpracuj AI komentáře".

Procedure the skill prescribes:

1. Read `.claude/comments.json`; stop with a message if it is missing or has no `open`
   comments.
2. For every `open` comment: open the file, read the line ±5 lines of context; if the
   line has other comments (including `resolved` ones) treat them as the conversation
   history for a follow-up.
3. A comment is an instruction unless it is phrased as a question: instructions change the
   code, questions get an answer. In both cases `claudeResponse` states briefly what was
   done or answered, in the language of the comment.
4. Set `status: "processed"`, `processed: true`, `processedAt` (UTC ISO 8601) and
   `metadata.lastModified`.
5. Only these fields may change; `line` changes only when Claude's own edit moved the
   commented code, otherwise it stays. Never delete records or alter `id`, `text`,
   `created`, `author`. Keep the pretty-printed format. Write the whole file once after all
   comments are handled so the IDE refreshes once and never sees a half-processed file.
6. Finish with a summary: comments processed, files modified.

## 13. Error handling summary

| Situation | Behaviour |
|---|---|
| File missing | Treated as empty; created on first write |
| Malformed JSON | Empty read-only snapshot, notification with *Reset file* / *Open file* |
| Invalid single record | Skipped with a log warning, rest loads read-only, notification with *Open file* |
| `version > 1` | Read-only, notification "unsupported version" |
| Write I/O error | Notification, snapshot unchanged |
| Line beyond document end | No marker; tool window shows `(line out of range)` |
| File outside project | Action disabled |

Notifications use a `NotificationGroup` "AI Comments" (balloon).

## 14. Testing

Unit tests (JUnit 5, no platform):

- `CommentsFileCodec`: round-trip against `comments.sample.json` (spec §3.1), invalid
  record skipped, unknown version detected, id generation.
- `CommentStore` on a temp directory: add creates directory and file; resolve; followUp
  resolves the original and adds a new open comment in a single write; an external edit
  made between load and write is preserved; atomic write leaves no `.tmp` on success;
  malformed file makes the store read-only.

Platform tests (`BasePlatformTestCase`):

- `CommentMarkerManager`: after add the editor has one marker on the right line with the
  open icon; after resolve the icon is the resolved one; after external delete of the file
  there are no markers; two comments on one line yield one marker with the highest status.
- `LineSyncOnSave`: inserting lines above a comment and saving updates the stored line.

Manual smoke checklist (spec §9 UI tests) lives in the README.

## 15. Deliverables

- `./gradlew buildPlugin` → `build/distributions/ai-comments-<version>.zip`, installable
  via *Install plugin from disk*.
- `./gradlew verifyPlugin` against 2024.1 and 2026.2.2; `./gradlew runIde` for development.
- `README.md`: installation, workflow, skill installation, JSON format, settings, smoke
  checklist, development commands.
- `claude/skills/process-ai-comments/SKILL.md`.
