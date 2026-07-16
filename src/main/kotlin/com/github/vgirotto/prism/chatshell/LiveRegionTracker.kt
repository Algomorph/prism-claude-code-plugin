package com.github.vgirotto.prism.chatshell

import kotlin.math.abs

/**
 * Estimates the height (in rows) of the CLI's live/interactive region so the divider can
 * optionally auto-follow it (design §6.6, §8.1). This is a **later-phase enhancement that
 * is never load-bearing** — it degrades to the manual draggable divider (Group 4), and the
 * min-row guard + mandatory expand (Group 4) always keep interactive choices reachable.
 *
 * Only the pure row-classification + settle logic lives here (unit-tested against
 * hand-authored fixtures). Reading JediTerm's live buffer and driving the strip's size
 * (a real SIGWINCH reflow) is a thin adapter verified in HITL; the recorded grid-diff
 * fixtures from Group 0/HITL will tune the constants.
 */
interface LiveRegionTracker {
    /** Estimate how many trailing rows form the interactive region for this frame. */
    fun liveRegionRows(rows: List<String>): Int
}

/**
 * Claude/Ink heuristic: Ink writes committed output to scrollback via `<Static>` and
 * redraws the live region (input box, spinner, approval/menu) at the bottom. From a single
 * frame we approximate the live region as the trailing contiguous block of non-blank rows
 * up to the blank-line gap that separates it from committed scrollback, clamped to
 * [minRows].
 */
class ClaudeLiveRegionTracker(private val minRows: Int = 3) : LiveRegionTracker {

    override fun liveRegionRows(rows: List<String>): Int {
        if (rows.isEmpty()) return minRows
        // Drop trailing fully-blank rows (terminal padding).
        var end = rows.size
        while (end > 0 && rows[end - 1].isBlank()) end--
        if (end == 0) return minRows
        // Walk up until a blank separator row (gap to scrollback) or the top.
        var start = end
        while (start > 0 && rows[start - 1].isNotBlank()) start--
        val count = end - start
        return maxOf(count, minRows)
    }
}

/**
 * Suppresses per-frame single-row spinner churn: the reported value only changes when the
 * new estimate differs from the current one by more than [threshold] rows, so the divider
 * follows *shape* changes (prompt ↔ menu) rather than animation (design §6.6).
 */
class LiveRegionSettleFilter(private val threshold: Int = 1) {
    private var current: Int? = null

    fun offer(value: Int): Int {
        val c = current
        if (c == null || abs(value - c) > threshold) {
            current = value
        }
        return current!!
    }

    fun reset() { current = null }
}
