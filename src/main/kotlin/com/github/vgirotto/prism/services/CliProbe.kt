package com.github.vgirotto.prism.services

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/** Short-lived CLI probes (`--help`, `--version`) shared by the validation services. */
internal object CliProbe {

    private val log = Logger.getInstance(CliProbe::class.java)

    /** The first `major.minor.patch` in [output], e.g. `2.1.210` or `0.159.0`. */
    fun versionIn(output: String?): String? =
        output?.let { Regex("""(\d+)\.(\d+)\.(\d+)""").find(it)?.value }

    /**
     * Run a short-lived probe command and return its combined output, or null on failure.
     * The output is drained on a daemon thread so a child that never closes stdout cannot
     * block us past [timeoutSec]; on timeout the process is force-killed (review #11 — a
     * bare `readText()` before `waitFor` can hang indefinitely).
     */
    fun run(command: List<String>, timeoutSec: Long): String? {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val drain = Thread {
                try {
                    process.inputStream.bufferedReader().use { r -> r.forEachLine { sb.appendLine(it) } }
                } catch (_: Exception) { /* stream closed on kill */ }
            }.apply { isDaemon = true; start() }
            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                log.debug("Probe timed out: ${command.joinToString(" ")}")
                return null
            }
            drain.join(1000)
            sb.toString()
        } catch (e: Exception) {
            log.debug("Probe failed: ${command.joinToString(" ")}", e)
            null
        }
    }
}
