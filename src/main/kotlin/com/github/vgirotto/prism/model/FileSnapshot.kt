package com.github.vgirotto.prism.model

/**
 * Represents the diff result for a single file between two interactions.
 */
data class FileDiffEntry(
    val path: String,
    val status: ChangeStatus,
    val originalContent: ByteArray?,
    val modifiedContent: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDiffEntry) return false
        return path == other.path && status == other.status
    }

    override fun hashCode(): Int = 31 * path.hashCode() + status.hashCode()
}

enum class ChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
}

/**
 * Represents all changes from a single agent interaction.
 */
data class InteractionDiff(
    val interactionIndex: Int,
    val timestamp: Long,
    val changes: List<FileDiffEntry>,
    val sessionName: String = "",
)
