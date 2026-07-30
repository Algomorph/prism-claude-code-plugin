package com.github.vgirotto.prism.chatshell

import com.github.vgirotto.prism.icons.PrismIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Marks a transcript editor tab with the icon of the agent behind it, matching the tool-window
 * chat tab it belongs to. Without this the platform falls back to the plain-text file icon, which
 * says nothing about which chat — or which CLI — the tab is showing.
 *
 * Registered via `com.intellij.fileIconProvider`; returning null for everything else leaves the
 * rest of the IDE's icons alone.
 */
class TranscriptFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        (file as? TranscriptVirtualFile)?.let { PrismIcons.forCli(it.cli) }
}
