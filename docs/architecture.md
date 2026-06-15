# Architecture — Prism: IDE Companion for Claude Code

## 1. Project Structure

```
src/main/kotlin/com/github/vgirotto/prism/
├── actions/               # Keyboard shortcuts and menu actions
│   ├── OpenClaudeAction.kt
│   ├── ShowDiffAction.kt
│   ├── SendSelectionAction.kt
│   ├── InsertFileReferenceAction.kt
│   └── AskClaudeAction.kt
│
├── listeners/             # Event listeners
│   └── FileChangeListener.kt (VFS monitoring)
│
├── model/                 # Data classes
│   ├── ClaudeSession.kt
│   ├── FileSnapshot.kt
│   ├── ConversationEntry.kt
│   └── PromptTemplate.kt
│
├── services/              # Core services
│   ├── ClaudeProcessManager.kt (Multi-session, PTY, Health Monitor)
│   ├── ClaudeValidationService.kt (Error handling)
│   ├── FileSnapshotService.kt (Incremental snapshots)
│   ├── DiffViewService.kt (Native IDE diff)
│   ├── ConversationHistoryService.kt
│   ├── ClaudeSettingsState.kt (Persistence)
│   ├── ContextProvider.kt
│   └── ClaudeTtyConnector.kt
│
├── settings/              # Settings UI
│   └── ClaudeSettingsConfigurable.kt
│
├── toolwindow/            # Main UI
│   ├── ClaudeToolWindowFactory.kt
│   ├── ClaudeToolbar.kt
│   ├── ClaudeStatusBarWidget.kt
│   ├── DiffPanel.kt
│   └── HistoryPanel.kt
│
└── i18n/                  # Internationalization
    └── ClaudeBundle.kt
```

## 2. Compatibility

| Property | Value |
|----------|-------|
| `sinceBuild` | `243` (IntelliJ 2024.3) |
| `untilBuild` | _(none — no upper limit)_ |
| Kotlin | 1.9.x (must match IDE's bundled stdlib) |
| JVM | 21 |

The plugin deliberately omits `untilBuild` to remain compatible with all future IDE versions. Before every release, run `./gradlew verifyPlugin` to check binary compatibility across the supported IDE range. The verifier automatically tests against the recommended set of builds (currently 2024.3 through 2026.1).

All action classes must override `getActionUpdateThread()` — this is mandatory since IntelliJ Platform build 241.

## 3. Diff View Architecture

The diff system captures a snapshot of the project before each Claude interaction and computes the delta after Claude finishes writing to disk.

```
  ┌──────────────────────────────────────────────────────────────┐
  │                     User sends message                       │
  │                     (Enter in terminal)                      │
  └──────────────┬───────────────────────────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────┐     ┌───────────────────────┐
  │      takeSnapshot()          │     │   /tmp/claude-snap/   │
  │                              │────▶│   ├── src/main.kt     │
  │  1. Local History label      │     │   ├── README.md       │
  │  2. First: full copy to temp │     │   └── ...             │
  │     Next: incremental update │     └───────────────────────┘
  │     (only changedPaths)      │       Disk (reused across
  │  3. Build hash index (RAM)   │       interactions)
  └──────────────────────────────┘
                 │
                 │  RAM: only hash index
                 │  (~100 bytes × N files)
                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │               Claude Code CLI modifies files                 │
  │               (external process, writes to disk)             │
  └──────────────┬───────────────────────────────────────────────┘
                 │
                 │  idle 2s detected
                 ▼
  ┌──────────────────────────────┐
  │   VFS Refresh (forced)       │
  │   BulkFileListener.after()   │──▶ changedPaths = {files that changed}
  └──────────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                    computeDiff()                              │
  │                                                              │
  │  For each file in changedPaths + snapshot index:             │
  │    original = read from /tmp/claude-snap/{path}              │
  │    current  = read from project/{path}                       │
  │    if hash differs → FileDiffEntry(MODIFIED, original, cur)  │
  │    if file missing → FileDiffEntry(DELETED, original, null)  │
  │    if not in snap  → FileDiffEntry(ADDED, null, current)     │
  └──────────────┬───────────────────────────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              Claude Changes Panel                            │
  │  ┌────────────────────────────┐  ┌───────────────────────┐  │
  │  │ ~ src/main.kt              │  │ DiffManager.showDiff() │  │
  │  │ + new_file.kt              │  │ (native side-by-side)  │  │
  │  │ - old_file.kt              │  │                       │  │
  │  │ [Revert File] [Revert All] │  │ Before Claude | After  │  │
  │  │ ◀ #3 (Last) ▶              │  │                       │  │
  │  └────────────────────────────┘  └───────────────────────┘  │
  └──────────────────────────────────────────────────────────────┘
```

### Snapshot System

The snapshot mechanism is designed to minimize memory usage while keeping diff computation fast and accurate.

**On first interaction**, `FileSnapshotService` copies the entire project tree to a temporary directory (`/tmp/claude-snap/`). A SHA-256 hash is computed for each file and stored in a lightweight in-memory index (approximately 100 bytes per file).

Snapshot exclusions are applied before files are copied. The exclusion list accepts comma-separated names and wildcard patterns such as `cmake-build-*` or `**/generated`.

**On subsequent interactions**, only files whose paths were touched since the last snapshot (tracked via `BulkFileListener`) are re-copied and re-hashed. This incremental strategy avoids redundant I/O on large projects.

**At diff time**, `computeDiff()` compares the current file content against the snapshot copy using the hash index as a fast pre-filter. Only files with a differing hash are read in full and presented in the Claude Changes Panel. The native IDE `DiffManager` renders a side-by-side view labeled "Before Claude" and "After".

Each interaction is also tagged as a Local History label, providing an independent recovery path outside of the plugin's own revert mechanism.

## 4. Performance

The table below reflects typical measurements on macOS with an SSD. Incremental snapshot time refers to a scenario where fewer than 10% of files changed since the previous interaction.

| Repo size | RAM (hash index) | Disk (temp) | First snapshot | Incremental |
|-----------|-----------------|-------------|----------------|-------------|
| 100 files | ~10 KB | ~5 MB | <100ms | <10ms |
| 1K files | ~100 KB | ~50 MB | <500ms | <50ms |
| 10K files | ~1 MB | ~500 MB | ~2-5s | <100ms |

The hash index is the only persistent in-memory structure; file contents are never held in RAM beyond the duration of a single diff computation pass.
