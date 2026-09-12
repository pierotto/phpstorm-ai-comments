# Comment threads and skill triggering — design

Follow-up to `2026-09-11-ai-comments-plugin-design.md`. Two problems from real use:

1. The Claude Code skill does not trigger reliably: "phpstorm" routes to the JetBrains MCP,
   "komentáře" routes to GitHub.
2. The user never really sees Claude's answer. The processed dialog offers *OK* / *Follow-up*,
   and a follow-up hides the previous exchange. The user wants a conversation on the line,
   like a Google Docs comment thread: their note, Claude's reply, their reaction, …, with
   resolution done by hand and a resolved thread reopening when they reply to it.

## 1. Skill triggering

Only the skill `description` is under our control. It gains the phrases the user actually
says and an explicit disambiguation:

- English: "AI comments", "phpstorm comments", "process/answer the comments", "comments.json"
- Czech: "AI komentáře", "komentáře v PhpStormu", "komentáře z PhpStormu",
  "zpracuj / vyřiď / projdi / odpověz na komentáře"
- Rule: a bare "comments"/"komentáře" in a project that has `.claude/comments.json` means
  this skill, not GitHub PR comments and not the JetBrains MCP.

Fallback if it still misroutes (not part of this change): one line in the user's global
`CLAUDE.md` mapping "komentáře" to `process-ai-comments`.

## 2. Threads

### File format

Format version stays 1. Each record gains an optional `threadId`:

```json
{
  "id": "c_1726140000_0",
  "threadId": "c_1726061700_0",
  "line": 45,
  "author": "human",
  "text": "that rename is wrong, keep the old name",
  "status": "open",
  "created": "2026-09-12T12:00:00Z",
  "processed": false,
  "processedAt": null,
  "claudeResponse": null
}
```

- The first record of a thread has `threadId == id`. A reply carries the `threadId` of the
  thread it answers.
- A record without `threadId` (files written before this change) is its own thread. No
  migration and no version bump; the plugin writes `threadId` on every record it saves.
- `text` is the human message, `claudeResponse` the answer to it. A thread is the list of
  records with the same `threadId`, ordered by `created` (ties by `id`).
- A thread has one `line`: the plugin keeps all records of a thread on the same line (a
  reply copies the line of the newest record; line sync on save updates all of them since
  they share the marker).

### Thread status

The status of a thread is the status of its newest record. Every older record is
`resolved` — replying resolves the record it answers. Transitions:

| Event | Newest record |
|---|---|
| human writes a new comment or replies | `open` (blue dot) |
| Claude answers | `processed` (green dot) |
| human clicks *Resolve* | `resolved` (gray check) |
| human replies to a resolved thread | new `open` record — the thread is open again |

Claude never sets `resolved`.

### Plugin

- `Comment` gets `threadId: String` (decoded as `id` when missing).
- `CommentsFileOps`: `threads(path)` groups records into `Thread(id, path, line, records)`
  with `status` and `newest` derived; `withReply(threadId, text)` resolves the newest
  record and appends a new open one with the same `threadId` and line. `withStatus` stays
  for *Resolve*. `withLines` works per record as today.
- `CommentStore`: `reply(threadId, text)` replaces `followUp`; `resolve(threadId)` resolves
  the newest record of the thread.
- `ViewCommentDialog` becomes a thread dialog: the messages of the thread in order, each
  labeled with author and time (human `text`, then Claude `claudeResponse` when present),
  a *Reply* text field below, and the actions **Reply**, **Resolve** (only when the thread is
  not resolved), **Close**. Opening a resolved thread is allowed; the dialog no longer
  ignores it. *OK* and *Follow-up* go away; `AddCommentDialog` is used only for new
  comments (the `followUp` flag is removed).
- `CommentWorkflow.openThread(project, thread)` replaces `openComment`.
- Gutter (`CommentMarkerManager`) and tool window (`CommentsTreeModel`) show one item per
  thread, with the thread's status and the first message as label; the tool-window counts
  count threads. *Hide Resolved* hides resolved threads.
- Max comment length applies to each reply the same way as to a new comment.

### Skill

The skill processes threads, not records:

- A thread needs work when its newest record is `open`.
- Read the whole thread (all records, oldest to newest, `text` and `claudeResponse`) as the
  conversation; the newest `text` is the message to act on.
- Answer once per thread, into the newest open record: `status: processed`,
  `processed: true`, `processedAt`, `claudeResponse`. If the human wrote several open
  records in one thread before Claude ran, treat their texts together as one message, put
  the answer on the newest, and mark the older open ones `processed` with
  `claudeResponse: null`.
- Never set `resolved`, never touch `resolved` records, never change `threadId`.
- The rest of the procedure (context lines, instruction vs question, line correction,
  single write, report) stays as it is.

## Testing

- Codec: round-trip with `threadId`; a record without it decodes with `threadId == id`.
- Ops: grouping into threads, thread status from the newest record, `withReply` resolves
  the previous record and appends an open one on the same line, reply to a resolved thread
  makes it open.
- Store: `reply` and `resolve` on a thread.
- Marker manager and tree model: one item per thread, status of the thread, counts.
- Manual smoke: comment → run skill → green dot → open, read answer → reply → blue →
  run skill → green → Resolve → gray → open, reply → blue again.
