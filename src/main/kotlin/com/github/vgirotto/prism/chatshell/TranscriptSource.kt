package com.github.vgirotto.prism.chatshell

import com.intellij.openapi.Disposable
import java.io.File

/**
 * A bounded page of messages plus an opaque locator for the oldest loaded message
 * (design §6.3, R14). `beforeCursor` is fed back to [TranscriptSource.loadOlder] to page
 * backwards on scroll-up. Never carries the whole history.
 */
data class TranscriptPage(
    val messages: List<TranscriptMessage>,
    /** Opaque cursor identifying the oldest loaded message (null = start of history). */
    val beforeCursor: String?,
)

/**
 * The observable states of a transcript source (design §6.3). The source deals in
 * *messages*; the controller owns epoch/revision bookkeeping and payload building. [epoch]
 * is owned by the source (it knows about rotation/reset boundaries); it bumps on a fresh
 * [Ready] after a rotation so the controller can reset the view.
 */
sealed interface TranscriptState {
    object Loading : TranscriptState
    object NoTranscriptYet : TranscriptState
    /** Bounded window — most-recent page only, NOT the whole history. */
    data class Ready(val page: TranscriptPage, val epoch: Long = 0) : TranscriptState
    /** New or updated messages appended since the last state, within [epoch]. */
    data class Appended(val epoch: Long, val messages: List<TranscriptMessage>) : TranscriptState
    object Reconnecting : TranscriptState
    data class Error(val error: Throwable) : TranscriptState
}

/**
 * The agent-neutral seam (design §6.3, §11): owns where the transcript comes from and how
 * to tail it. Group 2 provides the interface + a one-shot windowed read; Group 5 adds live
 * byte-offset tailing, paging, and eviction behind the same contract.
 */
interface TranscriptSource {
    /** Subscribe to state updates; dispose to stop. */
    fun subscribe(onState: (TranscriptState) -> Unit): Disposable

    /** Page backwards from an opaque cursor. Returns null when there is nothing older. */
    fun loadOlder(beforeCursor: String?): TranscriptPage?
}

/**
 * A static, one-shot windowed source over a single JSONL file (Group 2). Parses the file
 * once, delivers the most-recent [windowSize] messages as [TranscriptState.Ready], and
 * pages older messages from the in-memory list. Live tailing/eviction is Group 5.
 */
class StaticTranscriptSource(
    private val file: File?,
    private val parser: TranscriptParser = TranscriptParser(),
    private val windowSize: Int = 50,
) : TranscriptSource {

    private var all: List<TranscriptMessage> = emptyList()

    override fun subscribe(onState: (TranscriptState) -> Unit): Disposable {
        onState(TranscriptState.Loading)
        val f = file
        if (f == null || !f.isFile) {
            onState(TranscriptState.NoTranscriptYet)
            return Disposable { }
        }
        try {
            all = parser.parseFile(f)
            if (all.isEmpty()) {
                onState(TranscriptState.NoTranscriptYet)
            } else {
                val start = maxOf(0, all.size - windowSize)
                val window = all.subList(start, all.size).toList()
                val cursor = if (start > 0) start.toString() else null
                onState(TranscriptState.Ready(TranscriptPage(window, cursor), epoch = 0))
            }
        } catch (e: Exception) {
            onState(TranscriptState.Error(e))
        }
        return Disposable { }
    }

    override fun loadOlder(beforeCursor: String?): TranscriptPage? {
        val end = beforeCursor?.toIntOrNull() ?: return null
        if (end <= 0) return null
        val start = maxOf(0, end - windowSize)
        val page = all.subList(start, end).toList()
        val cursor = if (start > 0) start.toString() else null
        return TranscriptPage(page, cursor)
    }
}
