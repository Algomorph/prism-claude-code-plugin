package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.model.AgentCli
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * In-memory identity for a chat's transcript editor tab (design: transcript-in-editor mode).
 *
 * Keyed by [sessionId] so each Prism chat maps to exactly one transcript tab; carries [convId]
 * for the live tail (read by [TranscriptFileEditor]) and [chatName] for the tab title. The
 * presentable name is the chat name followed by a check mark (e.g. `Chat #1 ✓`) so the editor
 * tab is visually paired with the matching Prism tool-window tab.
 *
 * [cli] tells [TranscriptFileEditor] how to tail: Claude keys its transcript file on [convId]
 * (`--session-id`), while Codex has no caller-supplied id and instead resolves the newest
 * rollout for the project by cwd + recency (design §11).
 *
 * It is not disk-backed (a temp-scheme [LightVirtualFile]), so the platform cannot resolve it
 * after an IDE restart and will not try to reopen an orphaned transcript tab with no session
 * behind it. [equals]/[hashCode] key on [sessionId] so opening is idempotent even if a fresh
 * instance is handed to `FileEditorManager` — and so a [chatName] change cannot strand the file
 * in the editor manager's maps.
 */
class TranscriptVirtualFile(
    val sessionId: String,
    val convId: String,
    chatName: String,
    val cli: AgentCli,
) : LightVirtualFile("$chatName ✓") {

    /**
     * The chat's display name, which changes when the agent CLI titles or retitles the
     * conversation. The platform reads a tab's title once, when the editor is created, so
     * assigning this renames the tab on its next open rather than in place — acceptable because
     * the toggle that owns this tab closes and reopens it, and because the tool-window tab (the
     * primary surface) does rename live.
     */
    @Volatile
    var chatName: String = chatName

    override fun getName(): String = "$chatName ✓"

    override fun equals(other: Any?): Boolean =
        other is TranscriptVirtualFile && other.sessionId == sessionId

    override fun hashCode(): Int = sessionId.hashCode()

    companion object {
        /** True for the transcript tab bound to [sessionId]. */
        fun matches(file: VirtualFile, sessionId: String): Boolean =
            file is TranscriptVirtualFile && file.sessionId == sessionId
    }
}
