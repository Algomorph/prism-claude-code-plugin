package com.github.vgirotto.prism.chatshell

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
 * It is not disk-backed (a temp-scheme [LightVirtualFile]), so the platform cannot resolve it
 * after an IDE restart and will not try to reopen an orphaned transcript tab with no session
 * behind it. [equals]/[hashCode] key on [sessionId] so opening is idempotent even if a fresh
 * instance is handed to `FileEditorManager`.
 */
class TranscriptVirtualFile(
    val sessionId: String,
    val convId: String,
    val chatName: String,
) : LightVirtualFile("$chatName ✓") {

    override fun equals(other: Any?): Boolean =
        other is TranscriptVirtualFile && other.sessionId == sessionId

    override fun hashCode(): Int = sessionId.hashCode()

    companion object {
        /** True for the transcript tab bound to [sessionId]. */
        fun matches(file: VirtualFile, sessionId: String): Boolean =
            file is TranscriptVirtualFile && file.sessionId == sessionId
    }
}
