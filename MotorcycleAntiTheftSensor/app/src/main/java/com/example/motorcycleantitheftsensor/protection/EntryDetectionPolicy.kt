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

    /**
     * The displaced mount reads compatible with the commissioned geometry again, and has
     * for long enough to be believed. Emitted once, on the way back to normal detection.
     */
    data object MountRestored : EntryDetectionVerdict

    /**
     * The phone is not mounted the way the model was measured, said once as the watch starts.
     * Emitted by [EntryArmedSessionController], which is where a live pose first exists.
     */
    data object MountUnrecognized : EntryDetectionVerdict
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
        /** Since when a displaced mount has read compatible again; see [evaluateMountRestore]. */
        val mountRestoreHealthySinceMs: Long? = null,
        /** When the owner was last told the source was gone; see [mayAnnounceSourceLoss]. */
        val sourceLossAnnouncedAtMs: Long? = null,
        /** Whether the loss currently in progress was announced, so its recovery may be. */
        val sourceLossAnnounced: Boolean = false,
        /**
         * Orientation a displaced phone came to rest at, and when it was taken.
         *
         * Not a door zero and never used as one — the commissioned axis no longer describes
         * this geometry. It is only a reference for the one question still worth asking while
         * displaced: has the phone been moved *again*.
         */
        val displacedAnchor: EntryQuaternion? = null,
        val displacedAnchorSinceMs: Long? = null,
        /**
         * Whether the phone's pose never matched the commissioned one to begin with, rather
         * than having been displaced away from it. A distinction with real consequences: see
         * [evaluateWhileDisplaced].
         */
        val mountUnrecognized: Boolean = false,
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
            val announce = firstTransition && mayAnnounceSourceLoss(state, sample.timestampMs)
            val verdict = if (announce) EntryDetectionVerdict.SourceUnavailable else null
            return verdict to state.copy(
                sourceUnavailableSinceMs = state.sourceUnavailableSinceMs ?: sample.timestampMs,
                sourceLossAnnouncedAtMs = if (announce) sample.timestampMs else state.sourceLossAnnouncedAtMs,
                sourceLossAnnounced = if (firstTransition) announce else state.sourceLossAnnounced,
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

        // A session already displaced is judged by its own rules; the gates below describe a
        // geometry it no longer has.
        if (state.mountMoved) {
            return evaluateWhileDisplaced(state, swingDeg, wrongDirection, angleDeg, sample)
        }

        // Gates 2-3: hinge residual then allowed direction. Mount movement outranks any
        // door event, including an already-open episode.
        if (swingDeg > model.residualToleranceDeg || wrongDirection) {
            return mountMoved(state)
        }

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
                    // A recovery from a loss nobody was told about is not news either, and
                    // announcing it alone would be the strangest message of all.
                    val verdict = if (state.sourceLossAnnounced) {
                        EntryDetectionVerdict.SourceRecovered
                    } else {
                        null
                    }
                    verdict to state.copy(
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
     * Only the verdict is withheld, because there is nothing new to say. What happens after
     * it is [evaluateWhileDisplaced], which is where this stopped being terminal.
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

    /**
     * What a displaced watch does instead of nothing.
     *
     * A phone whose residual has left the hinge tolerance cannot be read as a door angle any
     * more: the commissioned axis describes a geometry that has moved. Refusing to guess an
     * angle is right. Going silent for the rest of the armed session was not, and it made one
     * shove the whole attack — bump the phone, absorb the single critical alert, and every
     * door opening after it went unwatched until the owner came back and re-armed by hand,
     * which on a sleeping owner is the entire night. The worst moment for a burglar alarm to
     * go blind is the moment right after somebody touches it.
     *
     * So the session keeps working, on the two things still true after a mount move:
     *
     * - **It can come back.** A knock, a gust, or a bike settling displaces the phone and
     *   leaves it where it was. When the residual is inside tolerance, the direction is
     *   allowed and the door reads shut, and all three hold for [MOUNT_RESTORE_REQUIRED_MS],
     *   the commissioned geometry describes this door again and normal detection resumes.
     * - **It can be moved again.** While it stays displaced the current orientation is still
     *   an anchor to measure against, even though it is not a door zero. Once the phone has
     *   come to rest, [DISPLACED_MOVEMENT_DEG] of further rotation off that anchor is a
     *   second displacement and is said out loud, because somebody is still handling it.
     */
    private fun evaluateWhileDisplaced(
        state: State,
        swingDeg: Double,
        wrongDirection: Boolean,
        angleDeg: Double,
        sample: EntryOrientationSample,
    ): Pair<EntryDetectionVerdict?, State> {
        val timestampMs = sample.timestampMs
        // A pose that never matched has nothing to come back to. The residual is measured
        // from a baseline taken at that very pose, so it reads perfect immediately and the
        // restore below would declare the mount good five seconds into every armed session
        // — which is the opposite of what was just discovered about it.
        val backOnAxis = !state.mountUnrecognized &&
            swingDeg <= model.residualToleranceDeg &&
            !wrongDirection &&
            angleDeg <= settings.closeThresholdDegrees.toDouble()
        if (backOnAxis) return evaluateMountRestore(state, timestampMs)

        val cleared = state.copy(mountRestoreHealthySinceMs = null)
        val anchor = state.displacedAnchor
            ?: return null to cleared.copy(
                displacedAnchor = sample.quaternion,
                displacedAnchorSinceMs = timestampMs,
            )
        val movedDeg = EntryOrientationMath.totalRotationDeg(
            EntryOrientationMath.relativeRotation(anchor, sample.quaternion),
        )
        val anchoredSinceMs = state.displacedAnchorSinceMs ?: timestampMs
        return when {
            // Still coming to rest: follow the phone rather than measure against a reading
            // taken mid-swing, which would report the one displacement over and over.
            timestampMs - anchoredSinceMs < DISPLACED_SETTLE_MS ->
                if (movedDeg > DISPLACED_QUIET_DEG) {
                    null to cleared.copy(
                        displacedAnchor = sample.quaternion,
                        displacedAnchorSinceMs = timestampMs,
                    )
                } else {
                    null to cleared
                }
            movedDeg >= DISPLACED_MOVEMENT_DEG -> EntryDetectionVerdict.MountMoved to cleared.copy(
                displacedAnchor = sample.quaternion,
                displacedAnchorSinceMs = timestampMs,
            )
            else -> null to cleared
        }
    }

    /**
     * The way back from a displacement, held to the same standard as the source-loss recovery
     * beside it: sustained compatible evidence, never one good sample. A door episode that the
     * displacement interrupted resolves here as the confirmed close it has become.
     */
    private fun evaluateMountRestore(
        state: State,
        timestampMs: Long,
    ): Pair<EntryDetectionVerdict?, State> {
        val since = state.mountRestoreHealthySinceMs ?: timestampMs
        if (timestampMs - since < MOUNT_RESTORE_REQUIRED_MS) {
            return null to state.copy(mountRestoreHealthySinceMs = since)
        }
        val restored = state.copy(
            mountMoved = false,
            mountRestoreHealthySinceMs = null,
            displacedAnchor = null,
            displacedAnchorSinceMs = null,
            openStreakStartMs = null,
            closeStreakStartMs = null,
        )
        val episode = state.doorEpisode
        return if (episode != null) {
            EntryDetectionVerdict.DoorClosedConfirmed(episode.episodeId) to
                restored.copy(doorEpisode = null)
        } else {
            EntryDetectionVerdict.MountRestored to restored
        }
    }

    /**
     * Whether a fresh loss of the source is worth telling the owner about again.
     *
     * A phone that freezes background apps — which is most of them, and is exactly the phone
     * this gate was built for — does not lose the sensor once. It loses it for twenty seconds
     * every few minutes, all night. Each of those was a full incident: a Telegram message, an
     * SMS, and the same sentence the owner had already read, until the source came back for
     * good or the ceiling stopped it. The owner learns nothing from the ninth telling that
     * they did not learn from the first.
     *
     * So the fact is said, and then not said again for [SOURCE_LOSS_REANNOUNCE_MS]. What the
     * silence costs is only the message: the health episode still opens, door evidence is
     * still marked interrupted, recovery is still made to prove itself, and the black box
     * still counts the samples that did not arrive — a gap in the record is a gap whether or
     * not anybody was texted about it.
     */
    private fun mayAnnounceSourceLoss(state: State, timestampMs: Long): Boolean {
        val last = state.sourceLossAnnouncedAtMs ?: return true
        return timestampMs - last >= SOURCE_LOSS_REANNOUNCE_MS
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

        /**
         * How long before a source that keeps dropping out may say so again.
         *
         * Long enough that a phone flapping every few minutes is one message an hour rather
         * than one an outage, and short enough that a fresh loss hours later still arrives as
         * its own news.
         */
        const val SOURCE_LOSS_REANNOUNCE_MS: Long = 600_000L

        /**
         * Compatible evidence required before a displaced mount is trusted again. The same
         * five seconds the source-loss recovery asks for, for the same reason: one good
         * sample off a phone that is being handled proves nothing.
         */
        const val MOUNT_RESTORE_REQUIRED_MS: Long = 5_000L

        /** How long a displaced phone must hold still before its anchor is measured from. */
        const val DISPLACED_SETTLE_MS: Long = 5_000L

        /** Small enough to be a displaced phone still settling rather than a new event. */
        const val DISPLACED_QUIET_DEG: Double = 3.0

        /**
         * Rotation off a settled displaced anchor that counts as being moved again. Well
         * clear of the few degrees a resting phone wanders, and far below what handling one
         * produces, so it reports hands and not noise.
         */
        const val DISPLACED_MOVEMENT_DEG: Double = 10.0
    }
}
