package com.github.vgirotto.prism.chatshell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionResolverTest {

    @Test
    fun `escapes project path forward with slash and underscore to dash`() {
        val r = SessionResolver("/home/greg/Garage/prism-claude-code-plugin", "/home/greg")
        assertEquals("-home-greg-Garage-prism-claude-code-plugin", r.escapedProjectName())
    }

    @Test
    fun `escaping is lossy - distinct paths can collide - so we never invert`() {
        // A '-' segment and a '/'-split path escape to the same directory name. This is
        // exactly why the resolver escapes forward and never reconstructs a path.
        val withDash = SessionResolver("/a/b-c").escapedProjectName()
        val withSlash = SessionResolver("/a/b/c").escapedProjectName()
        assertEquals(withDash, withSlash)
        assertEquals("-a-b-c", withDash)
    }

    @Test
    fun `transcript file is conversationId dot jsonl in the escaped dir`() {
        val r = SessionResolver("/home/dev/proj", "/home/dev")
        val f = r.transcriptFile("abc-123")!!
        assertEquals("abc-123.jsonl", f.name)
        assertEquals("-home-dev-proj", f.parentFile.name)
    }

    @Test
    fun `fresh project dir does not exist yet - parent is the projects root`() {
        val r = SessionResolver("/nonexistent/brand/new/project", "/home/dev")
        assertFalse(r.projectDirExists())
        assertTrue(r.projectsRoot.path.endsWith(".claude/projects"))
    }

    @Test
    fun `null base path yields null escaped name`() {
        assertEquals(null, SessionResolver(null).escapedProjectName())
    }
}
