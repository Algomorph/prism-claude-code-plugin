package com.github.vgirotto.prism.chatshell

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The wire protocol between Kotlin and the JCEF shell (design §6.1, §8.3, R15/R18).
 *
 * Kotlin emits **data, never HTML**: a [TranscriptDelta] of typed [RenderBlock]s with
 * pre-resolved media. It is serialized to JSON, UTF-8, then base64, and handed to a
 * single trusted JS entry point (`window.__prismApplyDelta`). Base64 protects the
 * `executeJavaScript` call boundary — transcript content is never interpolated into the
 * script string. Content *safety* comes from the in-browser escape→marked→sanitize
 * pipeline (see `shell.js`, design §6.8), not from the encoding.
 */

/** A single visible unit inside a message, mirroring the sealed block model (§6.3). */
data class RenderBlock(
    /** text | thinking | toolUse | toolResult | image | toolReference | unknown */
    val kind: String,
    /** visible | collapsed | hidden-internal (§6.3, R22). */
    val visibility: String = "visible",
    /** Raw markdown (math delimiters intact) for text/thinking blocks — NEVER HTML. */
    val markdown: String? = null,
    val toolName: String? = null,
    /** Pretty-printed tool input JSON; rendered as textContent, never parsed as HTML. */
    val toolInput: String? = null,
    val toolResultText: String? = null,
    val isError: Boolean? = null,
    /** A bounded `data:image/...` URI already produced by the MediaResolver (§6.8). */
    val imageDataUri: String? = null,
    val imageAlt: String? = null,
    /** Human label for unsupported/reference markers. */
    val label: String? = null,
)

/** The per-message payload carried by an `upsert` op. */
data class BlockPayload(
    val role: String,
    val roleLabel: String? = null,
    val blocks: List<RenderBlock> = emptyList(),
)

/**
 * One delta operation. Flattened (not a sealed hierarchy) so Gson serializes it to the
 * exact `{op, id, payload}` shape `shell.js` expects. `op` is one of upsert|remove|reset.
 */
data class DeltaOp(
    val op: String,
    val id: String? = null,
    val payload: BlockPayload? = null,
) {
    companion object {
        fun upsert(id: String, payload: BlockPayload) = DeltaOp("upsert", id, payload)
        fun remove(id: String) = DeltaOp("remove", id, null)
        fun reset() = DeltaOp("reset", null, null)
    }
}

/**
 * A versioned batch of operations. [epoch] bumps on reset boundaries (/clear, /resume,
 * /compact, recovery reload); [revision] increments per delta within an epoch. Acks echo
 * both so late/stale acks can be discarded (§8.3).
 */
data class TranscriptDelta(
    val epoch: Long,
    val revision: Long,
    val operations: List<DeltaOp>,
)

/** The ack the browser posts back after a delta renders + lays out (§8.3). */
data class TranscriptAck(
    val epoch: Long,
    val revision: Long,
    val status: String,
)

object TranscriptCodec {
    private val gson = Gson() // default: omits nulls, no HTML escaping needed (base64'd)

    /** Serialize a delta to base64(UTF-8(JSON)) for the trusted JS entry point. */
    fun encodeDelta(delta: TranscriptDelta): String {
        val json = gson.toJson(delta)
        return Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    fun encodeDeltaJson(delta: TranscriptDelta): String = gson.toJson(delta)

    /** Parse an ack posted by the browser (best-effort; returns null on garbage). */
    fun decodeAck(raw: String): TranscriptAck? = try {
        gson.fromJson(raw, TranscriptAck::class.java)
    } catch (_: Exception) {
        null
    }
}
