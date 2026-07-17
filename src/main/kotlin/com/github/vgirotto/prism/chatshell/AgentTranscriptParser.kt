package com.github.vgirotto.prism.chatshell

import java.io.File

/**
 * The per-agent transcript parser seam (design §11). Each agent CLI persists its session in
 * its own on-disk JSONL schema; one implementation per CLI turns that into the shared,
 * non-lossy [TranscriptMessage] model so the render/tail/controller stack stays agent-agnostic.
 *
 * Implementations:
 *  - [TranscriptParser]      — Claude Code (`~/.claude/projects/<esc>/<id>.jsonl`)
 *  - [CodexTranscriptParser] — OpenAI Codex (`~/.codex/sessions/.../rollout-*.jsonl`)
 *
 * Both entry points must tolerate a partial trailing line (live tailing) by skipping it, and
 * must never throw for a single malformed record.
 */
interface AgentTranscriptParser {
    fun parseFile(file: File): List<TranscriptMessage>
    fun parseLines(lines: Sequence<String>): List<TranscriptMessage>
}
