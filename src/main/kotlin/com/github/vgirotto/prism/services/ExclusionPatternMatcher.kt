package com.github.vgirotto.prism.services

/**
 * Matches snapshot exclusion entries against project-relative paths.
 *
 * Exact entries match directory or file name segments, preserving the previous
 * behavior for values like "build" and "node_modules". Entries containing
 * wildcards use glob-like matching: "*" and "?" stay within one path segment,
 * while "**" can cross path separators.
 */
internal object ExclusionPatternMatcher {

    fun matches(path: String, patterns: Iterable<String>): Boolean {
        val normalizedPath = normalize(path).trim('/')
        if (normalizedPath.isEmpty()) return false

        val segments = normalizedPath.split('/').filter { it.isNotEmpty() }
        val pathCandidates = pathCandidates(normalizedPath)

        return patterns.any { rawPattern ->
            val pattern = normalize(rawPattern).trim('/')
            if (pattern.isEmpty()) {
                false
            } else if (hasWildcard(pattern)) {
                wildcardMatches(pattern, segments, pathCandidates)
            } else {
                segments.any { it == pattern }
            }
        }
    }

    private fun wildcardMatches(
        pattern: String,
        segments: List<String>,
        pathCandidates: List<String>,
    ): Boolean {
        val regexes = wildcardRegexes(pattern)
        return if ('/' in pattern) {
            pathCandidates.any { candidate -> regexes.any { it.matches(candidate) } }
        } else {
            segments.any { segment -> regexes.any { it.matches(segment) } }
        }
    }

    private fun wildcardRegexes(pattern: String): List<Regex> {
        val regexes = mutableListOf(globToRegex(pattern).toRegex())
        if (pattern.endsWith("/**")) {
            regexes.add(globToRegex(pattern.removeSuffix("/**")).toRegex())
        }
        return regexes
    }

    private fun pathCandidates(path: String): List<String> {
        val candidates = mutableListOf(path)
        var nextSlash = path.lastIndexOf('/')
        while (nextSlash > 0) {
            candidates.add(path.substring(0, nextSlash))
            nextSlash = path.lastIndexOf('/', nextSlash - 1)
        }
        return candidates
    }

    private fun hasWildcard(pattern: String): Boolean =
        pattern.any { it == '*' || it == '?' }

    private fun normalize(value: String): String =
        value.trim().replace('\\', '/')

    private fun globToRegex(pattern: String): String {
        val out = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when (val char = pattern[i]) {
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            out.append("(?:.*/)?")
                            i += 3
                        } else {
                            out.append(".*")
                            i += 2
                        }
                    } else {
                        out.append("[^/]*")
                        i++
                    }
                }
                '?' -> {
                    out.append("[^/]")
                    i++
                }
                else -> {
                    if (char in RegexSpecialChars) out.append('\\')
                    out.append(char)
                    i++
                }
            }
        }
        out.append('$')
        return out.toString()
    }

    private val RegexSpecialChars = setOf('.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|')
}
