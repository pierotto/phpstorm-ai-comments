# Development

Requires JDK 21.

```bash
./gradlew test           # unit + platform tests
./gradlew runIde         # sandbox PhpStorm with the plugin
./gradlew verifyPlugin   # compatibility against 2024.1 and 2026.2
./gradlew buildPlugin    # ZIP in build/distributions
```

Design notes and implementation plans are in `docs/superpowers/`.

## Release

Bump `version` in `build.gradle.kts`, commit, tag, push:

```bash
git tag v0.2.0 && git push origin main v0.2.0
```

The `release` workflow builds the plugin and attaches the ZIP to a GitHub release for the tag.

## Smoke checklist before a release

- [ ] Add a comment from the gutter menu, the editor menu and the shortcut
- [ ] Validation: empty text blocked, warning from 400 characters, blocked above 500
- [ ] Open thread → *Resolve* → gray check; open it again → *Reply* → blue dot
- [ ] Edit the JSON externally (open → processed with a response) → green dot within ~1 s
- [ ] Processed thread → dialog shows the note and the reply → *Reply* → new record with the same `threadId`, previous one resolved
- [ ] Insert lines above a comment, save → line number updated in the JSON
- [ ] Let Claude rewrite the commented line from outside the IDE → dot stays on that line, JSON updated
- [ ] Tool window counts, navigation on double-click and Enter, *Hide Resolved*, *Reload*
- [ ] Settings: gutter icons off removes dots, tool window off hides the stripe
- [ ] Malformed JSON → notification, *Reset file* recovers
