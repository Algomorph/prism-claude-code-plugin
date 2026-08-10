package com.github.vgirotto.prism.toolwindow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies the keystroke sequences that drive Codex's two-step `/model`
 * picker. The picker is a stable, 1-based numbered list where pressing a
 * digit selects that row and advances/confirms; pressing Enter keeps the
 * pre-highlighted (current) row. See [CodexModelPicker] for the flow.
 *
 * Model selection is intentionally not covered by digit mapping: the model
 * list is account/CLI-version driven, so the Model button opens the native
 * picker ([CodexModelPicker.OPEN_MODEL]) instead of pressing a fixed digit.
 */
class CodexModelPickerTest {

    @Test
    fun `selectEffort keeps current model then picks the effort row`() {
        // Enter keeps the highlighted current model, advancing to the effort step;
        // digit 1 selects Low.
        assertEquals(listOf("/model", "\r", "\r", "1"), CodexModelPicker.selectEffort(1))
    }

    @Test
    fun `open helpers stop at the model and effort steps respectively`() {
        assertEquals(listOf("/model", "\r"), CodexModelPicker.OPEN_MODEL)
        assertEquals(listOf("/model", "\r", "\r"), CodexModelPicker.OPEN_EFFORT)
    }

    @Test
    fun `effort rows are numbered contiguously from one`() {
        assertEquals(listOf(1, 2, 3, 4), CodexModelPicker.EFFORTS.map { it.second })
    }
}
