package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CodexHistoryReaderTest {

    private fun writeRollout(root: Path, projectPath: String, sessionId: String): Path {
        val dir = root.resolve("sessions/2026/05/21")
        Files.createDirectories(dir)
        val file = dir.resolve("rollout-2026-05-21T12-00-00-$sessionId.jsonl")
        val lines = listOf(
            """{"timestamp":"2026-05-21T12:00:00.000Z","type":"session_meta","payload":{"id":"$sessionId","cwd":"$projectPath","timestamp":"2026-05-21T12:00:00.000Z"}}""",
            """{"timestamp":"2026-05-21T12:00:01.000Z","type":"turn_context","payload":{"model":"gpt-5-codex","effort":"high","cwd":"$projectPath"}}""",
            """{"timestamp":"2026-05-21T12:00:02.000Z","type":"event_msg","payload":{"type":"user_message","message":"Hello, agent."}}""",
            """{"timestamp":"2026-05-21T12:00:03.000Z","type":"event_msg","payload":{"type":"agent_message","message":"Hi! How can I help?"}}""",
            """{"timestamp":"2026-05-21T12:00:04.000Z","type":"event_msg","payload":{"type":"user_message","message":"Refactor the parser."}}""",
        )
        Files.writeString(file, lines.joinToString("\n") + "\n")
        return file
    }

    @Test
    fun `listConversations parses summary fields from a rollout file`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        writeRollout(tmp, project, "session-aaa")

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        val summaries = reader.listConversations()
        assertEquals(1, summaries.size)

        val s = summaries[0]
        assertEquals("session-aaa", s.sessionId)
        assertEquals(project, s.cwd)
        assertEquals("gpt-5-codex", s.model)
        assertEquals(3, s.messageCount) // 2 user + 1 agent
        assertEquals("Hello, agent.", s.firstUserMessage)
    }

    @Test
    fun `listConversations filters out sessions from other project directories`(@TempDir tmp: Path) {
        writeRollout(tmp, "/tmp/my-project", "session-mine")
        writeRollout(tmp, "/tmp/other-project", "session-other")

        val reader = CodexHistoryReader("/tmp/my-project", tmp.resolve("sessions").toFile())
        val summaries = reader.listConversations()
        assertEquals(1, summaries.size)
        assertEquals("session-mine", summaries[0].sessionId)
    }

    @Test
    fun `loadConversation returns user and agent messages in order`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        writeRollout(tmp, project, "session-bbb")

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        val messages = reader.loadConversation("session-bbb")
        assertEquals(3, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello, agent.", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("Hi! How can I help?", messages[1].content)
        assertEquals("user", messages[2].role)
        assertEquals("Refactor the parser.", messages[2].content)
    }

    @Test
    fun `searchConversations matches text inside session files`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        writeRollout(tmp, project, "session-ccc")

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertEquals(1, reader.searchConversations("refactor").size)
        assertEquals(0, reader.searchConversations("not-in-file").size)
    }

    @Test
    fun `returns empty list when sessions root is missing`(@TempDir tmp: Path) {
        val reader = CodexHistoryReader("/tmp/example-project", tmp.resolve("missing").toFile())
        assertTrue(reader.listConversations().isEmpty())
        assertTrue(reader.loadConversation("any").isEmpty())
        assertTrue(reader.searchConversations("anything").isEmpty())
    }
}
