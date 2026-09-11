---
name: process-ai-comments
description: Process open AI comments stored by the PhpStorm "AI Comments" plugin in .claude/comments.json — apply the requested code changes or answer questions, then write the responses back. Use when the user says "process AI comments", "zpracuj AI komentáře", or runs /process-ai-comments.
---

# Process AI comments

The PhpStorm plugin stores per-line notes in `.claude/comments.json` at the project root.
Your job: handle every comment with `"status": "open"` and write the result back so the
plugin can show it in the editor.

## File format

```json
{
  "version": 1,
  "metadata": { "projectName": "…", "created": "…", "lastModified": "…" },
  "comments": {
    "src/Service/UserService.php": [
      {
        "id": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "use FQN here",
        "status": "open",
        "created": "2026-09-11T10:05:00Z",
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

## Procedure

1. Read `.claude/comments.json`. If it is missing or has no comment with `"status": "open"`,
   say so and stop.
2. For each open comment, in file order:
   - Open the file and read the commented line plus 5 lines before and after. That is the
     context the comment refers to; read more of the file only if the instruction needs it.
   - If the same line has other comments (any status), read them oldest to newest — they
     are the conversation history and the open one is a follow-up to the previous response.
   - Decide what the comment is:
     - an **instruction** ("use FQN here", "extract this to a method") → change the code;
     - a **question** (ends with `?`, or starts with "is/does/why/should/can") → answer, do
       not change the code.
   - If your edit moved the commented code (e.g. you inserted lines above it), set `line`
     to the line where that code now is.
   - Write `claudeResponse`: 1–3 sentences in the language of the comment saying what you
     changed (with the new name / shape) or the answer. No preamble.
3. Update the record: `"status": "processed"`, `"processed": true`,
   `"processedAt": "<now, UTC ISO 8601>"`, `"claudeResponse": "<text>"`.
4. Set `metadata.lastModified` to the same timestamp.
5. Write the whole file once, after all comments are handled, pretty-printed with two-space
   indentation, keys in the order shown above. The IDE watches the file and refreshes on
   every write; a single write means a single refresh and never a half-processed file.
6. Report: how many comments were processed and which source files changed.

## Rules

- Change only `status`, `processed`, `processedAt`, `claudeResponse` and
  `metadata.lastModified`. Change `line` only when your own edit moved the commented code;
  otherwise leave it. Never delete a record, never change `id`, `text`, `created` or
  `author`, never add records.
- Leave `processed` and `resolved` records untouched.
- If a line number no longer matches the code the comment describes (the file changed),
  look for the described code nearby; if you cannot find it, answer in `claudeResponse`
  that the line could not be matched and still mark the comment processed.
- When an instruction is ambiguous, pick the most conservative reading and say what you
  assumed in `claudeResponse`.
- Do not stage or commit anything.
