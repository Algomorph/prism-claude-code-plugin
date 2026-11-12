package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.services.session.SessionIdentity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.io.File

/** One chat as the record keeps it: its agent, its name, and its conversation when known. */
data class ChatEntry(val cli: AgentCli, val name: String, val identity: SessionIdentity? = null)

/** What a chat content records before its session attaches: set at creation, see [ChatTabsRecord]. */
val CHAT_ENTRY_KEY: Key<ChatEntry> = Key.create("PrismChatEntry")

/**
 * The project's chats, in tool-window order, kept in its workspace file so they can reopen after a
 * restart. Saved from the live tool window ([attach]) at each IDE save, but only once restoration is
 * decided and done ([takeRestorePlan], [restoreFinished]), and never after the project starts
 * closing ([freeze]), so neither start-up nor teardown can erase it. It is kept whatever the
 * persistence settings say; they decide only whether it is restored.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "PrismChatTabs",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
    getStateRequiresEdt = true,
)
class ChatTabsRecord : PersistentStateComponent<ChatTabsRecord.SavedChats> {

    /** The record's serialized form. */
    class SavedChats {
        @get:XCollection(style = XCollection.Style.v2)
        var chats: MutableList<SavedChat> = ArrayList()
    }

    /** One serialized [ChatEntry]; an agent this Prism does not know is dropped on load. */
    @Tag("chat")
    class SavedChat {
        var cli: String = ""
        var name: String = ""
        var sessionId: String? = null
        var transcriptPath: String? = null
    }

    private enum class Phase { LOADED, RESTORING, LIVE }

    private var held = SavedChats()
    private var phase = Phase.LOADED
    private var frozen = false
    private var source: () -> List<ChatEntry>? = { null }

    override fun getState(): SavedChats {
        if (phase == Phase.LIVE && !frozen) source()?.let { held = saved(it) }
        return held
    }

    override fun loadState(state: SavedChats) {
        held = state
    }

    /** The chats the record holds now. */
    fun entries(): List<ChatEntry> = held.chats.mapNotNull(::entry)

    /** Reads the live chats from [chats], which returns null once the tool window is gone. */
    fun attach(chats: () -> List<ChatEntry>?) {
        source = chats
    }

    /**
     * The chats to reopen, on the first call only, when [enabled] and no chat is open yet ([hasChats]);
     * null otherwise. From then on the record follows the tool window: at once when there is nothing
     * to reopen, or after [restoreFinished].
     */
    fun takeRestorePlan(enabled: Boolean, hasChats: Boolean): List<ChatEntry>? {
        if (phase != Phase.LOADED) return null
        val plan = entries().takeIf { enabled && !hasChats && it.isNotEmpty() }
        phase = if (plan == null) Phase.LIVE else Phase.RESTORING
        return plan
    }

    /** Ends restoration: the record follows the tool window from now on. */
    fun restoreFinished() {
        if (phase == Phase.RESTORING) phase = Phase.LIVE
    }

    /** Keeps the chats open now as the record for good, as the project starts closing. */
    fun freeze() {
        if (frozen) return
        getState()
        frozen = true
        log.info("Chat record frozen with ${held.chats.size} chat(s)")
    }

    /** Freezes each project's record as it starts closing, while its chats are still open. */
    class CloseListener : ProjectCloseListener {
        override fun projectClosingBeforeSave(project: Project) {
            // Off the EDT the tool window cannot be read; its own disposal freezes the record then.
            if (ApplicationManager.getApplication().isDispatchThread) getInstance(project).freeze()
        }
    }

    companion object {
        private val log = Logger.getInstance(ChatTabsRecord::class.java)

        fun getInstance(project: Project): ChatTabsRecord = project.getService(ChatTabsRecord::class.java)

        /**
         * The chats among [contents], in order: each from its live [AgentSession] once attached
         * ([sessionOf]), else from what it recorded at creation ([CHAT_ENTRY_KEY]).
         */
        internal fun snapshot(contents: List<Content>, sessionOf: (String) -> AgentSession?): List<ChatEntry> =
            contents.mapNotNull { content ->
                val created = content.getUserData(CHAT_ENTRY_KEY) ?: return@mapNotNull null
                val session = content.getUserData(AgentToolWindowFactory.SESSION_ID_KEY)?.let(sessionOf)
                if (session == null) created else ChatEntry(created.cli, session.name, session.identity)
            }

        /**
         * The conversation to reopen [entry] on: its identity, when [sessionsEnabled] and its
         * transcript still [exists]; else null, for a new conversation.
         */
        internal fun resumeTarget(
            entry: ChatEntry,
            sessionsEnabled: Boolean,
            exists: (String) -> Boolean = { File(it).isFile },
        ): SessionIdentity? {
            val identity = entry.identity ?: return null
            val transcript = identity.transcriptPath ?: return null
            return identity.takeIf { sessionsEnabled && exists(transcript) }
        }

        private fun saved(entries: List<ChatEntry>) = SavedChats().apply {
            chats = entries.mapTo(ArrayList()) { entry ->
                SavedChat().apply {
                    cli = entry.cli.name
                    name = entry.name
                    sessionId = entry.identity?.sessionId
                    transcriptPath = entry.identity?.transcriptPath
                }
            }
        }

        private fun entry(saved: SavedChat): ChatEntry? {
            val cli = AgentCli.entries.firstOrNull { it.name == saved.cli } ?: return null
            val identity = saved.sessionId?.let { SessionIdentity(it, saved.transcriptPath) }
            return ChatEntry(cli, saved.name, identity)
        }
    }
}
