# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Ctrl+V on Linux**: now pastes whatever is on the clipboard. If the clipboard holds an image, the Claude CLI's image-paste runs (previous behavior); otherwise the clipboard text is pasted into the prompt using bracketed-paste escapes so multi-line content doesn't auto-submit. Force plain-text paste with `Ctrl+Shift+V`. macOS and Windows keep the native `Ctrl+V` paste.

## [1.2.1] — 2026-06-15

### Added

- **Wildcard snapshot exclusions**: exclusion patterns now support `*`, `?`, and `**` (e.g. `cmake-build-*`, `**/generated`)

### Changed

- **Exclusion matching**: exact patterns (`build`, `target`, etc.) now match any path segment, so they also exclude nested directories like `src/build/`

### Fixed

- **EDT freeze**: diff computation is now dispatched to a background thread, eliminating IDE freezes on projects with many tracked files (fixes #9)

## [1.2.0] — 2026-04-17

### Added

- **Clear button**: new toolbar button that sends `/clear` with a confirmation dialog (same UX pattern as Compact)
- **Effort: xhigh level**: added `xhigh` effort level between `high` and `max` in the effort dropdown
- **Effort picker**: "Open effort picker..." option in the effort dropdown — sends `/effort` to open Claude's native interactive slider in the terminal
- **Model picker**: "Open model picker..." option in the model dropdown — sends `/model` to open Claude's native interactive model selector in the terminal
- **Mention in Claude**: new "Mention in Claude" right-click action in the Project Explorer — inserts `@relative/path` at the terminal cursor for any file or folder

### Fixed

- **Send Selection shortcut**: selection reference (`@file:line`) is now inserted with a trailing space instead of a newliner

## [1.1.2] — 2026-03-31

### Fixed

- **API compliance**: replaced 8 usages of internal `ActionToolbarImpl` with public `ActionManager.createActionToolbar()` API across ClaudeToolbar, DiffPanel, and HistoryPanel
- **Deprecated API**: replaced `FileChooserDescriptorFactory.createSingleFileDescriptor()` with `FileChooserDescriptor` constructor in settings

## [1.1.1] — 2026-03-30

### Fixed

- **DiffPanel**: resolved `Write-unsafe context` error when refreshing VFS during tab selection — wrapped `VirtualFile.refresh()` in `invokeLater` for proper write-safe context

### Changed

- **Description**: rewritten plugin description for Marketplace with disclaimer, Apache 2.0 license notice, and contribution links
- **Icon**: added `pluginIcon_dark.svg` for better visibility on dark themes
- **Metadata**: removed hardcoded `<version>` from plugin.xml (now sourced solely from gradle.properties), updated vendor email

## [1.1.0] — 2026-03-27

### Changed

- **Compatibility**: removed upper IDE build limit (`untilBuild`) — plugin now works with IntelliJ 2024.3 and all future versions (fixes install error on 2026.1+)
- **Dependencies**: updated IntelliJ Platform Gradle Plugin (2.2.1 → 2.11.0), JUnit Jupiter (5.10.2 → 5.11.4), Gradle wrapper (8.10.2 → 8.13)

### Fixed

- **Actions**: added explicit `ActionUpdateThread.BGT` override to `AskClaudeAction`, `SendSelectionAction`, `ShowDiffAction`, `InsertFileReferenceAction`, and `OpenClaudeAction` (best practice for IntelliJ 241+, eliminates deprecation warnings on newer builds)

## [1.0.1] — 2026-03-26

### Fixed

- **History**: fix project path escaping for directories containing underscores (e.g. `my_cool-project`). Claude Code replaces both `/` and `_` with `-`, but the plugin only replaced `/`. Added multi-strategy resolution with fuzzy fallback.

### Changed

- **Icon**: new minimalist diamond outline icon replacing the old "C" letter badge.

## [1.0.0] — Unreleased

### Added

**Terminal & Process Management**
- Interactive terminal with Claude Code CLI integrated into the IDE
- Full ANSI color and text formatting support
- Real PTY (pty4j + JediTerm) for maximum compatibility
- Multi-session: multiple independent sessions in simultaneous tabs
- Auto-start Claude when opening a project (configurable)

**Diff View & Change Tracking**
- Claude Changes panel: visualize files modified per interaction
- Native IDE side-by-side diff (original vs. modified)
- Incremental snapshots on disk (zero RAM overhead for large repositories)
- Revert by file or by complete interaction
- History navigation between interactions (previous / next)
- Automatic refresh after Claude finishes
- "Clear Interactions" button with confirmation and cross-panel synchronization

**IDE Integration & Context**
- Send Selection: send selected text to Claude
- Insert File Reference: insert @path into the terminal
- Context menu actions: Explain / Review / Fix / Generate Tests / Refactor
- Auto-capture of context (active file, selection, open files)
- Customizable keyboard shortcuts (Cmd/Alt on macOS, Ctrl/Alt on Linux)

**Toolbar & Productivity**
- Compact toolbar with quick-action buttons
- Dropdowns: Model (opus/sonnet/haiku), Effort (auto/low/medium/high/max), Cost
- Buttons: Compact, Resume, Templates
- Prompt Templates: reusable with variables {selection}, {file}, {language}

**Settings & Configuration**
- Appearance: toggle Changes panel on startup, Status Bar widget
- Snapshot: exclusion patterns, maximum file size
- Configurable Claude path, shell, and auto-start
- Language: English, Portuguese, Spanish (selectable in settings)

**History & Sessions**
- Conversation History browser: navigate previous conversations
- Full-text search across history
- Support for multiple parallel sessions with independent state
- History view with native IDE formatting

**Status Visibility**
- Status Bar widget: shows Claude state (working / idle / stopped)
- Model and effort visible in real time
- Click the widget to open the Claude panel
- Multi-session support: [2/4 working]

### Technical

- **Language**: Kotlin + Gradle Kotlin DSL
- **Platform**: IntelliJ Platform Plugin 2.x, IDE 2024.3+
- **Runtime**: JDK 17+
- **Testing**: 34+ unit tests covering the main services
- **CI/CD**: GitHub Actions ready for build, test, verify, and release
