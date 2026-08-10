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

    @Test
    fun `another project's session is excluded on its header alone`(@TempDir tmp: Path) {
        val project = "/tmp/my-project"
        writeRollout(tmp, project, "session-mine")

        // Body mentions this project, header says otherwise. Only the header decides —
        // which is what lets the listing skip the body of every foreign file.
        val foreign = writeRollout(tmp, "/tmp/other-project", "session-other")
        Files.writeString(foreign, Files.readString(foreign).replace("Refactor the parser.", project))

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertEquals(listOf("session-mine"), reader.listConversations().map { it.sessionId })
    }

    @Test
    fun `a session file rewritten with a new cwd is re-read`(@TempDir tmp: Path) {
        val project = "/tmp/my-project"
        val file = writeRollout(tmp, "/tmp/other-project", "session-moved")
        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertTrue(reader.listConversations().isEmpty())

        // A remembered header must not outlive the file it came from.
        Files.writeString(file, Files.readString(file).replace("/tmp/other-project", project))
        file.toFile().setLastModified(file.toFile().lastModified() + 2_000)

        assertEquals(listOf("session-moved"), reader.listConversations().map { it.sessionId })
    }

    @Test
    fun `files nested deeper than the date tree are ignored`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        writeRollout(tmp, project, "session-ok")

        // sessions/YYYY/MM/DD/file is depth 4; anything below is not Codex's layout, and
        // walking it is how a symlink cycle would trap the reader.
        val deep = tmp.resolve("sessions/2026/05/21/nested/deeper")
        Files.createDirectories(deep)
        Files.copy(
            tmp.resolve("sessions/2026/05/21/rollout-2026-05-21T12-00-00-session-ok.jsonl"),
            deep.resolve("rollout-2026-05-21T13-00-00-session-deep.jsonl"),
        )

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertEquals(listOf("session-ok"), reader.listConversations().map { it.sessionId })
    }

    @Test
    fun `limit returns the most recent sessions by filename`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        val dir = tmp.resolve("sessions/2026/05/21")
        Files.createDirectories(dir)
        for (hour in listOf("10", "11", "12")) {
            val src = writeRollout(tmp, project, "session-$hour")
            Files.move(src, dir.resolve("rollout-2026-05-21T$hour-00-00-session-$hour.jsonl"))
        }

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertEquals(3, reader.listConversations().size)
        assertEquals(listOf("session-12", "session-11"), reader.listConversations(2).map { it.sessionId })
        assertEquals(listOf("session-12"), reader.listConversations(1).map { it.sessionId })
    }

    @Test
    fun `a file without a session_meta header is skipped without failing the listing`(@TempDir tmp: Path) {
        val project = "/tmp/example-project"
        writeRollout(tmp, project, "session-ok")

        val dir = tmp.resolve("sessions/2026/05/21")
        Files.writeString(
            dir.resolve("rollout-2026-05-21T09-00-00-headerless.jsonl"),
            "not json at all\n{\"type\":\"event_msg\"}\n",
        )

        val reader = CodexHistoryReader(project, tmp.resolve("sessions").toFile())
        assertEquals(listOf("session-ok"), reader.listConversations().map { it.sessionId })
        assertTrue(reader.searchConversations("Refactor").isNotEmpty())
    }
}
