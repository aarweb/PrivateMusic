package com.aar.privatemusic.player

/** Máquina de estados pura del ciclo de un crossfade. */
class CrossfadeLifecycle {

    enum class Phase {
        IDLE,
        PREARMING,
        ARMED,
        WARMING,
        HANDOVER,
        OVERLAPPING,
        CANCELLED,
    }

    enum class CancelReason {
        PREPARATION_FAILED,
        TAIL_NO_AUDIO,
        EXCESSIVE_DRIFT,
        PAUSED,
        NAVIGATION,
        INSUFFICIENT_CONTENT,
        WINDOW_CLOSED,
        INCOMING_NO_AUDIO,
        TAIL_ERROR,
    }

    data class State(
        val phase: Phase,
        val trackId: String?,
        val nextId: String?,
        val cancelReason: CancelReason?,
    )

    data class Transition(
        val accepted: Boolean,
        val event: String,
        val before: State,
        val after: State,
    )

    var current: State = idleState()
        private set

    val phase: Phase get() = current.phase
    val trackId: String? get() = current.trackId
    val nextId: String? get() = current.nextId
    val cancelReason: CancelReason? get() = current.cancelReason

    /** Pista que legítimamente debe estar en el reproductor principal. */
    val expectedMainTrackId: String?
        get() = if (phase == Phase.OVERLAPPING) nextId else trackId

    fun prearm(trackId: String, nextId: String): Transition =
        move("prearm", setOf(Phase.IDLE)) {
            State(Phase.PREARMING, trackId, nextId, null)
        }

    fun armed(): Transition = advance("armed", Phase.PREARMING, Phase.ARMED)

    fun warming(): Transition = advance("warming", Phase.ARMED, Phase.WARMING)

    fun handover(): Transition = advance("handover", Phase.WARMING, Phase.HANDOVER)

    fun overlapping(): Transition = advance("overlapping", Phase.HANDOVER, Phase.OVERLAPPING)

    fun complete(): Transition = move("complete", setOf(Phase.OVERLAPPING)) { idleState() }

    fun cancel(reason: CancelReason): Transition =
        move("cancel:$reason", ACTIVE_PHASES) {
            current.copy(phase = Phase.CANCELLED, cancelReason = reason)
        }

    /**
     * La cancelación permanece consultable mientras siga la misma pista. Al
     * cambiar de canción se descarta de forma explícita y vuelve a IDLE.
     */
    fun trackChanged(currentTrackId: String?): Transition =
        move("trackChanged:$currentTrackId", setOf(Phase.CANCELLED)) {
            if (currentTrackId != trackId) idleState() else current
        }.let { result ->
            if (result.accepted && result.after.phase == Phase.CANCELLED) {
                rejected(result.event, result.before)
            } else {
                result
            }
        }

    /** Reset de emergencia siempre válido e idempotente. */
    fun reset(): Transition {
        val before = current
        current = idleState()
        return Transition(true, "reset", before, current)
    }

    private fun advance(event: String, from: Phase, to: Phase): Transition =
        move(event, setOf(from)) { current.copy(phase = to, cancelReason = null) }

    private inline fun move(
        event: String,
        allowed: Set<Phase>,
        destination: () -> State,
    ): Transition {
        val before = current
        if (before.phase !in allowed) return rejected(event, before)
        current = destination()
        return Transition(true, event, before, current)
    }

    private fun rejected(event: String, before: State): Transition =
        Transition(false, event, before, current)

    private companion object {
        val ACTIVE_PHASES = setOf(
            Phase.PREARMING,
            Phase.ARMED,
            Phase.WARMING,
            Phase.HANDOVER,
            Phase.OVERLAPPING,
        )

        fun idleState() = State(Phase.IDLE, null, null, null)
    }
}
