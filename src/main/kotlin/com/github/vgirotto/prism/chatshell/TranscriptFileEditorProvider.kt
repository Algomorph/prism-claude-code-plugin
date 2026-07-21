package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

/**
 * Routes [TranscriptVirtualFile]s to a [TranscriptFileEditor] (transcript-in-editor mode).
 * Registered via `com.intellij.fileEditorProvider`. [HIDE_DEFAULT_EDITOR] keeps the platform
 * from also opening a plain-text editor for the in-memory file.
 */
class TranscriptFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is TranscriptVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TranscriptFileEditor(project, file as TranscriptVirtualFile)

    override fun disposeEditor(editor: FileEditor) = Disposer.dispose(editor)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "prism-transcript"
    }
}
