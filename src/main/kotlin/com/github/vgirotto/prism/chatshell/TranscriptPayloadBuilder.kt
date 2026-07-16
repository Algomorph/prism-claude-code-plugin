package com.github.vgirotto.prism.chatshell

import java.io.File

/**
 * Turns [TranscriptMessage]s into the serializable [BlockPayload]/[RenderBlock] wire form
 * (design §6.2, R18). Emits **data, never HTML**, and never runs marked/KaTeX/DOMPurify
 * (those are browser-side). Images are resolved to bounded `data:` URIs (or blocked
 * markers) here via [MediaResolver] before the payload leaves Kotlin, so the browser never
 * dereferences a path or URL.
 *
 * [roleLabel] is injectable so Group 4/6 can route labels through the i18n bundle.
 */
class TranscriptPayloadBuilder(
    private val allowedImageRoots: List<File> = emptyList(),
    private val roleLabel: (String) -> String = ::defaultRoleLabel,
) {

    fun buildPayload(message: TranscriptMessage): BlockPayload =
        BlockPayload(
            role = message.role,
            roleLabel = roleLabel(message.role),
            blocks = message.blocks.map { toRenderBlock(it) },
        )

    /**
     * A full (re)render as a single delta: a `reset` followed by an `upsert` per renderable
     * message. Non-renderable (all-hidden-internal) messages are preserved in the model but
     * omitted from the payload to keep it lean.
     */
    fun buildDelta(messages: List<TranscriptMessage>, epoch: Long, revision: Long): TranscriptDelta {
        val ops = ArrayList<DeltaOp>()
        ops.add(DeltaOp.reset())
        for (m in messages) {
            if (!m.isRenderable) continue
            ops.add(DeltaOp.upsert(m.id, buildPayload(m)))
        }
        return TranscriptDelta(epoch, revision, ops)
    }

    /** An incremental upsert for a single message (used by the live seam, Group 5). */
    fun upsertOp(message: TranscriptMessage): DeltaOp =
        DeltaOp.upsert(message.id, buildPayload(message))

    private fun toRenderBlock(block: Block): RenderBlock {
        val vis = block.visibility.wire()
        return when (block) {
            is TextBlock -> RenderBlock("text", vis, markdown = block.markdown)
            is ThinkingBlock -> RenderBlock("thinking", vis, markdown = block.text)
            is ToolUseBlock -> RenderBlock("toolUse", vis, toolName = block.name, toolInput = block.inputJson)
            is ToolResultBlock -> RenderBlock(
                "toolResult", vis, toolResultText = block.text, isError = block.isError
            )
            is ImageBlock -> resolveImage(block, vis)
            is ToolReferenceBlock -> RenderBlock("toolReference", vis, label = "↳ ${block.toolName}")
            is CompactBoundaryBlock -> RenderBlock("compactBoundary", vis, label = block.summary)
            is UnknownBlock -> RenderBlock("unknown", vis, label = "[unsupported content]")
        }
    }

    private fun resolveImage(block: ImageBlock, vis: String): RenderBlock {
        val result = when {
            block.base64Data != null -> MediaResolver.resolveBase64(block.mediaType, block.base64Data)
            block.sourceRef != null && allowedImageRoots.isNotEmpty() ->
                MediaResolver.resolveLocalFile(block.sourceRef, allowedImageRoots)
            else -> MediaResolver.Blocked("no resolvable image source")
        }
        return when (result) {
            is MediaResolver.Resolved -> RenderBlock("image", vis, imageDataUri = result.dataUri, imageAlt = "image")
            is MediaResolver.Blocked -> RenderBlock("image", vis, imageAlt = "[blocked image]")
        }
    }

    companion object {
        fun defaultRoleLabel(role: String): String = when (role) {
            "user" -> "You"
            "assistant" -> "Claude"
            else -> role.replaceFirstChar { it.uppercase() }
        }
    }
}

private fun Visibility.wire(): String = when (this) {
    Visibility.VISIBLE -> "visible"
    Visibility.COLLAPSED -> "collapsed"
    Visibility.HIDDEN_INTERNAL -> "hidden-internal"
}
