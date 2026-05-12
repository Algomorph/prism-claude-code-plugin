package com.github.vgirotto.prism.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusionPatternMatcherTest {

    @Test
    fun `exact patterns match path segments`() {
        val patterns = listOf("node_modules", "build")

        assertTrue(ExclusionPatternMatcher.matches("node_modules/lib/index.js", patterns))
        assertTrue(ExclusionPatternMatcher.matches("src/build/output.txt", patterns))
        assertFalse(ExclusionPatternMatcher.matches("src/main.kt", patterns))
    }

    @Test
    fun `single segment wildcards match directory names`() {
        val patterns = listOf("cmake*", "build-?")

        assertTrue(ExclusionPatternMatcher.matches("cmake/README.md", patterns))
        assertTrue(ExclusionPatternMatcher.matches("src/cmake-build-debug/cache.txt", patterns))
        assertTrue(ExclusionPatternMatcher.matches("build-a/output.txt", patterns))
        assertFalse(ExclusionPatternMatcher.matches("build-long/output.txt", patterns))
    }

    @Test
    fun `path wildcards match relative paths and parent directories`() {
        val patterns = listOf("docs/*.md", "src/generated-*", "fixtures/**")

        assertTrue(ExclusionPatternMatcher.matches("docs/readme.md", patterns))
        assertFalse(ExclusionPatternMatcher.matches("docs/nested/readme.md", patterns))
        assertTrue(ExclusionPatternMatcher.matches("src/generated-client/model.kt", patterns))
        assertTrue(ExclusionPatternMatcher.matches("fixtures", patterns))
        assertTrue(ExclusionPatternMatcher.matches("fixtures/a/b/c.json", patterns))
    }

    @Test
    fun `double star prefix matches directories at any depth`() {
        val patterns = listOf("**/generated")

        assertTrue(ExclusionPatternMatcher.matches("generated/output.txt", patterns))
        assertTrue(ExclusionPatternMatcher.matches("src/generated/output.txt", patterns))
        assertTrue(ExclusionPatternMatcher.matches("src/main/generated/output.txt", patterns))
        assertFalse(ExclusionPatternMatcher.matches("src/not-generated/output.txt", patterns))
    }
}
