package com.github.vgirotto.prism.chatshell

import java.io.File

/**
 * Maps a session to its JSONL transcript file (design §6.5, §9).
 *
 * Group 0 confirmed the project-dir escaping is **lossy** (`/`→`-`, `_`→`-`, existing `-`
 * preserved) and therefore **not invertible** — so this resolver always escapes the known
 * project path *forward* and never reconstructs a path from a directory name. For a
 * brand-new project the directory may not exist yet; [projectDir] returns the computed
 * path regardless, and [projectsRoot] is the parent to watch for its creation (§6.7).
 *
 * Identity (R2): the transcript file is `<conversationId>.jsonl`, where `conversationId`
 * is a mutable field on the session distinct from the stable process-map key
 * `ClaudeSession.id`. A native `/resume` changes the conversation, not the tab.
 */
class SessionResolver(
    private val projectBasePath: String?,
    userHome: String = System.getProperty("user.home"),
) {
    val projectsRoot: File = File(userHome, ".claude/projects")

    /** Forward escape only — never inverted (Group 0, R13). */
    fun escapedProjectName(): String? =
        projectBasePath?.replace("/", "-")?.replace("_", "-")

    /** The computed project history dir (may not exist yet for a fresh project). */
    fun projectDir(): File? = escapedProjectName()?.let { File(projectsRoot, it) }

    /** The transcript file for a conversation id, in the forward-escaped project dir. */
    fun transcriptFile(conversationId: String): File? =
        projectDir()?.let { File(it, "$conversationId.jsonl") }

    /**
     * For *reading existing* history only: resolve an already-created dir, tolerating the
     * fuzzy escaping variations (mirrors `ConversationHistoryService`). Never used to
     * choose our own session's file — that is always the forward-escaped [projectDir].
     */
    fun existingProjectDir(): File? {
        val base = projectBasePath ?: return null
        if (!projectsRoot.isDirectory) return null
        val primary = File(projectsRoot, base.replace("/", "-").replace("_", "-"))
        if (primary.isDirectory) return primary
        val slashOnly = File(projectsRoot, base.replace("/", "-"))
        if (slashOnly.isDirectory) return slashOnly
        val normalize = { s: String -> s.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase() }
        val target = normalize(base)
        return projectsRoot.listFiles { f -> f.isDirectory }
            ?.firstOrNull { normalize(it.name) == target }
    }

    /** True when [projectDir] exists on disk (else the tailer must watch [projectsRoot]). */
    fun projectDirExists(): Boolean = projectDir()?.isDirectory == true
}
