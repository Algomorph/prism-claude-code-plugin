package com.github.vgirotto.prism.chatshell

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * Hosts a chat's rendered transcript as a tab in the IDE editor area (transcript-in-editor
 * mode). It owns its own [TranscriptView] + [TranscriptController] — the rendering stack is
 * host-agnostic, so this is the same wiring the tool-window split uses, just re-homed.
 *
 * The editor's own [selectNotify]/[deselectNotify] hooks drive the controller's
 * resume/pause (background-tab batching, R20): a transcript tab that is not the visible
 * editor coalesces its deltas instead of rendering them one by one.
 */
class TranscriptFileEditor(
    project: Project,
    private val file: TranscriptVirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val changeSupport = PropertyChangeSupport(this)
    private val editorDisposable = Disposer.newDisposable("PrismTranscriptEditor:${file.sessionId}")

    private val view = TranscriptView(editorDisposable).apply {
        onOpenLink = { href -> try { BrowserUtil.browse(href) } catch (_: Exception) {} }
    }
    private val controller = TranscriptController(project, view).also {
        Disposer.register(editorDisposable, it)
    }

    @Volatile private var disposed = false
    private var attached = false

    init {
        // createEditor runs on the EDT, so building the JCEF browser here is safe. The tab was
        // opened explicitly to be shown, so attach the live tail eagerly; select/deselect then
        // only toggle rendering on/off.
        view.initialize(ChatShellTheme.currentVars())
        ensureAttached()
    }

    private fun ensureAttached() {
        if (attached || disposed) return
        attached = true
        controller.attachLive(file.convId)
    }

    override fun getComponent(): JComponent = view.component
    override fun getPreferredFocusedComponent(): JComponent = view.component
    override fun getName(): String = file.chatName
    override fun getFile(): VirtualFile = file

    override fun selectNotify() {
        ensureAttached()
        controller.resume()
    }

    override fun deselectNotify() {
        controller.pause()
    }

    override fun setState(state: FileEditorState) {}
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed
    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        if (disposed) return
        disposed = true
        Disposer.dispose(editorDisposable)
    }
}
