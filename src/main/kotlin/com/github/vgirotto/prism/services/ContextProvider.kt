package com.github.vgirotto.prism.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
class ContextProvider(private val project: Project) {

    /**
     * Returns the relative path of a file to the project root.
     * Falls back to the file name if not under the project.
     */
    fun relativePath(file: VirtualFile): String {
        val basePath = project.basePath ?: return file.name
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/")
        } else {
            file.name
        }
    }

    /**
     * Returns the currently active editor, if any.
     */
    fun getActiveEditor(): Editor? =
        FileEditorManager.getInstance(project).selectedTextEditor

    /**
     * Returns the VirtualFile for the active editor.
     */
    fun getActiveFile(): VirtualFile? {
        val editor = getActiveEditor() ?: return null
        return FileDocumentManager.getInstance().getFile(editor.document)
    }

    /**
     * Returns the selected text in the active editor, if any.
     */
    fun getSelectedText(): String? =
        getActiveEditor()?.selectionModel?.selectedText

    /**
     * Returns all currently open files in the editor.
     */
    fun getOpenFiles(): List<VirtualFile> =
        FileEditorManager.getInstance(project).openFiles.toList()

    /**
     * Formats a file reference using the agent's @ syntax.
     */
    fun formatFileReference(file: VirtualFile): String =
        "@${relativePath(file)}"

    /**
     * Formats a file reference with line range based on the editor selection.
     * Returns e.g. "@src/Main.kt:15-47" or "@src/Main.kt:42" for single-line.
     * Returns null if no file is associated with the editor.
     */
    fun formatSelectionReference(editor: Editor, file: VirtualFile?): String? {
        if (file == null) return null
        val doc = editor.document
        val selectionModel = editor.selectionModel
        val startLine = doc.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = doc.getLineNumber(selectionModel.selectionEnd) + 1
        val ref = formatFileReference(file)
        return if (startLine == endLine) "$ref:$startLine" else "$ref:$startLine-$endLine"
    }

    /**
     * Formats selected code with file context for sending to the agent.
     * Used as fallback when no file is available (e.g. scratch files).
     */
    fun formatSelectionForAgent(text: String, file: VirtualFile?, prompt: String? = null): String {
        val builder = StringBuilder()
        if (prompt != null) {
            builder.append(prompt)
            builder.append(" ")
        }
        if (file != null) {
            builder.append("in ${formatFileReference(file)}")
            builder.append(": ")
        }
        builder.append(text)
        return builder.toString()
    }

    companion object {
        fun getInstance(project: Project): ContextProvider =
            project.getService(ContextProvider::class.java)
    }
}
