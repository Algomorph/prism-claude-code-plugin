# Contributing to Prism — IDE Companion for Claude Code

Thank you for your interest in contributing. This document explains how to get started and what is expected from contributors.

## How to Contribute

1. Fork the repository at [github.com/VGirotto/prism-claude-code-plugin](https://github.com/VGirotto/prism-claude-code-plugin).
2. Create a feature branch from `main`:
   ```
   git checkout -b feat/your-feature-name
   ```
3. Make your changes, commit them (see [Commit Messages](#commit-messages)), and push to your fork.
4. Open a Pull Request against the `main` branch of the upstream repository.
5. Describe what the PR does, why it is needed, and how it was tested.

All PRs are reviewed before merging. Small, focused changes are preferred over large, wide-ranging ones.

## Development Setup

### Prerequisites

- **JDK 17 or later.** The bundled JBR that ships with any recent JetBrains IDE works well:
  ```
  export JAVA_HOME="/path/to/IDE.app/Contents/jbr/Contents/Home"
  ```
- **IntelliJ IDEA 2024.3+** (Community or Ultimate) is recommended for development.
- The IntelliJ Platform Plugin SDK 2.x and Gradle are managed automatically by the project wrappers — no separate installation is required.

### Clone and Build

```bash
git clone https://github.com/VGirotto/prism-claude-code-plugin.git
cd prism-claude-code-plugin
./gradlew build
```

### Key Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew build` | Compile and assemble the project |
| `./gradlew test` | Run the test suite |
| `./gradlew buildPlugin` | Produce the distributable `.zip` artifact under `build/distributions/` |
| `./gradlew runIde` | Launch a sandboxed IDE instance with the plugin loaded |
| `./gradlew verifyPlugin` | Run IntelliJ Plugin Verifier checks against the declared IDE range |

## Testing the Plugin

The primary way to test is with the Gradle sandbox:

```bash
./gradlew runIde
```

This launches a fresh IDE instance with the plugin installed. No changes to your main IDE installation are made.

You can also install a built artifact manually:

1. Run `./gradlew buildPlugin` to produce the `.zip` file.
2. In your IDE go to **Settings > Plugins > Install Plugin from Disk** and select the generated `.zip`.

Always run `./gradlew test` and `./gradlew verifyPlugin` before submitting a PR.

## IDE Compatibility

The plugin supports **IntelliJ 2024.3 (build 243) and newer** with no upper version limit. To ensure your changes don't break compatibility:

1. **Run the Plugin Verifier** — this downloads and tests against multiple IDE versions automatically:
   ```bash
   ./gradlew verifyPlugin
   ```
   This will verify against the recommended set of IDE builds (2024.3 through the latest). Fix any reported errors before submitting.

2. **Check for deprecated APIs** — the verifier reports deprecated and internal API usages. Avoid introducing new ones.

3. **Keep `sinceBuild` at 243** — do not raise the minimum unless absolutely necessary, to preserve backward compatibility.

4. **Do not set `untilBuild`** — the project deliberately omits the upper bound. If you need to restrict compatibility for a specific reason, discuss it in an issue first.

5. **Keep Kotlin at 1.9.x** — the plugin uses the IDE's bundled Kotlin stdlib (`kotlin.stdlib.default.dependency = false`). IntelliJ 2024.3 bundles Kotlin 1.9.x, so compiling with Kotlin 2.x could produce incompatible bytecode.

## Bumping the Plugin Version

When your PR introduces a change that warrants a version bump, update **all** of these files:

| File | What to update |
|------|----------------|
| `gradle.properties` | `pluginVersion` — source of truth, used by Gradle to patch `plugin.xml` at build time |
| `README.md` | Version badge on line 3 (`version-X.Y.Z-blue`) |
| `README.pt-BR.md` | Version badge on line 3 (same format) |
| `CHANGELOG.md` | Add a new `## [X.Y.Z] — YYYY-MM-DD` section at the top |
| `plugin.xml` `<change-notes>` | Add a new `<h2>vX.Y.Z — Title</h2>` block at the top of the CDATA section |

Do **not** add a `<version>` tag to `plugin.xml` — it is injected automatically by `patchPluginXml` from `gradle.properties`.

If you're unsure whether your change warrants a version bump, leave it out and mention it in the PR — the maintainer will handle it.

## Code Style

- Follow the conventions already present in the codebase.
- Prefer idiomatic Kotlin: use `when` expressions, extension functions, data classes, and null-safety operators where appropriate.
- Avoid unnecessary abstraction. Keep changes easy to read and review.
- Do not introduce new external dependencies without discussing it in an issue first.

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/) style:

```
<type>(<scope>): <short description>
```

Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`.

Examples:
```
feat(terminal): add auto-scroll toggle to tool window
fix(cli): handle missing PATH entry on macOS
docs: update setup instructions for JDK 17
```

Keep the subject line under 72 characters. Add a body if the motivation or context is not obvious from the subject alone.

## License

By contributing you agree that your code will be distributed under the [Apache License 2.0](LICENSE), the same license that covers this project.
