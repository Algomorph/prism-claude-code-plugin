package com.github.vgirotto.prism.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Covers the per-session PTY write queue.
 *
 * A Codex submit is delivered as two keystrokes with a ~700 ms gap between them so the CLI
 * does not mistake the burst for a paste. Before this queue existed each call spawned its
 * own thread, so a second click landed inside that gap: two Resume clicks produced
 * "/resumeresume" in the composer.
 */
class AgentSessionWriterTest {

    @Test
    fun `writer serializes overlapping submissions instead of interleaving them`() {
        val session = AgentSession(name = "test")
        val out = StringBuilder()
        val done = CountDownLatch(2)

        // Mimics a staged sequence: several chunks separated by a delay.
        fun stage(tag: String) = session.writer.execute {
            try {
                for (i in 1..3) {
                    synchronized(out) { out.append(tag).append(i) }
                    Thread.sleep(10)
                }
            } finally {
                done.countDown()
            }
        }

        stage("a")
        stage("b")

        assertTrue(done.await(5, TimeUnit.SECONDS), "writes did not finish in time")
        // With a raw Thread per call this read something like "a1b1a2b2a3b3".
        assertEquals("a1a2a3b1b2b3", out.toString())

        session.dispose()
    }

    @Test
    fun `a keystroke typed during a sequence lands after it, never inside it`() {
        val session = AgentSession(name = "test")
        val out = StringBuilder()
        val bodyWritten = CountDownLatch(1)
        val done = CountDownLatch(2)

        // The staged submit: body, a gap, then the Enter that commits it.
        session.writer.execute {
            synchronized(out) { out.append("/resume") }
            bodyWritten.countDown()
            Thread.sleep(50)
            synchronized(out) { out.append("\r") }
            done.countDown()
        }

        // The user types into the terminal inside that gap. Routing the connector's
        // write through the same queue is what keeps it out of the middle.
        assertTrue(bodyWritten.await(5, TimeUnit.SECONDS))
        session.writer.execute {
            synchronized(out) { out.append("x") }
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "writes did not finish in time")
        assertEquals("/resume\rx", out.toString())

        session.dispose()
    }

    @Test
    fun `sequenceInFlight stays true until every queued sequence finishes`() {
        val session = AgentSession(name = "test")
        assertFalse(session.sequenceInFlight)

        session.beginSequence()
        session.beginSequence()
        assertTrue(session.sequenceInFlight)

        session.endSequence()
        // A plain boolean would have cleared here, re-enabling the toolbar while the
        // second sequence was still sending keystrokes.
        assertTrue(session.sequenceInFlight)

        session.endSequence()
        assertFalse(session.sequenceInFlight)

        session.dispose()
    }

    @Test
    fun `endSequence never drives the count negative`() {
        val session = AgentSession(name = "test")

        // A rejected submission still runs its onDone, so end can outnumber begin.
        session.endSequence()
        session.endSequence()
        assertFalse(session.sequenceInFlight)

        session.beginSequence()
        assertTrue(session.sequenceInFlight, "a stale negative count would swallow this")

        session.dispose()
    }

    @Test
    fun `dispose shuts the writer down so queued keystrokes stop`() {
        val session = AgentSession(name = "test")
        session.dispose()

        assertTrue(session.writer.isShutdown)
        assertThrows(RejectedExecutionException::class.java) { session.writer.execute {} }
    }

    @Test
    fun `dispose clears an in-flight sequence`() {
        val session = AgentSession(name = "test")
        session.beginSequence()
        assertTrue(session.sequenceInFlight)

        session.dispose()

        assertFalse(session.sequenceInFlight)
    }
}
