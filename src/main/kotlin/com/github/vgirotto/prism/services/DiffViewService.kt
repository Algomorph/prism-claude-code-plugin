package com.github.vgirotto.prism.services

import com.github.vgirotto.prism.model.ChangeStatus
import com.github.vgirotto.prism.model.FileDiffEntry
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class DiffViewService(private val project: Project) {

    private val log = Logger.getInstance(DiffViewService::class.java)

    fun showDiffForFile(entry: FileDiffEntry) {
        closeAllDiffTabs()

        val request = createDiffRequest(entry)
        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Closes all diff editor tabs by checking the VirtualFile class name.
     * Diff tabs use DiffVirtualFile or similar non-local-filesystem files
     * whose class name contains "Diff".
     */
    private fun closeAllDiffTabs() {
        val editorManager = FileEditorManager.getInstance(project)
        val openFiles = editorManager.openFiles.toList()

        for (vFile in openFiles) {
            val className = vFile.javaClass.simpleName
            val isInLocalFs = vFile.isInLocalFileSystem

            // Diff tabs are NOT in the local filesystem and their class contains "Diff"
            if (!isInLocalFs && (className.contains("Diff", ignoreCase = true) ||
                    className.contains("Light", ignoreCase = true))) {
                try {
                    log.info("Closing diff tab: name=${vFile.name}, class=$className")
                    editorManager.closeFile(vFile)
                } catch (e: Exception) {
                    log.debug("Failed to close tab: ${vFile.name}", e)
                }
            }
        }
    }

    private fun createDiffRequest(entry: FileDiffEntry): SimpleDiffRequest {
        val contentFactory = DiffContentFactory.getInstance()

        val originalText = entry.originalContent?.toString(Charsets.UTF_8) ?: ""
        val modifiedText = entry.modifiedContent?.toString(Charsets.UTF_8) ?: ""

        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(entry.path)

        val leftContent = contentFactory.create(project, originalText, fileType)
        val rightContent = contentFactory.create(project, modifiedText, fileType)

        val prefix = when (entry.status) {
            ChangeStatus.ADDED -> "+"
            ChangeStatus.MODIFIED -> "~"
            ChangeStatus.DELETED -> "-"
        }

        return SimpleDiffRequest(
            "$prefix ${entry.path}",
            leftContent,
            rightContent,
            "Before agent",
            "After agent"
        )
    }

    companion object {
        fun getInstance(project: Project): DiffViewService =
            project.getService(DiffViewService::class.java)
    }
}
