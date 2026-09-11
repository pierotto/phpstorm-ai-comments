# PhpStorm Plugin: Claude AI Comments
## Technical Specification & Implementation Guide

**Version:** 1.0  
**Date:** 2026-09-11  
**Scope:** MVP (Minimal Viable Product)  
**Estimated Implementation Time:** 10–12 hours

---

## 1. Overview

### Vision
A PhpStorm plugin that enables developers to annotate code with AI-focused comments, which are processed asynchronously by Claude, with results synchronized back to the editor in real-time.

### Problem Statement
Developers want to:
- Write quick notes in code while coding (e.g., "use FQN here")
- Have Claude process these notes without context-switching
- See Claude's responses inline without manually exporting/importing
- Track which comments are processed, acknowledged, or rejected

### Solution
A persistent storage layer (`.claude/comments.json`) with a PhpStorm UI that:
- Allows adding comments to any code line
- Displays visual indicators (gutter icons) showing comment status
- Synchronizes with Claude's processing
- Lets developers accept or reject suggestions inline

---

## 2. Functional Requirements

### 2.1 Core Features (MVP)

#### 2.1.1 Add Comment to Line
- **Trigger:** Right-click on line number → "Add AI Comment" OR click gutter icon
- **Action:** Modal dialog opens
- **Input:** Free-form text (1–500 chars recommended)
- **Storage:** Saved to `.claude/comments.json` immediately
- **UI Feedback:** Gutter icon changes to indicate "open" comment

#### 2.1.2 View Processed Comment
- **Trigger:** Click gutter icon with processed status (🟢)
- **Display:** Modal showing:
  - Original comment text
  - Claude's response
  - Two buttons: "✅ OK" and "❌ Add Follow-up"
- **No Edit:** Cannot modify processed comment, only acknowledge it

#### 2.1.3 File Watching & Auto-Refresh
- **Trigger:** `.claude/comments.json` file changes (externally)
- **Action:** PhpStorm detects change → refreshes gutter icons
- **Status Sync:** Gutter updates to reflect new statuses (open → processed → resolved)
- **Performance:** Non-blocking; debounced (500ms)

#### 2.1.4 Comment Status Lifecycle

```
STATE MACHINE:

open
  ↓ (user: Mark as resolved)
resolved
  
open
  ↓ (Claude: processes & updates JSON)
processed
  ↓ (user: click OK button)
resolved

open
  ↓ (Claude: processes)
processed
  ↓ (user: click "Add Follow-up")
open (new comment added to same line)
```

#### 2.1.5 Sidebar Panel (Optional but Recommended)
- **Display:** Tool window showing:
  - Open comments count
  - Processed comments count
  - Resolved comments count
  - List of files with comments
  - Quick navigation to commented lines
- **Update:** Real-time as JSON changes

### 2.2 Non-Functional Requirements

- **Storage Location:** `.claude/comments.json` at project root
- **Persistence:** JSON format, human-readable
- **Concurrency:** Handle external JSON edits (Claude writing back)
- **Performance:** All UI operations < 200ms
- **Compatibility:** PhpStorm 2024.1+
- **Language:** Plugin code in Kotlin

---

## 3. Data Structure

### 3.1 .claude/comments.json Format

```json
{
  "version": 1,
  "metadata": {
    "projectName": "my-project",
    "created": "2026-09-11T10:00:00Z",
    "lastModified": "2026-09-11T15:30:00Z"
  },
  "comments": {
    "src/Service/UserService.php": [
      {
        "id": "c_1726061700_0",
        "line": 45,
        "author": "human",
        "text": "zde je potřeba FQN",
        "status": "open",
        "created": "2026-09-11T10:05:00Z",
        "processed": false,
        "processedAt": null,
        "claudeResponse": null
      },
      {
        "id": "c_1726061700_1",
        "line": 45,
        "author": "human",
        "text": "to OK není, FQN nemusí být tady",
        "status": "open",
        "created": "2026-09-11T10:15:00Z",
        "processed": false,
        "processedAt": null,
        "claudeResponse": null
      },
      {
        "id": "c_1726061700_2",
        "line": 78,
        "author": "human",
        "text": "refactor this method",
        "status": "processed",
        "created": "2026-09-11T10:20:00Z",
        "processed": true,
        "processedAt": "2026-09-11T10:25:00Z",
        "claudeResponse": "This method can be simplified by extracting the validation logic into a separate private method."
      }
    ],
    "config/services.neon": [
      {
        "id": "c_1726061701_0",
        "line": 12,
        "author": "human",
        "text": "is this config correct for production?",
        "status": "resolved",
        "created": "2026-09-11T10:30:00Z",
        "processed": true,
        "processedAt": "2026-09-11T10:35:00Z",
        "claudeResponse": "Yes, this configuration is correct for production. The debug mode is set to false and all external services are configured with production endpoints."
      }
    ]
  }
}
```

### 3.2 JSON Schema Notes

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | string | ✅ | Format: `c_<timestamp>_<index>` (unique) |
| `line` | number | ✅ | 1-indexed line number |
| `author` | string | ✅ | "human" or "claude" (reserved for future) |
| `text` | string | ✅ | Comment content; sanitize input |
| `status` | enum | ✅ | "open", "processed", "resolved" |
| `created` | ISO8601 | ✅ | UTC timestamp |
| `processed` | boolean | ✅ | Redundant with status but useful for quick checks |
| `processedAt` | ISO8601 | ❌ | Null if not processed |
| `claudeResponse` | string | ❌ | Null until Claude processes |

---

## 4. Architecture

### 4.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    PhpStorm Editor Window                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Gutter                                                       │
│  ┌────────┐  ┌─────────────────────────────────────────┐    │
│  │ 45 🔵  │  │ public function getUserById($id)        │    │
│  │ 46     │  │ {                                        │    │
│  │ 47     │  │   // here is needed FQN                 │    │
│  │ 48 🟢  │  │   return $this->repo->find($id);        │    │
│  │ 49     │  │ }                                        │    │
│  └────────┘  └─────────────────────────────────────────┘    │
│                                                               │
│  Sidebar Tool Window (Optional)                              │
│  ┌──────────────────────────────────┐                       │
│  │ AI Comments (3)                  │                       │
│  │ • Open: 1                         │                       │
│  │ • Processed: 1                    │                       │
│  │ • Resolved: 1                     │                       │
│  │                                  │                       │
│  │ UserService.php                  │                       │
│  │ └─ Line 45 (open)               │                       │
│  │ └─ Line 78 (processed)          │                       │
│  │                                  │                       │
│  │ services.neon                    │                       │
│  │ └─ Line 12 (resolved)           │                       │
│  └──────────────────────────────────┘                       │
│                                                               │
└─────────────────────────────────────────────────────────────┘
         ↕ (watches & updates)
┌─────────────────────────────────────────────────────────────┐
│        CommentStorageService (.claude/comments.json)        │
│                                                              │
│  • Read/Write JSON                                          │
│  • File watcher with debounce                               │
│  • Validation & migration                                   │
└─────────────────────────────────────────────────────────────┘
         ↕ (external process updates)
┌─────────────────────────────────────────────────────────────┐
│                      Claude (Chat)                          │
│                                                              │
│  User: "Zpracuj všechny AI komentáře"                      │
│  Claude: Reads JSON → Processes → Updates JSON              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Core Classes/Components

#### CommentStorageService
- **Responsibility:** File I/O, JSON parsing, validation
- **Key Methods:**
  - `loadComments(project: Project): CommentData`
  - `saveComments(data: CommentData): Unit`
  - `getCommentsForFile(filePath: String): List<Comment>`
  - `addComment(filePath: String, line: Int, text: String): Comment`
  - `updateCommentStatus(commentId: String, status: CommentStatus): Unit`
  - `watchForChanges(listener: (CommentData) -> Unit): Disposable`

#### CommentGutterIconRenderer
- **Responsibility:** Visual indicator in editor gutter
- **Key Methods:**
  - `getIcon(): Icon` (depends on status: 🔵 open, 🟢 processed, ✔️ resolved)
  - `getTooltipText(): String` (preview of comment)
  - `getClickListener(): () -> Unit` (opens modal)

#### CommentModalDialog
- **Responsibility:** UI for viewing/adding comments
- **Variants:**
  - `AddCommentDialog` (input mode)
  - `ViewCommentDialog` (read mode with response)

#### AICommentsToolWindowFactory
- **Responsibility:** Sidebar panel
- **Content:** Summary and navigation

#### FileWatcher
- **Responsibility:** Detect external `.claude/comments.json` changes
- **Behavior:**
  - Debounced (500ms)
  - Triggers UI refresh via event system

---

## 5. Detailed Specifications

### 5.1 Gutter Icon Behavior

#### Icon Display Rules

| Status | Icon | Color | Clickable | On Click |
|--------|------|-------|-----------|----------|
| `open` | 🔵 | Blue | ✅ | Open "Add/Edit" modal |
| `processed` | 🟢 | Green | ✅ | Open "View Response" modal |
| `resolved` | ✔️ | Gray | ✅ | Quick tooltip (no modal) |

#### Tooltip Content
```
Open:      "AI Comment (open): zde je potřeba FQN"
Processed: "AI Comment (processed): Odpověď dostupná"
Resolved:  "AI Comment (resolved, 2026-09-11)"
```

### 5.2 Modal Dialogs

#### Add Comment Modal

```
┌─────────────────────────────────────────┐
│ Add AI Comment to Line 45                │
├─────────────────────────────────────────┤
│                                          │
│ [Text area - multiline]                 │
│ "zde je potřeba FQN"                    │
│                                          │
│ ┌──────────────┬──────────────┐         │
│ │ Cancel       │ Add Comment  │         │
│ └──────────────┴──────────────┘         │
│                                          │
│ Tip: Be concise. Claude will see:       │
│ • This line of code                     │
│ • 5 lines before & after                │
│ • File path & name                      │
│                                          │
└─────────────────────────────────────────┘
```

**Logic:**
- Text required (1–500 chars)
- Click "Add Comment" → saves to JSON → gutter updates
- Click "Cancel" → closes modal, no changes

#### View Processed Comment Modal

```
┌─────────────────────────────────────────┐
│ AI Comment - Line 78                     │
├─────────────────────────────────────────┤
│ Your comment:                            │
│ "refactor this method"                  │
│                                          │
│ Claude's response:                       │
│ "This method can be simplified by       │
│  extracting the validation logic into   │
│  a separate private method. Consider:   │
│  1. Extract _validateInput()            │
│  2. Reduce nesting                      │
│  3. Add unit tests"                     │
│                                          │
│ ┌──────────────┬──────────────┐         │
│ │ ✅ OK        │ ❌ Follow-up  │         │
│ └──────────────┴──────────────┘         │
│                                          │
└─────────────────────────────────────────┘
```

**Logic:**
- Click "✅ OK" → status becomes `resolved` → icon becomes ✔️ gray
- Click "❌ Follow-up" → new comment added to same line with status `open`

### 5.3 File Watcher Implementation

```kotlin
VirtualFileManager.getInstance().addAsyncFileListener(object : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val commentFile = events.find { it.file?.name == "comments.json" }
        if (commentFile != null) {
            debounce(500) {
                // Reload data
                val newData = storage.loadComments(project)
                // Trigger UI update
                EditorFactory.getInstance().allEditors.forEach { editor ->
                    refreshGutterForEditor(editor, newData)
                }
            }
        }
        return null
    }
})
```

**Requirements:**
- Non-blocking (async)
- Debounced to avoid rapid updates
- Only responds to `.claude/comments.json` changes
- Handles file creation, modification, deletion

---

## 6. Workflow Scenarios

### Scenario 1: Developer Adds Comment

```
[STEP 1] Developer opens UserService.php, line 45
         Right-click on line number
         
[STEP 2] Select "Add AI Comment"
         
[STEP 3] Modal opens, user types:
         "zde je potřeba FQN"
         
[STEP 4] Click "Add Comment"
         
[RESULT] • Gutter shows 🔵 at line 45
         • JSON updated with new comment
         • Status = "open"
```

### Scenario 2: Claude Processes Comment

```
[STEP 1] Developer opens chat with Claude
         Pastes: "Zpracuj všechny AI komentáře"
         
[STEP 2] Claude reads .claude/comments.json
         Processes all "open" comments
         
[STEP 3] Claude updates JSON:
         • "processed" = true
         • "processedAt" = ISO8601
         • "claudeResponse" = "..."
         • "status" = "processed"
         
[STEP 4] PhpStorm detects file change (file watcher)
         Refreshes gutter
         
[RESULT] • Gutter shows 🟢 at line 45
         • Developer can now click icon to see response
```

### Scenario 3: Developer Reviews Response

```
[STEP 1] Developer sees 🟢 icon at line 45
         Clicks it
         
[STEP 2] Modal opens showing:
         • Original comment
         • Claude's response
         
[STEP 3a] If agrees: Click "✅ OK"
          → Status = "resolved"
          → Icon becomes ✔️ gray
          
[STEP 3b] If disagrees: Click "❌ Follow-up"
          → New comment added to same line
          → Status = "open"
          → Developer can type follow-up
          → Next time Claude processes, handles both comments
```

---

## 7. Edge Cases & Error Handling

### Edge Case 1: JSON File Doesn't Exist
- **Handling:** Create with default structure on first comment
- **Path:** `{project.basePath}/.claude/comments.json`

### Edge Case 2: Malformed JSON
- **Handling:** Log error, show notification to user, offer to reset
- **Recovery:** Keep backup of last valid version

### Edge Case 3: Line Deleted/Moved
- **Handling:** Comment stays with original line number, but show warning
- **Future:** Could implement line-based tracking (not MVP)

### Edge Case 4: External JSON Edit Conflicts
- **Handling:** Last write wins (Claude's update overwrites plugin's change)
- **Mitigation:** Plugin reads before write, merges if needed

### Edge Case 5: Very Long Comment Text
- **Handling:** Truncate display in tooltip (show "..."), full text in modal
- **Limit:** 500 chars soft limit, warn at 400+

### Edge Case 6: File Outside Project Root
- **Handling:** Comments can be added, JSON path is project-relative
- **Note:** Paths stored as relative to project root

---

## 8. Configuration & Settings

### Plugin Settings (Optional MVP)

```kotlin
class AICommentsSettingsConfigurable : Configurable {
    var enableGutterIcons: Boolean = true
    var debounceMs: Int = 500
    var maxCommentLength: Int = 500
    var showSidebarPanel: Boolean = true
    var autoRefreshOnChange: Boolean = true
}
```

**Storage:** IDE's persistent settings (`.idea/` folder)

---

## 9. Testing Strategy

### Unit Tests

#### CommentStorageService Tests
```kotlin
// Initialization
- testLoadFromEmptyFile()
- testLoadFromExistingFile()
- testCreateNewCommentFile()

// CRUD
- testAddComment()
- testUpdateCommentStatus()
- testGetCommentsByFile()

// Validation
- testInvalidJsonHandling()
- testCommentIdUniqueness()

// Edge cases
- testConcurrentWrites()
- testLargeCommentText()
```

#### Comment Lifecycle Tests
```kotlin
- testCommentStatusTransition_openToProcessedToResolved()
- testFollowUpComment_createsNewComment()
- testResolvedCommentRemains_immutable()
```

### Integration Tests

#### File Watcher Tests
```kotlin
- testFileWatcherDetectsChange()
- testDebounceWorks()
- testUIRefreshesAfterChange()
```

#### UI Tests (Manual)
```
✅ Add comment via UI
✅ Edit comment text (before saving)
✅ View modal with response
✅ Mark as resolved
✅ Add follow-up comment
✅ Gutter icon shows correct status
✅ Sidebar shows correct counts
✅ Tooltip shows correct preview
```

---

## 10. Implementation Checklist

### Phase 1: Core Storage & Data (2–3 hours)

- [ ] Create `Comment` data class
- [ ] Create `CommentData` container class
- [ ] Implement `CommentStorageService`
  - [ ] `loadComments()`
  - [ ] `saveComments()`
  - [ ] `getCommentsForFile()`
  - [ ] `addComment()`
  - [ ] `updateCommentStatus()`
  - [ ] JSON serialization/deserialization
- [ ] Add JSON schema validation
- [ ] Write unit tests for storage

### Phase 2: UI - Gutter Icons (2–3 hours)

- [ ] Create icon assets (🔵 🟢 ✔️ or use built-in)
- [ ] Implement `CommentGutterIconRenderer`
- [ ] Register gutter provider with PhpStorm
- [ ] Implement click handler
- [ ] Add tooltip text
- [ ] Test gutter rendering in editor

### Phase 3: UI - Modals (2–3 hours)

- [ ] Create `AddCommentDialog`
  - [ ] Text input field
  - [ ] Input validation
  - [ ] Save button logic
- [ ] Create `ViewCommentDialog`
  - [ ] Display comment + response
  - [ ] "OK" button → update status
  - [ ] "Follow-up" button → add new comment
- [ ] Wire dialogs to gutter click handlers

### Phase 4: File Watcher & Auto-Refresh (1–2 hours)

- [ ] Implement `VirtualFileListener`
- [ ] Set up debounce logic
- [ ] Wire listener to UI refresh
- [ ] Handle file creation/deletion
- [ ] Test with external JSON edits

### Phase 5: Sidebar Tool Window (1–2 hours)

- [ ] Create `AICommentsToolWindowFactory`
- [ ] Implement summary display (counts)
- [ ] Implement file list with navigation
- [ ] Wire to same data source
- [ ] Auto-update on file changes

### Phase 6: Polish & Testing (1–2 hours)

- [ ] Settings/configuration
- [ ] Error handling & notifications
- [ ] Edge case handling
- [ ] Keyboard shortcuts (optional)
- [ ] Documentation

---

## 11. Deliverables

### Plugin Package
- `build/distributions/claude-ai-comments-plugin.zip`
- Installable via "Install from Disk" in PhpStorm

### Documentation
- `README.md` - User guide
- `PLUGIN_SETUP.md` - Installation & configuration
- `DEVELOPER.md` - Architecture & extension guide

### Assets
- Icon files (gutter icons)
- Sample `.claude/comments.json` file
- Sample project with pre-populated comments

---

## 12. Future Enhancements (Not MVP)

1. **Code Context**: Automatically send 5 lines before/after with comment
2. **Line Tracking**: Comments follow lines even if code is modified
3. **Batch Operations**: "Resolve All" / "Reprocess All" buttons
4. **GitHub Integration**: Export comments to GitHub review comments
5. **History**: View Claude's previous responses to same comment
6. **Custom Templates**: Quick comment snippets (e.g., "FQN needed", "Refactor")
7. **Multi-file Context**: Comments that span across related files
8. **Claude API Direct**: Direct integration without manual copy-paste

---

## 13. References & Resources

### IntelliJ Plugin Development
- [IntelliJ Platform SDK Documentation](https://plugins.jetbrains.com/docs/)
- [Gutter Icons](https://plugins.jetbrains.com/docs/intellij/editor-basics.html#gutter-marks)
- [Virtual File System](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html)
- [File Watchers](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html#detecting-changes)

### Sample Code
- [Example: SimpleAnnotator](https://github.com/JetBrains/intellij-plugins/tree/main/AngularJS)
- [Example: ToolWindow](https://github.com/JetBrains/intellij-community/tree/master/plugins/space)

### JSON Handling
- kotlinx.serialization or gson for JSON (recommend kotlinx.serialization 1.6+)

---

## 14. Contact & Questions

**Questions during implementation?**
- Review this spec
- Check IntelliJ documentation
- Prototype with minimal code first
- Iterate with stakeholder (Petr)

**Status Tracking:**
- Use implementation checklist above
- Report blockers early
- Demo after each phase completion

---

**Document Version:** 1.0  
**Last Updated:** 2026-09-11  
**Status:** Ready for Implementation
