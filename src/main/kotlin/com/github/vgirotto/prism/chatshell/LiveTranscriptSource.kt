package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Live incremental tailing source (design §6.7, §8). Polls the resolved transcript file on
 * a background executor (a per-tab poller is more portable across OSes than a raw
 * `WatchService` for files outside the project tree — review #11), reads only appended
 * bytes via [TailReader], parses new lines off the EDT, and emits:
 *   - [TranscriptState.NoTranscriptYet] while the file (or its project dir) is absent —
 *     absence is a normal state, not an error, covering the fresh-file AND fresh-project
 *     startup race (§6.7, R13);
 *   - a bounded [TranscriptState.Ready] window on first content;
 *   - [TranscriptState.Appended] with new/updated messages thereafter;
 *   - a fresh [TranscriptState.Ready] with a bumped epoch on truncation/rotation
 *     (e.g. a `/clear` recreating the file).
 *
 * The controller marshals emissions to the EDT and owns payload building + revisions.
 */
class LiveTranscriptSource(
    private val file: File,
    private val parser: TranscriptParser = TranscriptParser(),
    private val windowSize: Int = 50,
    private val pollIntervalMs: Long = 250,
) : TranscriptSource {

    private val reader = TailReader(file)
    private val messages = ArrayList<TranscriptMessage>()
    private val indexById = HashMap<String, Int>()

    @Volatile private var onState: ((TranscriptState) -> Unit)? = null
    @Volatile private var epoch = 0L
    @Volatile private var delivered = false
    @Volatile private var disposed = false
    private var future: ScheduledFuture<*>? = null

    override fun subscribe(onState: (TranscriptState) -> Unit): Disposable {
        this.onState = onState
        emit(TranscriptState.Loading)
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    emit(TranscriptState.Error(e))
                }
            },
            0, pollIntervalMs, TimeUnit.MILLISECONDS
        )
        return Disposable {
            disposed = true
            future?.cancel(false)
            this.onState = null
        }
    }

    /** Test seam: install the listener without starting the background poller. */
    @org.jetbrains.annotations.TestOnly
    internal fun installListenerForTest(cb: (TranscriptState) -> Unit) {
        this.onState = cb
    }

    /** One read+parse+emit cycle. Synchronous and synchronized so it is unit-testable. */
    @Synchronized
    fun pollOnce() {
        if (disposed) return
        if (!file.isFile) {
            if (!delivered) emit(TranscriptState.NoTranscriptYet)
            return
        }
        val res = reader.poll()
        if (res.rotated) {
            epoch++
            messages.clear(); indexById.clear(); delivered = false
        }
        if (res.completeLines.isEmpty()) {
            if (!delivered && file.length() == 0L) emit(TranscriptState.NoTranscriptYet)
            return
        }
        val parsed = parser.parseLines(res.completeLines.asSequence())
        val changed = ArrayList<TranscriptMessage>()
        for (m in parsed) {
            val idx = indexById[m.id]
            if (idx != null) messages[idx] = m
            else { indexById[m.id] = messages.size; messages.add(m) }
            changed.add(m)
        }
        if (!delivered) {
            delivered = true
            val start = maxOf(0, messages.size - windowSize)
            val window = messages.subList(start, messages.size).toList()
            val cursor = if (start > 0) start.toString() else null
            emit(TranscriptState.Ready(TranscriptPage(window, cursor), epoch))
        } else if (changed.isNotEmpty()) {
            emit(TranscriptState.Appended(epoch, changed))
        }
    }

    @Synchronized
    override fun loadOlder(beforeCursor: String?): TranscriptPage? {
        val end = beforeCursor?.toIntOrNull() ?: return null
        if (end <= 0 || end > messages.size) return null
        val start = maxOf(0, end - windowSize)
        val page = messages.subList(start, end).toList()
        val cursor = if (start > 0) start.toString() else null
        return TranscriptPage(page, cursor)
    }

    private fun emit(state: TranscriptState) {
        if (!disposed) onState?.invoke(state)
    }
}
