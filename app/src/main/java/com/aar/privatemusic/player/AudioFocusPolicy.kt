package com.aar.privatemusic.player

/**
 * Pure state machine for the local players' shared audio-focus ownership.
 *
 * Android callbacks and player mutations deliberately live outside this class. Keeping the
 * transition function pure makes duplicate and reordered focus events straightforward to test.
 */
object AudioFocusPolicy {

    enum class FocusState {
        NONE,
        REQUESTING,
        HELD,
        DUCKED,
        TRANSIENT_LOSS,
        PERMANENT_LOSS,
    }

    data class State(
        val focus: FocusState = FocusState.NONE,
        val wantsPlayback: Boolean = false,
        val isPlaying: Boolean = false,
        val resumeOnGain: Boolean = false,
        val duckMultiplier: Float = FULL_VOLUME,
    )

    sealed interface Event {
        data object Play : Event
        data object Pause : Event
        data object Stop : Event
        data object PlaybackStarted : Event
        data object PlaybackStopped : Event
        data object FocusRequestGranted : Event
        data object FocusRequestDenied : Event
        data object FocusGain : Event
        data object FocusDuck : Event
        data object FocusLossTransient : Event
        data object FocusLoss : Event
    }

    data class Decision(
        val requestFocus: Boolean = false,
        val abandonFocus: Boolean = false,
        val pausePlayers: Boolean = false,
        val resumeMain: Boolean = false,
        /** Null means that the currently published multiplier remains valid. */
        val duckMultiplier: Float? = null,
    )

    data class Transition(
        val state: State,
        val decision: Decision = Decision(),
    )

    fun reduce(state: State, event: Event): Transition = when (event) {
        Event.Play -> onPlay(state)
        Event.Pause -> onPauseOrStop(state)
        Event.Stop -> onPauseOrStop(state)
        Event.PlaybackStarted -> Transition(state.copy(isPlaying = true))
        Event.PlaybackStopped -> Transition(state.copy(isPlaying = false))
        Event.FocusRequestGranted -> onFocusRequestGranted(state)
        Event.FocusRequestDenied -> onFocusRequestDenied(state)
        Event.FocusGain -> onFocusGain(state)
        Event.FocusDuck -> onFocusDuck(state)
        Event.FocusLossTransient -> onTransientLoss(state)
        Event.FocusLoss -> onPermanentLoss(state)
    }

    private fun onPlay(state: State): Transition = when (state.focus) {
        FocusState.NONE,
        FocusState.PERMANENT_LOSS,
        -> Transition(
            state.copy(
                focus = FocusState.REQUESTING,
                wantsPlayback = true,
                isPlaying = false,
                resumeOnGain = false,
                duckMultiplier = FULL_VOLUME,
            ),
            Decision(requestFocus = true),
        )

        FocusState.REQUESTING -> Transition(state.copy(wantsPlayback = true))

        FocusState.HELD,
        FocusState.DUCKED,
        -> if (state.wantsPlayback && state.isPlaying) {
            Transition(state)
        } else {
            Transition(
                state.copy(wantsPlayback = true, isPlaying = true),
                Decision(resumeMain = true),
            )
        }

        FocusState.TRANSIENT_LOSS -> Transition(
            state.copy(wantsPlayback = true, resumeOnGain = true),
        )
    }

    private fun onPauseOrStop(state: State): Transition {
        val ownedOrPending = state.focus != FocusState.NONE &&
            state.focus != FocusState.PERMANENT_LOSS
        val shouldPause = state.isPlaying || state.wantsPlayback || state.resumeOnGain
        val resetMultiplier = state.duckMultiplier != FULL_VOLUME
        val alreadyStopped = !ownedOrPending && !shouldPause && !resetMultiplier &&
            state.focus == FocusState.NONE
        if (alreadyStopped) return Transition(state)

        return Transition(
            State(),
            Decision(
                abandonFocus = ownedOrPending,
                pausePlayers = shouldPause,
                duckMultiplier = FULL_VOLUME.takeIf { resetMultiplier },
            ),
        )
    }

    private fun onFocusRequestGranted(state: State): Transition {
        if (state.focus != FocusState.REQUESTING) return Transition(state)
        return if (state.wantsPlayback) {
            Transition(
                state.copy(
                    focus = FocusState.HELD,
                    isPlaying = true,
                    resumeOnGain = false,
                    duckMultiplier = FULL_VOLUME,
                ),
                Decision(resumeMain = true),
            )
        } else {
            Transition(State(), Decision(abandonFocus = true))
        }
    }

    private fun onFocusRequestDenied(state: State): Transition =
        if (state.focus == FocusState.REQUESTING) Transition(State()) else Transition(state)

    private fun onFocusGain(state: State): Transition = when (state.focus) {
        FocusState.REQUESTING -> onFocusRequestGranted(state)

        FocusState.TRANSIENT_LOSS -> {
            val shouldResume = state.resumeOnGain
            if (shouldResume) {
                Transition(
                    state.copy(
                        focus = FocusState.HELD,
                        wantsPlayback = true,
                        isPlaying = true,
                        resumeOnGain = false,
                        duckMultiplier = FULL_VOLUME,
                    ),
                    Decision(
                        resumeMain = true,
                        duckMultiplier = FULL_VOLUME.takeIf {
                            state.duckMultiplier != FULL_VOLUME
                        },
                    ),
                )
            } else {
                Transition(
                    State(),
                    Decision(
                        abandonFocus = true,
                        duckMultiplier = FULL_VOLUME.takeIf {
                            state.duckMultiplier != FULL_VOLUME
                        },
                    ),
                )
            }
        }

        FocusState.DUCKED -> Transition(
            state.copy(focus = FocusState.HELD, duckMultiplier = FULL_VOLUME),
            Decision(duckMultiplier = FULL_VOLUME),
        )

        FocusState.NONE,
        FocusState.HELD,
        FocusState.PERMANENT_LOSS,
        -> Transition(state)
    }

    private fun onFocusDuck(state: State): Transition = when (state.focus) {
        FocusState.HELD -> Transition(
            state.copy(focus = FocusState.DUCKED, duckMultiplier = DUCK_VOLUME),
            Decision(duckMultiplier = DUCK_VOLUME),
        )

        FocusState.NONE,
        FocusState.REQUESTING,
        FocusState.DUCKED,
        FocusState.TRANSIENT_LOSS,
        FocusState.PERMANENT_LOSS,
        -> Transition(state)
    }

    private fun onTransientLoss(state: State): Transition = when (state.focus) {
        FocusState.REQUESTING,
        FocusState.HELD,
        FocusState.DUCKED,
        -> {
            val shouldResume = state.wantsPlayback || state.isPlaying
            val resetMultiplier = state.duckMultiplier != FULL_VOLUME
            Transition(
                state.copy(
                    focus = FocusState.TRANSIENT_LOSS,
                    isPlaying = false,
                    resumeOnGain = shouldResume,
                    duckMultiplier = FULL_VOLUME,
                ),
                Decision(
                    pausePlayers = shouldResume,
                    duckMultiplier = FULL_VOLUME.takeIf { resetMultiplier },
                ),
            )
        }

        FocusState.NONE,
        FocusState.TRANSIENT_LOSS,
        FocusState.PERMANENT_LOSS,
        -> Transition(state)
    }

    private fun onPermanentLoss(state: State): Transition {
        if (state.focus == FocusState.NONE || state.focus == FocusState.PERMANENT_LOSS) {
            return Transition(state)
        }
        val alreadyPausedForTransient = state.focus == FocusState.TRANSIENT_LOSS && !state.isPlaying
        return Transition(
            State(focus = FocusState.PERMANENT_LOSS),
            Decision(
                abandonFocus = true,
                pausePlayers = !alreadyPausedForTransient &&
                    (state.isPlaying || state.wantsPlayback || state.resumeOnGain),
                duckMultiplier = FULL_VOLUME.takeIf {
                    state.duckMultiplier != FULL_VOLUME
                },
            ),
        )
    }

    const val FULL_VOLUME = 1f
    const val DUCK_VOLUME = 0.2f
}
