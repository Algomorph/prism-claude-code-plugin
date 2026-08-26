# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.1] — 2026-08-26

### Added

- **Terminal font and terminal settings**: the Prism terminal now uses the IDE terminal's own settings provider, so the font family, font size, and line spacing from Settings > Tools > Terminal > Font Settings apply — along with Ctrl+wheel zoom, live updates when those settings change, and the mouse-reporting, bell, and shortcut-override preferences set on that page. Prism previously used the editor's console font at an unscaled size, which ignored a terminal font you had set and rendered tiny on HiDPI displays.
- **Font Settings menu entry**: the options (⋮) menu of the Prism tool window now has a `Font Settings` entry, which opens the IDE's terminal settings page. The page is matched on its configurable ID, which is stable across releases and independent of the IDE's display language. The gear icon in the toolbar continues to open the settings of Prism.

### Fixed

- **Custom CLI arguments**: Claude Code and Codex command settings now accept optional arguments, including quoted paths with spaces. Prism validates the executable separately and preserves each argument when starting the session (fixes #13).
- **Reordering chat tabs**: dragging a tab to a new position no longer freezes it. The platform reorders a tab by removing its content and re-adding it at the new index, which Prism read as the tab being closed and used as the cue to kill that session's agent process — the tab came back with its terminal still painted but nothing running behind it. Session teardown is now tied to the tab actually being disposed, which only a real close does.
- **Changes panel HTML injection**: file names shown in the Agent Changes panel (list label and tooltip) are now escaped before being rendered as HTML, so a file created or renamed with characters like `<`, `>`, or `&` — including by an AI agent acting on the project — can no longer inject markup or trigger unintended content in the panel.

## [1.3.0] — 2026-08-18

### Added

- **Codex integration**: Prism now supports both Claude Code and OpenAI Codex CLI sessions from the same tool window.
- **New Session picker**: when both supported CLIs are installed, the New Session action lets you choose whether to start a Claude Code or Codex session.
- **Default agent setting**: settings now include a default CLI plus separate executable paths for Claude Code and Codex.
- **Codex conversation history**: the History panel can browse Codex sessions from `~/.codex/sessions`, filtered to the current IDE project by `cwd`.
- **Full toolbar for Codex**: the Resume, Compact, Clear, Model, Effort, and Cost buttons now work in Codex sessions, mapped to their Codex equivalents. Resume/Compact/Clear send the identical slash command; Model and Effort drive Codex's interactive `/model` picker (model list and reasoning level) via keystrokes; Cost is a dropdown that opens Codex's `/usage` token-activity view for the daily, weekly, or cumulative period.

### Changed

- **Agent Changes panel**: the changes window is now agent-agnostic, so Codex sessions use the same per-interaction snapshots, diff navigation, and revert workflow as Claude sessions.
- **Agent terminology**: user-facing actions, settings, status, and documentation now refer to Prism or the active agent where behavior applies to both Claude Code and Codex.
- **Plugin metadata**: plugin name, Marketplace description, README, and localized messages now describe support for Claude Code and Codex.
- **Excluded patterns field**: settings now use a multiline text area (one pattern per line) instead of a single-line field, with a live count of patterns defined; comma-separated values remain supported for backward compatibility.

### Fixed

- **Snapshot/revert symlink safety**: snapshots and revert operations now skip any path that crosses a symbolic link, preventing an interaction diff from reading or overwriting files outside the project directory through a symlinked folder.

## [1.2.2] — 2026-06-30

### Changed

- **Ctrl+V on Linux**: now pastes whatever is on the clipboard. If the clipboard holds an image, the image bytes are written to a temporary PNG and the file path is pasted into the prompt (Claude attaches the file); otherwise the clipboard text is pasted using bracketed-paste escapes so multi-line content doesn't auto-submit. Force plain-text paste with `Ctrl+Shift+V`. macOS and Windows keep the native `Ctrl+V` paste.

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
