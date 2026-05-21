package com.github.vgirotto.prism.services

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.nio.charset.Charset

/**
 * TtyConnector that wraps a PtyProcess for Claude Code.
 * Intercepts writes to detect when the user sends a message (Enter key),
 * and monitors reads to detect when Claude finishes responding.
 * Parses initial output to detect model and effort level.
 */
class AgentTtyConnector(
    process: Process,
    charset: Charset,
    private val onUserInput: (() -> Unit)? = null,
    private val onOutputActivity: (() -> Unit)? = null,
    private val onStartupParsed: ((model: String, effort: String) -> Unit)? = null,
) : ProcessTtyConnector(process, charset) {

    @Volatile
    private var lastOutputTime = 0L

    /** Buffer initial output to parse model/effort from Claude startup banner */
    private val startupBuffer = StringBuilder()

    @Volatile
    private var startupParsed = false

    override fun getName(): String = "Prism"

    override fun isConnected(): Boolean = process.isAlive

    override fun resize(termSize: TermSize) {
        val ptyProcess = process
        if (ptyProcess is PtyProcess && ptyProcess.isAlive) {
            ptyProcess.winSize = WinSize(termSize.columns, termSize.rows)
        }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
            onUserInput?.invoke()
        }
        super.write(bytes)
    }

    override fun write(string: String) {
        if (string.contains('\r') || string.contains('\n')) {
            onUserInput?.invoke()
        }
        super.write(string)
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val bytesRead = super.read(buf, offset, length)
        if (bytesRead > 0) {
            // Only treat as meaningful output if it contains printable text.
            // Cursor blink (\e[?25h / \e[?25l) and other terminal control sequences
            // are filtered out to prevent sessions from being stuck in WORKING state.
            if (containsPrintableText(buf, offset, bytesRead)) {
                lastOutputTime = System.currentTimeMillis()
                onOutputActivity?.invoke()
            }

            // Buffer output and try to parse startup banner on each read
            if (!startupParsed) {
                startupBuffer.append(buf, offset, bytesRead)
                tryParseModel()
                // Stop buffering after 16KB to avoid memory waste
                if (startupBuffer.length > 16384) {
                    startupParsed = true
                    startupBuffer.clear()
                }
            }
        }
        return bytesRead
    }

    /**
     * Returns true if the buffer contains printable text (not just escape sequences).
     * Cursor blink is typically pure CSI sequences (\e[?25h / \e[?25l) with no printable chars.
     * Real Claude output always contains printable characters (text, code, etc.).
     */
    private fun containsPrintableText(buf: CharArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val c = buf[i]
            if (c == '\u001B') {
                // Skip escape sequence
                i++
                if (i >= end) break
                when (buf[i]) {
                    '[' -> {
                        // CSI sequence \e[...{final byte 0x40-0x7E}
                        i++
                        while (i < end && buf[i].code !in 0x40..0x7E) i++
                        if (i < end) i++
                    }
                    ']' -> {
                        // OSC sequence \e]...BEL
                        i++
                        while (i < end && buf[i] != '\u0007') i++
                        if (i < end) i++
                    }
                    else -> i++ // 2-byte escape
                }
            } else if (c.code >= 0x20 && c.code != 0x7F) {
                return true // printable character (ASCII or Unicode)
            } else {
                i++ // control character (\n, \r, etc.)
            }
        }
        return false
    }

    /**
     * Try to parse model/effort from accumulated output.
     * Called on each read() and also from idle monitor.
     */
    fun tryParseStartup() {
        if (!startupParsed && startupBuffer.isNotEmpty()) {
            tryParseModel()
        }
    }

    private fun tryParseModel() {
        // Strip ALL escape sequences and control characters
        val raw = startupBuffer.toString()
        val text = raw
            .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")      // CSI: \e[...m
            .replace(Regex("\u001B\\][^\u0007\u001B]*[\u0007]"), "") // OSC: \e]...BEL
            .replace(Regex("\u001B[^\\[\\]]."), "")                // Other 2-char escapes
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "") // Control chars

        var model = ""
        var effort = ""

        // Parse model: "Opus 4.6" / "Sonnet 4.6" / "Haiku 4.5"
        val modelRegex = Regex("(Opus|Sonnet|Haiku)\\s+[\\d.]+", RegexOption.IGNORE_CASE)
        modelRegex.find(text)?.let { match ->
            model = match.value.split("\\s+".toRegex())[0].lowercase()
        }

        // Parse effort: "with X effort"
        val effortRegex = Regex("with\\s+(\\w+)\\s+effort", RegexOption.IGNORE_CASE)
        effortRegex.find(text)?.let { match ->
            effort = match.groupValues[1].lowercase()
        }

        if (model.isNotEmpty() || effort.isNotEmpty()) {
            startupParsed = true
            startupBuffer.clear()
            onStartupParsed?.invoke(model, effort)
        }
    }

    fun getIdleTimeMs(): Long {
        val last = lastOutputTime
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }
}
