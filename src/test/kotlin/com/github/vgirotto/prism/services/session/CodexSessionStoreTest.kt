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

    private fun reverted(day: String, time: String, id: String, rolloutId: String): File =
        home.resolve("sessions/$day/rollout-${day.replace('/', '-')}T$time-${id}_$rolloutId.jsonl").toFile()
            .apply { parentFile.mkdirs(); writeText("{}\n") }

    @Test
    fun `rollout names in both formats are read, with the thread id first`() {
        val rolloutId = "01a0ff00-0000-7000-8000-000000000009"
        assertEquals(
            RolloutName("2026-09-29T11-13-18", id, id),
            RolloutName.parse("rollout-2026-09-29T11-13-18-$id.jsonl"),
        )
        assertEquals(
            RolloutName("2026-10-02T08-00-00", id, rolloutId),
            RolloutName.parse("rollout-2026-10-02T08-00-00-${id}_$rolloutId.jsonl"),
        )
        for (bad in listOf(
            "rollout-2026-09-29T11-13-18-$id.jsonl.zst", "rollout-$id.jsonl", "notes-2026-09-29T11-13-18-$id.jsonl",
            "rollout-2026-09-29T11-13-18-${id}_.jsonl", "rollout-2026-09-29T11-13-18-${id}_short.jsonl",
        )) {
            assertNull(RolloutName.parse(bad), bad)
        }
    }

    @Test
    fun `a reverted thread resolves to its newest rollout, filed under the day of the revert`() {
        rollout("2026/09/29", id)
        val revert = reverted("2026/10/02", "08-00-00", id, "01a0ff00-0000-7000-8000-000000000009")
        assertEquals(SessionIdentity(id, revert.path), store.complete(prefix))
        assertEquals(revert.toPath(), store.rolloutFor(id))
    }

    @Test
    fun `rollouts of the same second are ordered by rollout id`() {
        reverted("2026/09/30", "08-00-00", id, "01a0ff00-0000-7000-8000-000000000001")
        val later = reverted("2026/09/30", "08-00-00", id, "01a0ff00-0000-7000-8000-000000000002")
        assertEquals(later.toPath(), store.rolloutFor(id))
    }

    @Test
    fun `another thread's rollout id is not taken for a thread id`() {
        val file = rollout("2026/09/29", id)
        // Another thread, reverted: its rollout id, last in the name, starts like this thread's id.
        reverted("2026/09/30", "08-00-00", "01a0eeee-0000-7000-8000-000000000000", id)
        assertEquals(SessionIdentity(id, file.path), store.complete(prefix))
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
    fun `only the most recently named threads are kept`() {
        val first = "01a0edbb-4501-7591-82b7-000000000000"
        val renamed = "01a0edbb-4501-7591-82b7-000000000001"
        index.writeText(record(first, "First") + record(renamed, "Before"))
        val others = (2..CodexSessionStore.MAX_NAMES).joinToString("") { record("other-$it", "Other $it") }
        index.appendText(others + record(renamed, "After"))
        assertNull(store.threadName(first))
        assertEquals("After", store.threadName(renamed))
        assertEquals("Other 2", store.threadName("other-2"))
    }

    @Test
    fun `an index line too long to be a record is skipped`() {
        index.writeText(record("other", "x".repeat(AppendedLines.DEFAULT_MAX_LINE_BYTES)) + record(id, "Fine"))
        assertNull(store.threadName("other"))
        assertEquals("Fine", store.threadName(id))
    }

    @Test
    fun `an index replaced at the same size forgets the names it no longer holds`() {
        index.writeText(record(id, "Name A") + record("other", "Other"))
        assertEquals("Name A", store.threadName(id))
        index.writeText(record(id, "Name B") + record("thing", "Thing"))
        assertEquals("Name B", store.threadName(id))
        assertNull(store.threadName("other"))
    }

    @Test
    fun `an index replaced by a larger one is read again from the start`() {
        index.writeText(record(id, "Before"))
        assertEquals("Before", store.threadName(id))
        index.writeText(record("other", "Other, now first") + record(id, "After"))
        assertEquals("After", store.threadName(id))
        assertEquals("Other, now first", store.threadName("other"))
    }

    @Test
    fun `an index rewritten in place under an unchanged last record is read again from the start`() {
        val last = record("unrelated-thread", "A final record longer than sixty-four bytes in all")
        index.writeText(record(id, "Before") + last)
        assertEquals("Before", store.threadName(id))
        index.writeText(record(id, "After!") + last)
        assertEquals("After!", store.threadName(id))
        index.writeText(record(id, "Later, longer") + last + record("other", "Other"))
        assertEquals("Later, longer", store.threadName(id))
        index.appendText(record(id, "New entry"))
        assertEquals("New entry", store.threadName(id))
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
