# AI Comments — PhpStorm plugin

Attach short notes to code lines from inside PhpStorm, let Claude Code process them, and
review the answers in the editor. Comments live in `.claude/comments.json` at the project
root; the plugin shows their state in the gutter and a tool window.

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

## Installation

### Plugin

1. Download `ai-comments-<version>.zip` from the
   [latest release](https://github.com/pierotto/phpstorm-ai-comments/releases/latest)
   (or build it yourself: `./gradlew buildPlugin` → `build/distributions/`).
2. PhpStorm → *Settings → Plugins → ⚙ → Install Plugin from Disk…* → pick the ZIP → restart.

Requires PhpStorm 2024.1 or newer. To update, install the newer ZIP the same way.

### Claude Code skill

```bash
mkdir -p ~/.claude/skills/process-ai-comments
curl -fsSL https://raw.githubusercontent.com/pierotto/phpstorm-ai-comments/main/claude/skills/process-ai-comments/SKILL.md \
  -o ~/.claude/skills/process-ai-comments/SKILL.md
```

(or from a checkout: `cp -r claude/skills/process-ai-comments ~/.claude/skills/`). The skill
is global, so it works in every project where the plugin is used. The file must live in its
own directory — a bare `~/.claude/skills/SKILL.md` is not picked up.

## Settings

*Settings → Tools → AI Comments* (stored per project in `.idea/aiComments.xml`):

| Setting | Default |
|---|---|
| Show gutter icons | on |
| Refresh automatically when `.claude/comments.json` changes | on |
| Refresh debounce (ms) | 500 |
| Maximum comment length | 500 |
| Show the AI Comments tool window | on |
| Hide resolved comments in the tool window | off |

## File format

See `claude/skills/process-ai-comments/SKILL.md`. Records of one line form a
thread by `threadId`; files written by an older plugin version (no `threadId`) load as
one thread per record. Add `.claude/comments.json` to
`.gitignore` if the comments should stay local.

A malformed file shows a notification with **Reset file** (the broken file is kept as
`comments.json.broken-<timestamp>`) and **Open file**. Until then the plugin is read-only.

## Development

```bash
./gradlew test           # unit + platform tests
./gradlew runIde         # sandbox PhpStorm with the plugin
./gradlew verifyPlugin   # compatibility against 2024.1 and 2026.2
./gradlew buildPlugin    # ZIP in build/distributions
```

Requires JDK 21 (`brew install --cask temurin@21`).

### Release

Bump `version` in `build.gradle.kts`, commit, then tag and push:

```bash
git tag v0.2.0 && git push origin main v0.2.0
```

The `release` workflow builds the plugin and attaches the ZIP to a GitHub release for the tag.

### Smoke checklist before a release

- [ ] Add a comment from the gutter menu, the editor menu and the shortcut
- [ ] Validation: empty text blocked, warning from 400 characters, blocked above 500
- [ ] Open thread → *Resolve* → gray check; open it again → *Reply* → blue dot
- [ ] Edit the JSON externally (open → processed with a response) → green dot within ~1 s
- [ ] Processed thread → dialog shows the note and the reply → *Reply* → new record with the same `threadId`, previous one resolved
- [ ] Insert lines above a comment, save → line number updated in the JSON
- [ ] Tool window counts, navigation on double-click and Enter, *Hide Resolved*, *Reload*
- [ ] Settings: gutter icons off removes dots, tool window off hides the stripe
- [ ] Malformed JSON → notification, *Reset file* recovers

## License

[MIT](LICENSE)
