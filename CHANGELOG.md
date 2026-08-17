# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] — Not yet published

### Added

- **Codex integration**: Prism now supports both Claude Code and OpenAI Codex CLI sessions from the same tool window.
- **New Session picker**: when both supported CLIs are installed, the New Session action lets you choose whether to start a Claude Code or Codex session.
- **Default agent setting**: settings now include a default CLI plus separate executable paths for Claude Code and Codex.
- **Codex conversation history**: the History panel can browse Codex sessions from `~/.codex/sessions`, filtered to the current IDE project by `cwd`.
- **Full toolbar for Codex**: the Resume, Compact, Clear, Model, Effort, and Cost buttons now work in Codex sessions, mapped to their Codex equivalents. Resume/Compact/Clear send the identical slash command; Model and Effort drive Codex's interactive `/model` picker (model list and reasoning level) via keystrokes; Cost is a dropdown that opens Codex's `/usage` token-activity view for the daily, weekly, or cumulative period.
- **Hybrid WYSIWYG chat shell**: each chat session now has a rendered transcript of the conversation. Prism reads the transcript from the session's JSONL file. The transcript shows typeset **KaTeX math**, **inline images**, collapsible tool calls and results, and thinking disclosures. Click a formula to show its exact LaTeX source and to copy it. The terminal keeps all interactive actions. These actions include input, approvals, pickers, plan mode, `$`-skills, and smart paste.
  - Security: Prism treats all transcript content as unsafe input. The embedded browser does all of the rendering. Prism escapes the text, processes it with marked, cleans it with **DOMPurify**, and then adds trusted KaTeX nodes and image nodes. A strict CSP permits only the scripts that match a known hash. A media resolver decodes each image, validates it, and encodes it again. The resolver rejects SVG files and remote files. A request interceptor blocks all external requests.
  - The transcript updates while the agent works. It uses the light theme or the dark theme of the IDE, and it changes theme immediately. Prism supplies the transcript in English, Spanish, and Portuguese.
  - You can show the transcript in the editor area or in the tool-window split. Select the location in Settings. Prism uses the editor area by default. In the tool-window split, the terminal keeps a minimum height, and you can expand the terminal to the full height.
  - Claude sessions and Codex sessions both show the transcript. For a Claude session, Prism identifies the transcript file with the `--session-id` option. For a Codex session, Prism finds the rollout files for the project directory and selects the most recent file. If Prism cannot read a transcript, the terminal continues to work, and the transcript pane shows that the transcript is not available.
- **Session names on chat tabs**: each chat tab shows the name of its conversation instead of `Chat #1`. Prism reads the name from the session file on disk, and it updates the tab while the chat runs.
  - For a Claude session, Prism uses the title that Claude records. A title that you set wins over a title that Claude generates.
  - Codex records no title, so for a Codex session Prism uses the first user message. This is the same label that the Codex `/resume` picker shows.
  - If no name is available, the tab keeps its number. Prism clips a long name at a word boundary. The tooltip shows the full name and the name of the agent.
- **Agent marks on tabs**: each chat tab, the New Session picker, and the transcript editor tab show a mark for the active agent. The marks are simple shapes in the Prism colors. They are not vendor logos.
- **Terminal font**: the terminal in Prism now uses the font settings of the IDE terminal. Set the font family, the font size, and the line spacing in Settings > Tools > Terminal > Font Settings. Prism used the console font of the editor before, and it ignored a terminal font that you set. On a HiDPI display, the text was also too small.
- **Font Settings menu entry**: the options (⋮) menu of the Prism tool window now has a `Font Settings` entry. The entry opens the terminal settings page of the IDE. The gear icon in the toolbar continues to open the settings of Prism.

### Changed

- **Agent Changes panel**: the changes window is now agent-agnostic, so Codex sessions use the same per-interaction snapshots, diff navigation, and revert workflow as Claude sessions.
- **Agent terminology**: user-facing actions, settings, status, and documentation now refer to Prism or the active agent where behavior applies to both Claude Code and Codex.
- **Plugin metadata**: plugin name, Marketplace description, README, and localized messages now describe support for Claude Code and Codex.

### Fixed

- **Reordering chat tabs**: dragging a tab to a new position no longer freezes it. The platform reorders a tab by removing its content and re-adding it at the new index, which Prism read as the tab being closed and used as the cue to kill that session's agent process — the tab came back with its terminal still painted but nothing running behind it. Session teardown is now tied to the tab actually being disposed, which only a real close does.
- **Copy from the terminal**: you can now select terminal text with the mouse and copy it. Claude and Codex turn on mouse reporting, and the terminal sent each drag to the agent instead of making a selection. The `Copy` command in the context menu stayed disabled, and the drag only painted the highlight of the agent.

### Technical

- Codex model detection skips the `loading` placeholder Codex paints in its welcome box before the real model resolves, so the status bar shows the actual model instead of `loading` for the life of the session. The reasoning level is read from the same line, with the older separate `reasoning effort:` line kept as a fallback.
- CLI binary lookups fall back to the PATH the user's login shell exports — read once at IDE startup by the platform — instead of spawning `which` against the IDE's own environment. A GUI-launched IDE inherits that environment from the desktop session, which on macOS means launchd's `/usr/bin:/bin:/usr/sbin:/sbin`, so a CLI under `~/.local/bin`, nvm, volta, or Homebrew was invisible to the lookup even though `which` finds it in a terminal.
- Every lookup re-checks disk rather than caching: with no process to spawn, a full miss over all candidate paths and PATH entries costs tens of microseconds, so a CLI installed, upgraded in place, or removed mid-session is seen by the next New Session click with no cached answer to go stale first.
- The New Session picker takes keyboard focus while it is open, so Escape dismisses it without also reaching the agent running in the terminal behind it.
- Sessions launch the absolute binary path the availability preflight already resolved, rather than re-resolving the configured name through the login shell's PATH.
- New-session startup logs phase timings at INFO, measured monotonically: four lines greppable as `timing:` (click → availability → UI, preflight resolve, PTY spawn, first shell output), plus a launch-relative offset appended to the existing `Startup parsed` line.
- Replaced Claude-specific session, process, terminal, settings, toolbar, and tool-window classes with agent-aware equivalents.
- Split conversation history parsing behind a `HistoryReader` interface with dedicated Claude and Codex readers.
- Added shared CLI binary lookup and Codex validation services, plus tests for agent settings, Codex history parsing, toolbar availability, banner parsing, and CLI path resolution.
- Added the transcript stack behind an agent-specific seam. The stack has a non-lossy JSONL parser, a secure JCEF render pipeline, incremental live tailing, and `/resume` rebinding. Claude and Codex each supply their own transcript source through this seam.
- Added live chat-name resolution behind a per-agent source seam. Each source does one bounded tail read of a candidate session file. The read identifies the conversation and supplies the title. A rank keeps a better name from losing to a worse name.
- The transcript editor tab renames in place. Prism looks up the refresh call on the editor manager at runtime, and it prefers the public name. This keeps the plugin off internal API on all supported hosts.
- Prism now finds the Conversation History tab by a key instead of by its display name, because a chat can now use the name `History`.
- The terminal settings provider delegates the font, the font size, and the line spacing to the provider that the IDE terminal uses. Prism builds that provider through the classloader of the terminal plugin, because the class is not on the 2024.3 API baseline. Each delegate falls back to the platform base class if the provider is absent. Prism still extends the platform base class, so the tuned shortcut behavior and paste behavior do not change. One INFO line records the font family and the size that Prism resolved, or the cause of a failure.
- The Font Settings entry finds the terminal settings page by its configurable class, which is independent of the language. If the class is absent, Prism selects the page by its display name.
- The terminal settings provider now overrides `forceActionOnMouseReporting`. It delegates to the provider of the IDE terminal, and it uses `true` as the fallback value. The JediTerm default is `false`, and that default is the cause of the defect.

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
