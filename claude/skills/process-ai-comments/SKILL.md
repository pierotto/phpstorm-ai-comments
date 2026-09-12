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

A `resolved` record with `"claudeResponse": null` is a message the user followed up before
you ran; read it as part of the conversation, together with the newer message.

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
- Leave `processed` and `resolved` records untouched, except `line` when the whole thread moved (step 2).
- If a line number no longer matches the code the thread describes (the file changed),
  look for the described code nearby; if you cannot find it, answer in `claudeResponse`
  that the line could not be matched and still mark the record processed.
- When an instruction is ambiguous, pick the most conservative reading and say what you
  assumed in `claudeResponse`.
- Do not stage or commit anything.
