package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

/** Typed outcomes of one evaluated orientation sample during an armed Entry session. */
sealed interface EntryDetectionVerdict {
    data class DoorOpened(val angleDeg: Double, val episodeId: String) : EntryDetectionVerdict
    data class DoorStillOpen(val angleDeg: Double, val episodeId: String) : EntryDetectionVerdict
    data class DoorClosedConfirmed(val episodeId: String) : EntryDetectionVerdict
    data object SourceUnavailable : EntryDetectionVerdict
    data object SourceRecovered : EntryDetectionVerdict
    data object MountMoved : EntryDetectionVerdict
}

/** One physical door opening and its lifecycle inside a single armed session. */
data class EntryDoorEpisode(
    val episodeId: String,
    val openedAtMs: Long,
    val peakAngleDeg: Double,
    val interrupted: Boolean = false,
)

/**
 * Pure armed-session detector for Entry Guard (spec sections 6-7).
 *
 * Strict evaluation order per sample: source freshness, hinge-axis residual, allowed
 * opening direction, then the customer angle threshold. A sample failing an earlier
 * gate can never produce `DOOR_OPEN`. The baseline is frozen for the whole armed
 * session — this class never rebaselines.
 */
class EntryDetectionPolicy(
    private val baseline: EntryQuaternion,
    private val model: EntryHingeModel,
    private val settings: EntryProfileSettings,
) {

    data class State(
        val doorEpisode: EntryDoorEpisode? = null,
        val sourceUnavailableSinceMs: Long? = null,
        val recoveryHealthySinceMs: Long? = null,
        val mountMoved: Boolean = false,
        val openStreakStartMs: Long? = null,
        val closeStreakStartMs: Long? = null,
        val episodeCounter: Int = 0,
    )

    private val axis = doubleArrayOf(model.axisX, model.axisY, model.axisZ)

    fun initialState(): State = State()

    fun evaluate(
        state: State,
        sample: EntryOrientationSample,
    ): Pair<EntryDetectionVerdict?, State> {
        // Gate 1: source freshness/quality.
        if (!sample.fresh) {
            val firstTransition = state.sourceUnavailableSinceMs == null
            val episode = state.doorEpisode?.let { ep ->
                if (ep.interrupted) ep else ep.copy(interrupted = true)
            }
            val verdict = if (firstTransition) EntryDetectionVerdict.SourceUnavailable else null
            return verdict to state.copy(
                sourceUnavailableSinceMs = state.sourceUnavailableSinceMs ?: sample.timestampMs,
                recoveryHealthySinceMs = null,
                openStreakStartMs = null,
                closeStreakStartMs = null,
                doorEpisode = episode,
            )
        }

        val rel = EntryOrientationMath.relativeRotation(baseline, sample.quaternion)
        val swingDeg = EntryOrientationMath.swingResidualDeg(rel, axis)
        val twistSignedDeg = EntryOrientationMath.twistAroundAxisDeg(rel, axis)
        val angleDeg = abs(twistSignedDeg).coerceIn(0.0, 180.0)
        val wrongDirection = if (model.allowedDirection >= 0) twistSignedDeg < 0.0 else twistSignedDeg > 0.0

        // Recovery path while a source-unavailable health episode is open.
        if (state.sourceUnavailableSinceMs != null) {
            return evaluateDuringRecovery(state, swingDeg, wrongDirection, angleDeg, sample.timestampMs)
        }

        // Gates 2-3: hinge residual then allowed direction. Mount movement outranks any
        // door event, including an already-open episode.
        if (swingDeg > model.residualToleranceDeg || wrongDirection) {
            return mountMoved(state)
        }
        // Mount movement is terminal for the session until controlled recommissioning.
        if (state.mountMoved) return null to state

        return evaluateAngle(state, angleDeg, sample.timestampMs)
    }

    private fun evaluateDuringRecovery(
        state: State,
        swingDeg: Double,
        wrongDirection: Boolean,
        angleDeg: Double,
        timestampMs: Long,
    ): Pair<EntryDetectionVerdict?, State> {
        if (swingDeg > model.residualToleranceDeg || wrongDirection) {
            return mountMoved(
                state.copy(sourceUnavailableSinceMs = null, recoveryHealthySinceMs = null),
            )
        }
        if (angleDeg <= settings.closeThresholdDegrees.toDouble()) {
            val start = state.recoveryHealthySinceMs ?: timestampMs
            val elapsed = timestampMs - start
            if (elapsed >= RECOVERY_REQUIRED_MS) {
                val episode = state.doorEpisode
                return if (episode != null && episode.interrupted) {
                    EntryDetectionVerdict.DoorClosedConfirmed(episode.episodeId) to state.copy(
                        sourceUnavailableSinceMs = null,
                        recoveryHealthySinceMs = null,
                        doorEpisode = null,
                        openStreakStartMs = null,
                        closeStreakStartMs = null,
                    )
                } else {
                    EntryDetectionVerdict.SourceRecovered to state.copy(
                        sourceUnavailableSinceMs = null,
                        recoveryHealthySinceMs = null,
                    )
                }
            }
            return null to state.copy(recoveryHealthySinceMs = start)
        }
        // Above the close threshold during recovery: the window restarts.
        return null to state.copy(recoveryHealthySinceMs = null)
    }

    /**
     * Announced once per session, which is what "terminal" has always meant here and never
     * did. The guard for it sat one statement below the gate that fires it, so it could only
     * be reached by a sample that no longer tripped the gate — and a phone that has been
     * displaced stays displaced, so every sample after the first tripped it again.
     *
     * On the device that was fifteen full alerts in thirty seconds from a single incident,
     * each one a Telegram message and an SMS: the owner's phone bill and attention spent on
     * repeating a thing they had already been told, for as long as the session stayed armed.
     *
     * The state transition is unchanged and still terminal. Only the verdict is withheld,
     * because there is nothing new to say.
     */
    private fun mountMoved(state: State): Pair<EntryDetectionVerdict?, State> {
        val moved = state.copy(
            mountMoved = true,
            doorEpisode = state.doorEpisode?.let { it.copy(interrupted = true) },
            openStreakStartMs = null,
            closeStreakStartMs = null,
        )
        return if (state.mountMoved) null to moved else EntryDetectionVerdict.MountMoved to moved
    }

    private fun evaluateAngle(
        state: State,
        angleDeg: Double,
        timestampMs: Long,
    ): Pair<EntryDetectionVerdict?, State> {
        val thresholdDeg = settings.angleThresholdDegrees.toDouble()
        return when {
            angleDeg >= thresholdDeg -> evaluateOpenSide(state, angleDeg, timestampMs)
            angleDeg >= settings.closeThresholdDegrees.toDouble() ->
                // Hysteresis band: neither opening nor closing progress.
                null to state.copy(openStreakStartMs = null, closeStreakStartMs = null)
            else -> evaluateCloseSide(state, timestampMs)
        }
    }

    private fun evaluateOpenSide(
        state: State,
        angleDeg: Double,
        timestampMs: Long,
    ): Pair<EntryDetectionVerdict?, State> {
        val streakStart = state.openStreakStartMs ?: timestampMs
        val confirmed = timestampMs - streakStart >= settings.openConfirmationMs
        val episode = state.doorEpisode
        return when {
            !confirmed -> null to state.copy(
                openStreakStartMs = streakStart,
                closeStreakStartMs = null,
            )
            episode == null -> {
                val newEpisode = EntryDoorEpisode(
                    episodeId = "ENTRY-${state.episodeCounter + 1}",
                    openedAtMs = timestampMs,
                    peakAngleDeg = angleDeg,
                )
                EntryDetectionVerdict.DoorOpened(angleDeg, newEpisode.episodeId) to state.copy(
                    doorEpisode = newEpisode,
                    episodeCounter = state.episodeCounter + 1,
                    openStreakStartMs = streakStart,
                    closeStreakStartMs = null,
                )
            }
            angleDeg > episode.peakAngleDeg ->
                EntryDetectionVerdict.DoorStillOpen(angleDeg, episode.episodeId) to state.copy(
                    doorEpisode = episode.copy(peakAngleDeg = angleDeg),
                    openStreakStartMs = streakStart,
                )
            else -> null to state.copy(openStreakStartMs = streakStart)
        }
    }

    private fun evaluateCloseSide(
        state: State,
        timestampMs: Long,
    ): Pair<EntryDetectionVerdict?, State> {
        val streakStart = state.closeStreakStartMs ?: timestampMs
        val episode = state.doorEpisode
        val eligible = episode != null && !episode.interrupted
        val confirmed = timestampMs - streakStart >= settings.closeConfirmationMs
        return if (eligible && confirmed) {
            EntryDetectionVerdict.DoorClosedConfirmed(episode!!.episodeId) to state.copy(
                doorEpisode = null,
                closeStreakStartMs = streakStart,
                openStreakStartMs = null,
            )
        } else {
            null to state.copy(closeStreakStartMs = streakStart, openStreakStartMs = null)
        }
    }

    companion object {
        /** Fresh compatible evidence required to clear a health episode (spec section 7.2). */
        const val RECOVERY_REQUIRED_MS: Long = 5_000L
    }
}
