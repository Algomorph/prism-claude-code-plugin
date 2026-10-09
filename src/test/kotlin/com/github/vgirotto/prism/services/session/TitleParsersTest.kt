package com.github.vgirotto.prism.services.session

import com.github.vgirotto.prism.services.session.TitleReading.Named
import com.github.vgirotto.prism.services.session.TitleReading.Unnamed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Title strings as recorded from Claude Code 2.1.284 and codex-cli 0.159.0 in a pty. */
class TitleParsersTest {

    @Nested
    inner class Claude {

        private fun parse(title: String) = ClaudeTitleParser.parse(title)

        @Test
        fun `every status glyph is accepted`() {
            for (glyph in listOf("✳", "◐", "◑")) {
                assertEquals(Named("Fix the parser", false, null), parse("$glyph Fix the parser"))
            }
        }

        @Test
        fun `the untitled placeholder reads as unnamed`() {
            assertEquals(Unnamed(null), parse("✳ Claude Code"))
            assertEquals(Unnamed(null), parse("◐ Claude Code"))
        }

        @Test
        fun `shell and unknown titles produce no reading`() {
            assertNull(parse("greg@Sage: ~/Garage/prism"))
            assertNull(parse("Claude Code"))
            assertNull(parse("* Fix the parser"))
            assertNull(parse("✳Fix the parser"))
            assertNull(parse("✳ "))
            assertNull(parse(""))
        }

        @Test
        fun `a user title is kept as written, including a leading emoji or angle bracket`() {
            assertEquals(Named("🚀 Launch prep", false, null), parse("✳ 🚀 Launch prep"))
            assertEquals(Named("<draft> notes", false, null), parse("◑ <draft> notes"))
            assertEquals(Named("a | b", false, null), parse("✳ a | b"))
        }

        @Test
        fun `a long title is never marked as cut off`() {
            val long = "x".repeat(89)
            assertEquals(Named(long, false, null), parse("✳ $long"))
        }
    }

    @Nested
    inner class Codex {

        private val idItem = "01a0edbb-4501-7591-82b7-36c4c..."
        private val hint = IdHint("01a0edbb-4501-7591-82b7-36c4c", isPrefix = true)

        private fun parse(title: String) = CodexTitleParser.parse(title)

        @Test
        fun `an id alone reads as unnamed with a prefix hint`() {
            assertEquals(Unnamed(hint), parse(idItem))
        }

        @Test
        fun `a named thread carries its name and id prefix`() {
            assertEquals(Named("Reply ok", false, hint), parse("$idItem | Reply ok"))
            assertEquals(Named("short-name", false, hint), parse("$idItem | short-name"))
        }

        @Test
        fun `spinner frames on both items are removed`() {
            assertEquals(Named("Reply ok", false, hint), parse("$idItem ⠋ | Reply ok ⠙"))
            assertEquals(Unnamed(hint), parse("$idItem ⠸"))
        }

        @Test
        fun `a name still being generated produces no reading`() {
            assertNull(parse("$idItem |"))
            assertNull(parse("$idItem ⠋ | ⠋"))
        }

        @Test
        fun `a name containing the separator is kept whole`() {
            assertEquals(Named("left | right", false, hint), parse("$idItem | left | right"))
        }

        @Test
        fun `any name ending in the ellipsis may be cut off, others are not`() {
            val cut = "A".repeat(45) + "..."
            assertEquals(Named(cut, true, hint), parse("$idItem | $cut"))
            assertEquals(Named(cut, true, hint), parse("$idItem ⠋ | $cut ⠼"))

            // Recorded after `/rename Investigate the flaky integration tests in the payments service today`.
            assertEquals(
                Named("Investigate the flaky integration tests in th...", true, IdHint("01a0edf7-13df-7bf3-a5ab-fc27d", true)),
                parse("01a0edf7-13df-7bf3-a5ab-fc27d... | Investigate the flaky integration tests in th..."),
            )

            // Shorter than a cut, as when Codex's sanitizer collapsed whitespace in the kept part.
            assertEquals(Named("Wait for it...", true, hint), parse("$idItem | Wait for it..."))
            assertEquals(Named("Wait for it", false, hint), parse("$idItem | Wait for it"))
        }

        @Test
        fun `titleText cuts by graphemes first and sanitizes after`() {
            val recorded = "Investigate the flaky integration tests in the payments service today"
            assertEquals("Investigate the flaky integration tests in th...", CodexTitleParser.titleText(recorded))
            assertEquals(
                "Investigate flaky integration tests in t...",
                CodexTitleParser.titleText("Investigate  flaky  integration  tests  in  the  payments  service"),
            )
            // 45 emoji (two UTF-16 units each) plus the ellipsis: 48 graphemes, 93 chars.
            assertEquals("😀".repeat(45) + "...", CodexTitleParser.titleText("😀".repeat(60)))
            assertEquals("😀".repeat(48), CodexTitleParser.titleText("😀".repeat(48)))
            assertEquals("Wait for it...", CodexTitleParser.titleText("  Wait for it...  "))
        }

        @Test
        fun `a full id is not a prefix`() {
            val id = "01a0edbb-4501-7591-82b7-36c4c0a1b2c3"
            assertEquals(Unnamed(IdHint(id, isPrefix = false)), parse(id))
        }

        @Test
        fun `shell, action-required and other titles produce no reading`() {
            assertNull(parse("greg@Sage: ~/Garage/prism"))
            assertNull(parse("[ ! ] Action Required | $idItem | Reply ok"))
            assertNull(parse("[ . ] Action Required | $idItem | Reply ok"))
            assertNull(parse("codex | prism"))
            assertNull(parse("deadbeef | Reply ok"))
            assertNull(parse(""))
        }

        @Test
        fun `a name ending in a spinner character keeps it when no name is being generated`() {
            assertEquals(Named("dots⠋", false, hint), parse("$idItem | dots⠋"))
            assertEquals(Named("Debug ⠋", false, hint), parse("$idItem | Debug ⠋"))
            assertEquals(Named("⠋", false, hint), parse("$idItem | ⠋"))
        }

        @Test
        fun `only the frame Codex appended is removed from a name being generated`() {
            assertEquals(Named("Debug ⠋", false, hint), parse("$idItem ⠙ | Debug ⠋ ⠙"))
            assertEquals(Named("⠋", false, hint), parse("$idItem ⠙ | ⠋ ⠙"))
        }

        @Test
        fun `normalize matches the Codex title sanitizer`() {
            assertEquals("Project | Working | Thread", CodexTitleParser.normalize("  Project\t|\nWorking\u001b\u0007\u009D\u009C |  Thread  "))
            assertEquals("Project Title", CodexTitleParser.normalize("Pro‮j⁦e‏c؜t​ ﻿T⁠itle"))
        }
    }

    @Test
    fun `id hints match whole ids exactly and prefixes by prefix`() {
        val id = "01a0edbb-4501-7591-82b7-36c4c0a1b2c3"
        assertTrue(IdHint("01a0edbb-4501", isPrefix = true).matches(id))
        assertFalse(IdHint("01a0edbb-4502", isPrefix = true).matches(id))
        assertTrue(IdHint(id, isPrefix = false).matches(id))
        assertFalse(IdHint("01a0edbb-4501", isPrefix = false).matches(id))
    }
}
