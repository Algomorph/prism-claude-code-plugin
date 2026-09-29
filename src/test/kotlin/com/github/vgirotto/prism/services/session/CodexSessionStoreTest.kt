package com.github.vgirotto.prism.services.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CodexSessionStoreTest {

    @TempDir lateinit var home: Path

    private val store by lazy { CodexSessionStore(home) }
    private val index get() = home.resolve(CodexSessionStore.INDEX_FILE).toFile()

    // 0x01a0edbb4501 ms = 2026-09-29T15:14:28Z (a UUIDv7 creation time).
    private val id = "01a0edbb-4501-7591-82b7-36c4c0a1b2c3"
    private val prefix = IdHint("01a0edbb-4501-7591-82b7-36c4c", isPrefix = true)

    private fun record(id: String, name: String) =
        """{"id":"$id","thread_name":"$name","updated_at":"2026-09-29T15:14:00Z"}""" + "\n"

    private fun rollout(day: String, id: String): File =
        home.resolve("sessions/$day/rollout-${day.replace('/', '-')}T11-13-18-$id.jsonl").toFile()
            .apply { parentFile.mkdirs(); writeText("{}\n") }

    @Test
    fun `a UUIDv7 prefix encodes its creation time`() {
        assertEquals(0x01a0edbb4501L, CodexSessionStore.uuidV7Millis(prefix.value))
        assertNull(CodexSessionStore.uuidV7Millis("01a0edbb-45"))
    }

    @Test
    fun `a unique prefix completes to the thread and its rollout`() {
        val file = rollout("2026/09/29", id)
        rollout("2026/09/29", "01a0edbb-4501-7591-82b7-99999999999a")
        assertEquals(SessionIdentity(id, file.path), store.complete(prefix))
    }

    @Test
    fun `a prefix found only in the index completes without a rollout`() {
        index.writeText(record(id, "Reply ok"))
        assertEquals(SessionIdentity(id, null), store.complete(prefix))
    }

    @Test
    fun `an ambiguous prefix completes to nothing`() {
        rollout("2026/09/29", id)
        index.writeText(record("01a0edbb-4501-7591-82b7-36c4cffffff0", "Other"))
        assertNull(store.complete(prefix))
    }

    @Test
    fun `an unknown prefix completes to nothing until the thread appears`() {
        assertNull(store.complete(prefix))
        index.writeText(record(id, "Reply ok"))
        assertEquals(id, store.complete(prefix)?.sessionId)
    }

    @Test
    fun `rollouts filed under the neighbouring local day are found`() {
        val before = rollout("2026/09/28", id)
        assertEquals(before.path, store.complete(prefix)?.transcriptPath)
        before.delete()
        val after = rollout("2026/09/30", id)
        assertEquals(after.path, store.complete(prefix)?.transcriptPath)
    }

    @Test
    fun `a rollout two days away is not searched`() {
        rollout("2026/09/27", id)
        assertNull(store.complete(prefix))
    }

    @Test
    fun `a whole id is taken as is`() {
        assertEquals(SessionIdentity(id, null), store.complete(IdHint(id, isPrefix = false)))
    }

    @Test
    fun `the last index record for a thread wins`() {
        index.writeText(record(id, "First") + record("other", "Other") + record(id, "Renamed"))
        assertEquals("Renamed", store.threadName(id))
        index.appendText(record(id, "Again"))
        assertEquals("Again", store.threadName(id))
    }

    @Test
    fun `a partial last line waits until it is complete`() {
        index.writeText(record(id, "First"))
        assertEquals("First", store.threadName(id))
        val next = record(id, "Second")
        index.appendText(next.take(20))
        assertEquals("First", store.threadName(id))
        index.appendText(next.drop(20))
        assertEquals("Second", store.threadName(id))
    }

    @Test
    fun `a replaced index is read again from the start`() {
        index.writeText(record(id, "A long first name") + record("other", "Other"))
        assertEquals("A long first name", store.threadName(id))
        index.writeText(record(id, "B"))
        assertEquals("B", store.threadName(id))
        assertNull(store.threadName("other"))
    }

    @Test
    fun `malformed lines and a missing index are ignored`() {
        assertNull(store.threadName(id))
        index.writeText("not json\n" + record(id, "Fine"))
        assertEquals("Fine", store.threadName(id))
    }

    @Test
    fun `the default home is under the user's home`() {
        assertEquals(Path.of(System.getProperty("user.home"), ".codex"), CodexSessionStore.userDefaultHome())
    }
}
