package com.github.vgirotto.prism.toolwindow

import com.github.vgirotto.prism.model.AgentCli
import com.github.vgirotto.prism.model.AgentSession
import com.github.vgirotto.prism.services.session.SessionIdentity
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ChatTabsRecordTest {

    private val claude = ChatEntry(AgentCli.CLAUDE, "Fix login", SessionIdentity("c1", "/c1.jsonl"))
    private val codex = ChatEntry(AgentCli.CODEX, "Chat #2")
    private val sessions = mutableListOf<AgentSession>()

    @AfterEach
    fun disposeSessions() = sessions.forEach { it.dispose() }

    private fun loaded(vararg entries: ChatEntry): ChatTabsRecord {
        val source = ChatTabsRecord()
        source.attach { entries.toList() }
        source.takeRestorePlan(enabled = false, hasChats = false)
        return ChatTabsRecord().apply { loadState(source.state) }
    }

    @Test
    fun `the record survives serialization`() {
        val record = loaded(claude, codex)

        val xml = XmlSerializer.serialize(record.state)
        val reloaded = ChatTabsRecord().apply { loadState(XmlSerializer.deserialize(xml, ChatTabsRecord.SavedChats::class.java)) }

        assertEquals(listOf(claude, codex), reloaded.entries())
    }

    @Test
    fun `a chat on an agent this Prism does not know is dropped on load`() {
        val state = ChatTabsRecord.SavedChats().apply {
            chats = arrayListOf(
                ChatTabsRecord.SavedChat().apply { cli = "GEMINI"; name = "x" },
                ChatTabsRecord.SavedChat().apply { cli = "CODEX"; name = "Chat #2" },
            )
        }
        assertEquals(listOf(codex), ChatTabsRecord().apply { loadState(state) }.entries())
    }

    @Test
    fun `before restoration is decided the loaded record is kept`() {
        val record = loaded(claude, codex)
        record.attach { emptyList() }

        assertEquals(listOf(claude, codex), entriesSaved(record))
    }

    @Test
    fun `while restoring the loaded record is kept`() {
        val record = loaded(claude, codex)
        record.attach { listOf(codex) }

        assertEquals(listOf(claude, codex), record.takeRestorePlan(enabled = true, hasChats = false))
        assertEquals(listOf(claude, codex), entriesSaved(record))

        record.restoreFinished()
        assertEquals(listOf(codex), entriesSaved(record))
    }

    @Test
    fun `the plan is given once, and later calls replay nothing`() {
        val record = loaded(claude)

        assertEquals(listOf(claude), record.takeRestorePlan(enabled = true, hasChats = false))
        assertNull(record.takeRestorePlan(enabled = true, hasChats = false))
    }

    @Test
    fun `no plan when the setting is off, chats are already open, or nothing was recorded, and the record goes live`() {
        for ((record, hasChats, enabled) in listOf(
            Triple(loaded(claude), false, false),
            Triple(loaded(claude), true, true),
            Triple(loaded(), false, true),
        )) {
            assertNull(record.takeRestorePlan(enabled, hasChats))
            record.attach { listOf(codex) }
            assertEquals(listOf(codex), entriesSaved(record))
        }
    }

    @Test
    fun `a frozen record keeps the chats open when it froze`() {
        val record = loaded()
        var open = listOf(claude, codex)
        record.attach { open }
        record.takeRestorePlan(enabled = true, hasChats = false)

        record.freeze()
        open = emptyList()

        assertEquals(listOf(claude, codex), entriesSaved(record))
    }

    @Test
    fun `a tool window that is gone leaves the record as it was`() {
        val record = loaded(claude)
        record.takeRestorePlan(enabled = false, hasChats = false)
        record.attach { null }

        assertEquals(listOf(claude), entriesSaved(record))
    }

    @Test
    fun `the snapshot keeps chat order, skips other tabs, and reads a starting chat from its creation entry`() {
        val attached = AgentSession(name = "Renamed", cli = AgentCli.CLAUDE).also(sessions::add)
        attached.identity = SessionIdentity("c9", "/c9.jsonl")
        val contents = listOf(
            content(CHAT_ENTRY_KEY to ChatEntry(AgentCli.CLAUDE, "Chat #1"), AgentToolWindowFactory.SESSION_ID_KEY to attached.id),
            content(AgentToolWindowFactory.HISTORY_TAB_KEY to true),
            content(CHAT_ENTRY_KEY to codex),
        )

        val snapshot = ChatTabsRecord.snapshot(contents) { id -> attached.takeIf { it.id == id } }

        assertEquals(listOf(ChatEntry(AgentCli.CLAUDE, "Renamed", attached.identity), codex), snapshot)
    }

    @Test
    fun `a chat resumes only with sessions on, a recorded conversation and its transcript`() {
        val exists = { path: String -> path == "/c1.jsonl" }

        assertEquals(claude.identity, ChatTabsRecord.resumeTarget(claude, sessionsEnabled = true, exists))
        assertNull(ChatTabsRecord.resumeTarget(claude, sessionsEnabled = false, exists))
        assertNull(ChatTabsRecord.resumeTarget(claude, sessionsEnabled = true) { false })
        assertNull(ChatTabsRecord.resumeTarget(codex, sessionsEnabled = true) { true })
        val noTranscript = claude.copy(identity = SessionIdentity("c1", null))
        assertNull(ChatTabsRecord.resumeTarget(noTranscript, sessionsEnabled = true) { true })
    }

    private fun entriesSaved(record: ChatTabsRecord): List<ChatEntry> =
        ChatTabsRecord().apply { loadState(record.state) }.entries()

    private fun content(vararg data: Pair<Key<*>, Any>): Content {
        val userData = data.toMap()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Content::class.java)) { proxy, method, args ->
            when (method.name) {
                "getUserData" -> userData[args[0]]
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as Content
    }
}
