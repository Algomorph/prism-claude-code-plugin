# Architecture — Prism: IDE Companion for Claude Code and Codex

Prism is agent-agnostic: a single set of tool-window, process, and diff classes
drives every supported CLI, with the per-CLI differences (history location,
binary validation, startup banner) isolated behind small interfaces. The
`AgentCli` enum (`CLAUDE`, `CODEX`) selects the right implementation at runtime.

## 1. Project Structure

```
src/main/kotlin/com/github/vgirotto/prism/
├── actions/               # Keyboard shortcuts and menu actions
│   ├── OpenAgentAction.kt
│   ├── ShowDiffAction.kt
│   ├── SendSelectionAction.kt
│   ├── InsertFileReferenceAction.kt
│   ├── MentionFolderAction.kt
│   ├── NewSessionAction.kt
│   └── AskAgentAction.kt
│
├── listeners/             # Event listeners
│   └── FileChangeListener.kt (VFS monitoring)
│
├── model/                 # Data classes
│   ├── AgentCli.kt (CLAUDE / CODEX)
│   ├── AgentSession.kt
│   ├── FileSnapshot.kt
│   ├── ConversationEntry.kt
│   └── PromptTemplate.kt
│
├── services/              # Core services
│   ├── AgentProcessManager.kt (Multi-session, PTY, Health Monitor)
│   ├── AgentTtyConnector.kt
│   ├── AgentSettingsState.kt (Persistence)
│   ├── CliBinaryLocator.kt (Shared PATH/binary lookup)
│   ├── ClaudeValidationService.kt / CodexValidationService.kt
│   ├── BannerParser.kt (per-CLI startup banner parsing)
│   ├── HistoryReader.kt (interface)
│   ├── ClaudeHistoryReader.kt / CodexHistoryReader.kt
│   ├── ConversationHistoryService.kt
│   ├── FileSnapshotService.kt (Incremental snapshots)
│   ├── ExclusionPatternMatcher.kt
│   ├── DiffViewService.kt (Native IDE diff)
│   └── ContextProvider.kt
│
├── settings/              # Settings UI
│   └── AgentSettingsConfigurable.kt
│
├── toolwindow/            # Main UI
│   ├── AgentToolWindowFactory.kt
│   ├── AgentToolbar.kt
│   ├── ToolbarItems.kt (per-CLI toolbar gating)
│   ├── AgentStatusBarWidget.kt
│   ├── NewSessionPopupAction.kt
│   ├── DiffPanel.kt
│   └── HistoryPanel.kt
│
└── i18n/                  # Internationalization
    └── PrismBundle.kt
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

The diff system captures a snapshot of the project before each agent interaction and computes the delta after the agent finishes writing to disk.

```
  ┌──────────────────────────────────────────────────────────────┐
  │                     User sends message                       │
  │                     (Enter in terminal)                      │
  └──────────────┬───────────────────────────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────┐     ┌───────────────────────┐
  │      takeSnapshot()          │     │   /tmp/agent-snap/    │
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
  │                  Agent CLI modifies files                    │
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
  │    original = read from /tmp/agent-snap/{path}               │
  │    current  = read from project/{path}                       │
  │    if hash differs → FileDiffEntry(MODIFIED, original, cur)  │
  │    if file missing → FileDiffEntry(DELETED, original, null)  │
  │    if not in snap  → FileDiffEntry(ADDED, null, current)     │
  └──────────────┬───────────────────────────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              Agent Changes Panel                             │
  │  ┌────────────────────────────┐  ┌───────────────────────┐  │
  │  │ ~ src/main.kt              │  │ DiffManager.showDiff() │  │
  │  │ + new_file.kt              │  │ (native side-by-side)  │  │
  │  │ - old_file.kt              │  │                       │  │
  │  │ [Revert File] [Revert All] │  │ Before agent | After   │  │
  │  │ ◀ #3 (Last) ▶              │  │                       │  │
  │  └────────────────────────────┘  └───────────────────────┘  │
  └──────────────────────────────────────────────────────────────┘
```

### Snapshot System

The snapshot mechanism is designed to minimize memory usage while keeping diff computation fast and accurate.

**On first interaction**, `FileSnapshotService` copies the entire project tree to a temporary directory (`/tmp/agent-snap/`). A SHA-256 hash is computed for each file and stored in a lightweight in-memory index (approximately 100 bytes per file).

Snapshot exclusions are applied before files are copied. The exclusion list accepts comma-separated names and wildcard patterns such as `cmake-build-*` or `**/generated`.

**On subsequent interactions**, only files whose paths were touched since the last snapshot (tracked via `BulkFileListener`) are re-copied and re-hashed. This incremental strategy avoids redundant I/O on large projects.

**At diff time**, `computeDiff()` compares the current file content against the snapshot copy using the hash index as a fast pre-filter. Only files with a differing hash are read in full and presented in the Agent Changes Panel. The native IDE `DiffManager` renders a side-by-side view labeled "Before agent" and "After".

Each interaction is also tagged as a Local History label, providing an independent recovery path outside of the plugin's own revert mechanism.

## 4. Performance

The table below reflects typical measurements on macOS with an SSD. Incremental snapshot time refers to a scenario where fewer than 10% of files changed since the previous interaction.

| Repo size | RAM (hash index) | Disk (temp) | First snapshot | Incremental |
|-----------|-----------------|-------------|----------------|-------------|
| 100 files | ~10 KB | ~5 MB | <100ms | <10ms |
| 1K files | ~100 KB | ~50 MB | <500ms | <50ms |
| 10K files | ~1 MB | ~500 MB | ~2-5s | <100ms |

The hash index is the only persistent in-memory structure; file contents are never held in RAM beyond the duration of a single diff computation pass.
