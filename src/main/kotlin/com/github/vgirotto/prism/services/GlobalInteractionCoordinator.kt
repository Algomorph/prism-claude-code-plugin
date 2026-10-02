package com.github.vgirotto.prism.services

internal data class InteractionAttribution(val sessionNames: List<String>)

/**
 * Groups overlapping agent interactions into one project-wide diff interval.
 * All calls are serialized by FileSnapshotService's executor.
 */
internal class GlobalInteractionCoordinator {
    /** [active] tracks membership in the *current* overlap, separately from having joined the group. */
    private class Participant(val name: String, var active: Boolean)

    private val participants = linkedMapOf<String, Participant>()

    /** Returns true only when this interaction must capture a new global baseline. */
    fun begin(sessionId: String, sessionName: String): Boolean {
        val captureBaseline = participants.isEmpty()
        val existing = participants[sessionId]
        if (existing != null) {
            existing.active = true
            return false
        }
        participants[sessionId] = Participant(sessionName, active = true)
        return captureBaseline
    }

    /** Returns attribution only when the last overlapping interaction finishes. */
    fun finish(sessionId: String): InteractionAttribution? {
        val participant = participants[sessionId] ?: return null
        if (!participant.active) return null
        participant.active = false
        if (participants.values.any { it.active }) return null
        val attribution = InteractionAttribution(participants.values.map { it.name })
        participants.clear()
        return attribution
    }

    fun reset() {
        participants.clear()
    }

    fun hasActiveInteraction(): Boolean = participants.values.any { it.active }
}
