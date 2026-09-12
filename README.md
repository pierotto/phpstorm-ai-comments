# AI Comments — PhpStorm plugin

Leave a note on a line of code in PhpStorm, run one command in Claude Code, and read the
answer — or the change — right where you asked. Notes are threads: you write, Claude
replies, you reply back, and you close the thread when you are done.

## How it works

1. Right-click a line number (or press `Cmd+Alt+Shift+A`, `Ctrl+Alt+Shift+A` on
   Linux/Windows) → **Add AI Comment** → type the note. A blue dot appears in the gutter.
2. In Claude Code, in the same project, say "process AI comments" (or "zpracuj komentáře",
   or `/process-ai-comments`). Claude changes the code or answers, and the dot turns green.
3. Click the dot to open the thread. **Reply** sends another message to Claude (the dot is
   blue again); **Resolve** closes the thread (gray check). A resolved thread can be reopened
   any time by replying to it. Claude never resolves a thread for you.

The **AI Comments** tool window lists every thread in the project; double-click jumps to the
line. Comments are stored in `.claude/comments.json` at the project root — add it to
`.gitignore` if they should stay local.

## Installation

Requires PhpStorm 2024.1 or newer and [Claude Code](https://claude.com/claude-code).

**Plugin:** download `ai-comments-<version>.zip` from the
[latest release](https://github.com/pierotto/phpstorm-ai-comments/releases/latest), then
*Settings → Plugins → ⚙ → Install Plugin from Disk…* → pick the ZIP → restart.

**Claude Code skill** (once, it is global):

```bash
mkdir -p ~/.claude/skills/process-ai-comments
curl -fsSL https://raw.githubusercontent.com/pierotto/phpstorm-ai-comments/main/claude/skills/process-ai-comments/SKILL.md \
  -o ~/.claude/skills/process-ai-comments/SKILL.md
```

The file must sit in its own directory as above — a bare `~/.claude/skills/SKILL.md` is not
picked up.

## Settings

*Settings → Tools → AI Comments*, stored per project in `.idea/aiComments.xml`:

| Setting | Default |
|---|---|
| Show gutter icons | on |
| Refresh automatically when `.claude/comments.json` changes | on |
| Refresh debounce (ms) | 500 |
| Maximum comment length | 500 |
| Show the AI Comments tool window | on |
| Hide resolved comments in the tool window | off |

## Troubleshooting

- **The dot is on the wrong line** — the plugin follows the code as you edit and when Claude
  edits the file; if it still drifts, fix `line` in `.claude/comments.json` by hand.
- **"comments.json is malformed"** — the notification offers **Reset file** (the broken file
  is kept as `comments.json.broken-<timestamp>`) and **Open file**. Until then the plugin is
  read-only.
- **Claude reaches for GitHub or the JetBrains MCP instead** — make sure the skill is
  installed as described above; its description tells Claude that "comments" in a project
  with `.claude/comments.json` means these comments.

The file format is documented in [`SKILL.md`](claude/skills/process-ai-comments/SKILL.md).

## Development

See [docs/development.md](docs/development.md).

## License

[MIT](LICENSE)
