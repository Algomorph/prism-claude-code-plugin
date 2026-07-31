package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Makes an open editor tab re-read its title after the file behind it was renamed.
 *
 * The platform asks a file for its name when the editor is created and then caches it, so
 * assigning [TranscriptVirtualFile.chatName] changes nothing on screen until the editor manager is
 * told the presentation is stale. Which call does that depends on the host: 2024.3 — the platform
 * Prism compiles against — only has it as `FileEditorManagerImpl.updateFilePresentation`, while
 * later builds (including the 2026.1 Android Studio line) promote it to the public
 * `FileEditorManagerEx.updateFileName` and mark the impl class internal. Neither is callable from
 * source across that whole range, and `untilBuild` is open, so the method is looked up on the
 * manager at runtime with the public one preferred.
 *
 * Both candidates no-op for a file that is not open, so callers need no such guard. Finding
 * neither is not an error either: the new name then appears the next time the tab is opened, which
 * the Show/Hide Transcript toggle does anyway.
 */
object TranscriptTabTitle {

    private val log = Logger.getInstance(TranscriptTabTitle::class.java)

    /** Refresh candidates in preference order: public API first, then the older impl-only call. */
    private val CANDIDATE_METHODS = listOf("updateFileName", "updateFilePresentation")

    /** Refresh [file]'s tab title in [project]. Must be called on the EDT. */
    fun refresh(project: Project, file: VirtualFile) {
        refreshVia(FileEditorManager.getInstance(project), file)
    }

    /**
     * Invoke the first refresh method [manager] actually has, returning whether one ran.
     *
     * Takes the manager as [Any] because the method being called is not on the interface Prism
     * compiles against — which is also what lets this be tested without an IDE.
     */
    internal fun refreshVia(manager: Any, file: VirtualFile): Boolean {
        for (name in CANDIDATE_METHODS) {
            val method = manager.javaClass.methods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == VirtualFile::class.java
            } ?: continue
            return try {
                method.invoke(manager, file)
                true
            } catch (t: Throwable) {
                // A host that renamed or gutted the call is not worth failing a rename over.
                log.warn("Could not refresh the editor tab title via $name()", t)
                false
            }
        }
        log.debug("No editor tab title refresh available on ${manager.javaClass.name}")
        return false
    }
}
