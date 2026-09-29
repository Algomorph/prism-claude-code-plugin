package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * File-level behaviour of the name sources: picking the right session file out of a directory
 * that holds many, and degrading to no name rather than to the wrong one.
 */
class ClaudeChatNameSourceTest {

    @TempDir lateinit var dir: File

    private fun conversation(name: String, vararg lines: String): File =
        File(dir, "$name.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun source(launchId: String) = ClaudeChatNameSource({ dir }, launchId)

    @Test
    fun `the title comes from the conversation carrying our launch id`() {
        conversation(
            "other",
            """{"type":"user","session_id":"OTHER"}""",
            """{"type":"ai-title","aiTitle":"Someone else's chat","sessionId":"other"}""",
        )
        conversation(
            "ours",
            """{"type":"user","session_id":"OURS"}""",
            """{"type":"ai-title","aiTitle":"Our chat","sessionId":"ours"}""",
        )

        assertEquals("Our chat", source("OURS").poll()?.text)
    }

    @Test
    fun `a chat with no matching file gets no name rather than a neighbour's`() {
        // The safe failure: a Claude build without --session-id stamps no marker, so nothing
        // matches and the tab keeps its number.
        conversation(
            "other",
            """{"type":"user","session_id":"OTHER"}""",
            """{"type":"ai-title","aiTitle":"Someone else's chat","sessionId":"other"}""",
        )
        assertNull(source("OURS").poll())
    }

    @Test
    fun `the first typed message stands in until a title exists`() {
        conversation(
            "ours",
            """{"type":"user","message":{"content":"Establish the transcript bug"},"session_id":"OURS"}""",
        )

        assertEquals(
            ChatName("Establish the transcript bug", ChatName.Origin.FIRST_MESSAGE),
            source("OURS").poll(),
        )
    }

    @Test
    fun `a title supersedes the first typed message`() {
        conversation(
            "ours",
            """{"type":"user","message":{"content":"Establish the transcript bug"},"session_id":"OURS"}""",
            """{"type":"ai-title","aiTitle":"Diagnose missing transcript","sessionId":"ours"}""",
        )

        assertEquals(
            ChatName("Diagnose missing transcript", ChatName.Origin.AGENT_TITLE),
            source("OURS").poll(),
        )
    }

    @Test
    fun `a resumed chat follows its marker into the resumed conversation file`() {
        // After a /resume the chat appends to a different conversation file, still stamping its
        // own launch id — the file carrying that marker most recently is the one to read.
        conversation(
            "launch",
            """{"type":"user","session_id":"OURS"}""",
        ).setLastModified(1_000_000)
        conversation(
            "resumed",
            """{"type":"user","session_id":"OURS"}""",
            """{"type":"ai-title","aiTitle":"The older conversation","sessionId":"resumed"}""",
        ).setLastModified(2_000_000)

        assertEquals("The older conversation", source("OURS").poll()?.text)
    }

    @Test
    fun `a missing directory is not an error`() {
        assertNull(ClaudeChatNameSource({ File(dir, "nope") }, "OURS").poll())
        assertNull(ClaudeChatNameSource({ null }, "OURS").poll())
    }

    @Test
    fun `an empty conversation file is skipped`() {
        File(dir, "empty.jsonl").createNewFile()
        assertNull(source("OURS").poll())
    }
}

class CodexChatNameSourceTest {

    @TempDir lateinit var dir: File

    private fun rollout(vararg lines: String): File =
        File(dir, "rollout.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    @Test
    fun `the name is the first typed message in the resolved rollout`() {
        val file = rollout(
            """{"type":"session_meta","payload":{"id":"019f","cwd":"/w/x"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"Double-check the x264 result"}}""",
        )

        assertEquals(
            ChatName("Double-check the x264 result", ChatName.Origin.FIRST_MESSAGE),
            CodexChatNameSource { file }.poll(),
        )
    }

    @Test
    fun `an unresolved or empty rollout yields no name`() {
        assertNull(CodexChatNameSource { null }.poll())
        assertNull(CodexChatNameSource { rollout("""{"type":"session_meta","payload":{}}""") }.poll())
    }

    @Test
    fun `a sub-agent rollout yields no name rather than its system prompt`() {
        // cwd + recency can land on a rollout Codex wrote for a worker it spawned; that thread's
        // "first user message" is machine-composed, and an unnamed tab beats a misleading one.
        val file = rollout(
            """{"type":"session_meta","payload":{"id":"019f","thread_source":"subagent"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"The following is the Codex agent history whose request action you are assessing."}}""",
        )
        assertNull(CodexChatNameSource { file }.poll())
    }

    @Test
    fun `a huge leading record does not starve the scan`() {
        // Codex embeds its base instructions, and sub-agent prompts run to tens of kilobytes; the
        // head read is bounded by characters as well as lines.
        val file = rollout(
            """{"type":"session_meta","payload":{"instructions":"${"x".repeat(50_000)}"}}""",
            """{"type":"event_msg","payload":{"type":"user_message","message":"Found anyway"}}""",
        )
        assertEquals("Found anyway", CodexChatNameSource { file }.poll()?.text)
    }
}
