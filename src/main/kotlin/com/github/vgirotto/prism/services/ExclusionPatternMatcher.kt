package com.github.vgirotto.prism.services

/**
 * Matches snapshot exclusion entries against project-relative paths.
 *
 * Exact entries match directory or file name segments, preserving the previous
 * behavior for values like "build" and "node_modules". Entries containing
 * wildcards use glob-like matching: "*" and "?" stay within one path segment,
 * while "**" can cross path separators.
 *
 * Wildcard patterns are translated to regexes once via [compile]; the resulting
 * [Compiled] matcher is reused across every path. Callers that check many paths
 * against the same pattern list (e.g. a full snapshot scan) should [compile]
 * once and reuse the result instead of repeatedly calling [matches], which
 * recompiles on every invocation.
 */
internal object ExclusionPatternMatcher {

    /** Convenience entry point. Compiles [patterns] on every call — prefer [compile] for hot paths. */
    fun matches(path: String, patterns: Iterable<String>): Boolean =
        compile(patterns).matches(path)

    /** Pre-compiles [patterns] into a reusable matcher. */
    fun compile(patterns: Iterable<String>): Compiled {
        val exactNames = mutableSetOf<String>()
        val segmentRegexes = mutableListOf<Regex>()
        val pathRegexes = mutableListOf<Regex>()

        for (rawPattern in patterns) {
            val pattern = normalize(rawPattern).trim('/')
            if (pattern.isEmpty()) continue

            if (!hasWildcard(pattern)) {
                exactNames.add(pattern)
            } else if ('/' in pattern) {
                pathRegexes.addAll(wildcardRegexes(pattern))
            } else {
                segmentRegexes.addAll(wildcardRegexes(pattern))
            }
        }

        return Compiled(exactNames, segmentRegexes, pathRegexes)
    }

    /**
     * A matcher built from a fixed set of exclusion patterns. Holds the compiled
     * wildcard regexes so each [matches] call only re-tokenizes the path, not the
     * patterns.
     */
    internal class Compiled(
        private val exactNames: Set<String>,
        private val segmentRegexes: List<Regex>,
        private val pathRegexes: List<Regex>,
    ) {
        fun matches(path: String): Boolean {
            val normalizedPath = normalize(path).trim('/')
            if (normalizedPath.isEmpty()) return false

            val segments = normalizedPath.split('/').filter { it.isNotEmpty() }

            if (exactNames.isNotEmpty() && segments.any { it in exactNames }) return true
            if (segmentRegexes.isNotEmpty() &&
                segments.any { segment -> segmentRegexes.any { it.matches(segment) } }
            ) {
                return true
            }
            if (pathRegexes.isNotEmpty()) {
                val pathCandidates = pathCandidates(normalizedPath)
                if (pathCandidates.any { candidate -> pathRegexes.any { it.matches(candidate) } }) {
                    return true
                }
            }
            return false
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
