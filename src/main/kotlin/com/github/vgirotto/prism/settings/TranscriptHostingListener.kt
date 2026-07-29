package com.github.vgirotto.prism.settings

import com.intellij.util.messages.Topic

/**
 * Broadcast when the "Show transcript in the editor area" setting changes, so open Prism chats
 * can migrate their transcript between the tool-window split and an editor tab live, without
 * disturbing the running session (design §6.4). Application-level, matching [AgentSettingsState].
 */
fun interface TranscriptHostingListener {

    /** [inEditor] is the new value of the setting. */
    fun hostingChanged(inEditor: Boolean)

    companion object {
        @JvmField
        val TOPIC: Topic<TranscriptHostingListener> =
            Topic.create("Prism transcript hosting", TranscriptHostingListener::class.java)
    }
}
