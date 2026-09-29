package com.github.vgirotto.prism.services.session

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ClaudeSessionHookTest {

    @TempDir lateinit var dir: Path

    private fun event(id: String, source: String, transcript: String = "/p/$id.jsonl") =
        """{"session_id":"$id","transcript_path":"$transcript","cwd":"/p","source":"$source","hook_event_name":"SessionStart"}"""

    @Test
    fun `the settings carry one SessionStart command hook and turn the tab status off`() {
        val json = JsonParser.parseString(ClaudeSessionHook.settingsJson(dir.resolve("events.jsonl"))).asJsonObject
        val hooks = json.getAsJsonObject("hooks").getAsJsonArray("SessionStart")
        val hook = hooks[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals("command", hook.get("type").asString)
        assertEquals(ClaudeSessionHook.command(dir.resolve("events.jsonl")), hook.get("command").asString)
        assertFalse(json.get("showStatusInTerminalTab").asBoolean)
    }

    @Test
    fun `a path with a space and quotes survives JSON and the shell`() {
        val tricky = dir.resolve("it's a \"dir\"").also { it.toFile().mkdirs() }.resolve("events.jsonl")
        val json = JsonParser.parseString(ClaudeSessionHook.settingsJson(tricky)).asJsonObject
        val command = json.getAsJsonObject("hooks").getAsJsonArray("SessionStart")[0].asJsonObject
            .getAsJsonArray("hooks")[0].asJsonObject.get("command").asString

        // Run the command the way Claude does, with the event on stdin.
        val process = ProcessBuilder("sh", "-c", command).start()
        process.outputStream.use { it.write(event("s1", "startup").toByteArray()) }
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(SessionIdentity("s1", "/p/s1.jsonl"), ClaudeHookEventReader(tricky).poll())
    }

    @Test
    fun `the newest event is the session shown, and each is read once`() {
        val file = dir.resolve("events.jsonl").toFile()
        val reader = ClaudeHookEventReader(file.toPath())
        assertNull(reader.poll()) // Claude has not started yet.

        file.writeText(event("launch", "startup") + "\n")
        assertEquals("launch", reader.poll()?.sessionId)
        assertNull(reader.poll())

        file.appendText(event("picked", "resume") + "\n\n" + event("fresh", "clear") + "\n")
        assertEquals(SessionIdentity("fresh", "/p/fresh.jsonl"), reader.poll())
    }

    @Test
    fun `a partial last line waits until it is complete`() {
        val file = dir.resolve("events.jsonl").toFile()
        val reader = ClaudeHookEventReader(file.toPath())
        val line = event("picked", "resume")
        file.writeText(line.take(30))
        assertNull(reader.poll())
        file.appendText(line.drop(30) + "\n")
        assertEquals("picked", reader.poll()?.sessionId)
    }

    @Test
    fun `malformed lines and events without an id are skipped`() {
        val file = dir.resolve("events.jsonl").toFile()
        file.writeText(event("good", "startup") + "\nnot json\n{\"source\":\"clear\"}\n")
        assertEquals("good", ClaudeHookEventReader(file.toPath()).poll()?.sessionId)
    }

    @Test
    fun `a missing transcript path leaves it unknown`() {
        val file = File(dir.toFile(), "events.jsonl")
        file.writeText("""{"session_id":"s","source":"startup"}""" + "\n")
        assertEquals(SessionIdentity("s", null), ClaudeHookEventReader(file.toPath()).poll())
    }
}
