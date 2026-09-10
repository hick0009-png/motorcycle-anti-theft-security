package com.example.motorcycleantitheftsensor.protection

/**
 * Process-wide holder for one armed Entry session (spec sections 6-7).
 *
 * The commissioned hinge model is frozen at Arm; the relative-orientation baseline is
 * captured from the first fresh sample of the armed session and never rebaselined
 * while armed. Every later sample is evaluated through [EntryDetectionPolicy] with the
 * strict gate order. A sensor generation change (listener re-registration) resets the
 * debounce windows but keeps both baseline and model.
 */
class EntryArmedSessionController {

    private val lock = Any()
    private var settings: EntryProfileSettings? = null
    private var model: EntryHingeModel? = null
    private var policy: EntryDetectionPolicy? = null
    private var policyState: EntryDetectionPolicy.State? = null
    private var baseline: EntryQuaternion? = null

    /**
     * True while the baseline is still being settled inside the arming window and may be
     * re-captured by a later, steadier sample.
     *
     * The reference is meant to be the shut door, captured the instant the watch begins. But the
     * first *fresh* sample is not the first sample: the game rotation vector has to converge to
     * good accuracy first, which can take several seconds, and on the test device that first
     * fresh sample landed while the owner still had a hand on the door — freezing a half-open
     * pose in as "closed" for the whole session, past the reach of the drift rebaseline, which
     * only ever acts from inside the close band a wrong baseline never re-enters. So while the
     * coordinator is still counting down to armed, every fresh sample re-captures the baseline;
     * the pose in hand when the countdown ends is the one that is frozen. Nothing is lost by the
     * churn: the engine drops every verdict during the arming window regardless.
     */
    private var baselineProvisional: Boolean = false
    private var generation: Long = Long.MIN_VALUE

    /**
     * When the door has read continuously closed and still since, or null when it has not.
     *
     * The orientation sensor drifts on its own — a still phone's reported angle wanders a few
     * degrees an hour — and the baseline is frozen at Arm, so over a long armed session that
     * drift alone climbs past the open threshold and alarms a shut, untouched door. While the
     * door is confidently closed and nothing is happening, the current orientation *is* the
     * new closed reference, so re-capturing the baseline to it tracks the drift out. The guard
     * is what keeps it honest: it acts only from the clean closed state, never with an episode
     * open, a mount moved, or a source lost, and a real opening leaves the closed band within a
     * second — long before [DRIFT_REBASELINE_STABLE_MS] elapses — so this can never follow a
     * door that is actually opening.
     */
    private var closedStillSinceMs: Long? = null

    /**
     * When the sample stream last showed a sign of life, or when the watchdog first went
     * looking and found none.
     *
     * Not simply "when the last sample arrived": a session armed onto a source that never
     * delivers a single sample has no last sample, and that is the case most worth catching.
     * So the first silence check with nothing behind it starts the clock instead.
     */
    private var lastActivityAtMs: Long? = null

    /** Last orientation actually received, so a silence has something to be judged against. */
    private var lastQuaternion: EntryQuaternion? = null

    /** Whether an armed session that never received anything has already said so. */
    private var silentArmAnnounced: Boolean = false

    /**
     * Reference pose the motion check measures rotation away from, and when it was taken.
     *
     * Re-anchored whenever motion is found, and again whenever [DOOR_MOTION_WINDOW_MS] passes
     * without it, so a slow drift can never accumulate across an idle night into a rotation that
     * looks like a door.
     */
    private var motionRefQuaternion: EntryQuaternion? = null
    private var motionRefAtMs: Long? = null

    /**
     * When the orientation stream last turned far enough, fast enough, to prove the door
     * physically moved.
     *
     * The door watch is asked to corroborate its angle with movement, and the only signal that
     * could answer was the accelerometer — which a door barely troubles: it rotates the phone
     * about a hinge without accelerating it, so a smooth opening reads as little more than
     * gravity and the shake never arrives. The watch then refused every one of its own correct
     * verdicts and went silent on a door it was reading perfectly.
     *
     * Rotation over a short window answers the question the shake was asked: [DOOR_MOTION_MIN_DEG]
     * within [DOOR_MOTION_WINDOW_MS]. Drift, the false alarm corroboration exists to stop, moves
     * a few degrees an *hour* — thousandths of a degree inside that window — and sensor jitter on
     * a still phone is smaller still, so neither can reach it, while any real opening clears it
     * immediately.
     */
    @Volatile
    private var lastDoorMotionElapsedMs: Long? = null

    @Volatile
    private var liveAngleDeg: Double? = null

    /**
     * Live off-axis residual, the quantity the mount-moved gate judges. Exposed so the running
     * watch can be asked what a real door swing actually measures on this mounting, which is
     * how [MIN_RESIDUAL_TOLERANCE_DEG] is tuned from evidence rather than guessed.
     */
    @Volatile
    private var liveSwingResidualDeg: Double? = null

    /** Live signed twist about the commissioned axis; its sign is what the direction gate reads. */
    @Volatile
    private var liveTwistSignedDeg: Double? = null

    val isActive: Boolean
        get() = synchronized(lock) { model != null }

    /**
     * Arms a fresh session for [model]. Any previous baseline or debounce state is
     * discarded first, so re-arm always re-baselines.
     */
    fun begin(generation: Long, model: EntryHingeModel, settings: EntryProfileSettings) {
        synchronized(lock) {
            this.generation = generation
            this.model = model
            this.settings = settings
            this.baseline = null
            this.baselineProvisional = false
            this.policy = null
            this.policyState = null
            this.liveAngleDeg = null
            this.closedStillSinceMs = null
            this.lastActivityAtMs = null
            this.lastQuaternion = null
            this.silentArmAnnounced = false
            this.motionRefQuaternion = null
            this.motionRefAtMs = null
            this.lastDoorMotionElapsedMs = null
        }
    }

    /** Owner disarm or controlled stop: clears the runtime baseline and all state. */
    fun end() {
        synchronized(lock) {
            model = null
            settings = null
            policy = null
            policyState = null
            baseline = null
            baselineProvisional = false
            liveAngleDeg = null
            closedStillSinceMs = null
            lastActivityAtMs = null
            lastQuaternion = null
            silentArmAnnounced = false
            motionRefQuaternion = null
            motionRefAtMs = null
            lastDoorMotionElapsedMs = null
        }
    }

    /**
     * Feeds one orientation sample. Returns the verdicts produced by the sample —
     * empty while no session is active, while the baseline is still being captured,
     * or when the sample causes no transition.
     */
    fun onSample(
        sample: EntryOrientationSample,
        currentGeneration: Long,
        /**
         * Whether the coordinator is still in its arming countdown. While true the baseline is
         * only provisional and every fresh sample re-captures it, so a door moved during the
         * countdown cannot freeze itself in as the closed reference; see [baselineProvisional].
         * A caller that does not say is taken to be past arming, which is what every caller got
         * before this existed and what the controller's own unit proofs rely on.
         */
        arming: Boolean = false,
    ): List<EntryDetectionVerdict> {
        synchronized(lock) {
            val activeModel = model ?: return emptyList()
            lastActivityAtMs = sample.timestampMs
            lastQuaternion = sample.quaternion
            silentArmAnnounced = false
            if (sample.fresh) trackDoorMotion(sample)
            if (currentGeneration != generation) {
                // Listener re-registration: debounce windows restart from zero, and so does
                // the closed-still window — the sample stream had a gap, so nothing before it
                // can count toward a continuous-closed run. The baseline itself survives.
                generation = currentGeneration
                policyState = policyState?.copy(
                    openStreakStartMs = null,
                    closeStreakStartMs = null,
                    recoveryHealthySinceMs = null,
                )
                closedStillSinceMs = null
            }
            val currentBaseline = baseline
            if (currentBaseline == null) {
                if (!sample.fresh) return emptyList()
                baselineProvisional = arming
                return captureBaseline(sample, activeModel)
            }
            if (baselineProvisional) {
                if (arming) {
                    // Still inside the countdown: keep the reference on the latest fresh pose so a
                    // door moved mid-window does not freeze itself in as closed. Verdicts here are
                    // dropped by the engine anyway, so the churning window stays silent.
                    if (sample.fresh) captureBaseline(sample, activeModel)
                    return emptyList()
                }
                // Countdown over: the pose in hand is the closed reference from here on, and the
                // sample is evaluated against it like any other.
                baselineProvisional = false
            }
            val detection = policy ?: return emptyList()
            val axis = doubleArrayOf(activeModel.axisX, activeModel.axisY, activeModel.axisZ)
            val rel = EntryOrientationMath.relativeRotation(currentBaseline, sample.quaternion)
            liveAngleDeg = EntryOrientationMath.doorAngleDeltaDeg(rel, axis)
            liveSwingResidualDeg = EntryOrientationMath.swingResidualDeg(rel, axis)
            liveTwistSignedDeg = EntryOrientationMath.twistAroundAxisDeg(rel, axis)
            val (verdict, newState) = detection.evaluate(
                policyState ?: detection.initialState(),
                sample,
            )
            policyState = newState

            maybeRebaselineForDrift(newState, rel, axis, activeModel, sample)
            return listOfNotNull(verdict)
        }
    }

    /**
     * Records whether this sample proves the door physically moved; see [lastDoorMotionElapsedMs].
     * Called with [lock] held, on every fresh sample.
     */
    private fun trackDoorMotion(sample: EntryOrientationSample) {
        val ref = motionRefQuaternion
        val refAtMs = motionRefAtMs
        if (ref == null || refAtMs == null) {
            motionRefQuaternion = sample.quaternion
            motionRefAtMs = sample.timestampMs
            return
        }
        val movedDeg = EntryOrientationMath.totalRotationDeg(
            EntryOrientationMath.relativeRotation(ref, sample.quaternion),
        )
        if (movedDeg >= DOOR_MOTION_MIN_DEG) {
            lastDoorMotionElapsedMs = sample.timestampMs
            motionRefQuaternion = sample.quaternion
            motionRefAtMs = sample.timestampMs
            return
        }
        // Nothing that counts inside the window: re-anchor, so a slow creep can never add up
        // across hours into a rotation that would read as a door.
        if (sample.timestampMs - refAtMs >= DOOR_MOTION_WINDOW_MS) {
            motionRefQuaternion = sample.quaternion
            motionRefAtMs = sample.timestampMs
        }
    }

    /**
     * When the orientation stream last proved physical movement, on the sample clock; null when
     * it has not since the session began. Read by the runtime to stamp a door verdict as
     * self-corroborated.
     */
    fun lastDoorMotionElapsedMs(): Long? = lastDoorMotionElapsedMs

    /**
     * Freezes the current sample as the closed reference and rebuilds the detection policy and
     * state around it, returning the verdict the capture itself produces (a mount read as
     * unrecognized, or nothing). Called with [lock] held, on the first fresh sample and again on
     * each fresh sample while the baseline is still provisional inside the arming window.
     */
    private fun captureBaseline(
        sample: EntryOrientationSample,
        activeModel: EntryHingeModel,
    ): List<EntryDetectionVerdict> {
        val frozen = EntryOrientationMath.canonicalizeSign(sample.quaternion)
        baseline = frozen
        val detection = EntryDetectionPolicy(
            baseline = frozen,
            model = activeModel,
            settings = settings ?: EntryProfileSettings(),
        )
        policy = detection
        val unrecognized = mountUnrecognized(activeModel, sample.quaternion)
        policyState = if (unrecognized) {
            detection.initialState().copy(
                mountMoved = true,
                mountUnrecognized = true,
                // Stamped so the repeat is timed from here. This first one is usually lost — the
                // session begins inside the arming window, where the engine drops everything —
                // and the repeat is what actually reaches anyone.
                mountUnrecognizedAnnouncedAtMs = sample.timestampMs,
            )
        } else {
            detection.initialState()
        }
        liveAngleDeg = 0.0
        return if (unrecognized) {
            listOf(EntryDetectionVerdict.MountUnrecognized)
        } else {
            emptyList()
        }
    }

    /**
     * Whether the phone is mounted somewhere other than where the model was measured.
     *
     * The commissioned hinge axis is a direction in the device's own frame, so it describes
     * this door only while the phone sits the way it sat during commissioning. Remount it and
     * the axis quietly describes nothing — a mount signature was supposed to catch that and
     * was a fixed placeholder string, equal to itself on every phone in every position, so
     * nothing was ever caught. It was found later instead, by a door verdict that was wrong.
     *
     * The first fresh sample of an armed session is the earliest moment a live pose exists,
     * and this is the check that moment is for. What it can see is tilt, which is most of
     * what changes when a phone is moved; what it cannot see is heading, because the game
     * rotation vector has no compass and its yaw means nothing between sessions. The
     * tolerance is generous on purpose — a phone re-seated in its own cradle sits a little
     * differently every time, and an alarm that cried wolf at every arm would be turned off.
     *
     * A model commissioned before poses were recorded has none, and is taken at its word.
     */
    private fun mountUnrecognized(model: EntryHingeModel, quaternion: EntryQuaternion): Boolean {
        val commissioned = model.mountUp ?: return false
        val current = EntryOrientationMath.deviceUpVector(quaternion)
        return EntryOrientationMath.angleBetweenDeg(commissioned, current) > MOUNT_POSE_TOLERANCE_DEG
    }

    /**
     * Tracks how long the door has read closed and still, and re-captures the baseline to the
     * current orientation once that has held for [DRIFT_REBASELINE_STABLE_MS], slewing the
     * frozen reference along with sensor drift. Called only with an active baseline; every
     * caller holds [lock].
     */
    private fun maybeRebaselineForDrift(
        state: EntryDetectionPolicy.State,
        rel: EntryQuaternion,
        axis: DoubleArray,
        model: EntryHingeModel,
        sample: EntryOrientationSample,
    ) {
        if (!sample.fresh || !isClosedAndStill(state, rel, axis, model)) {
            closedStillSinceMs = null
            return
        }
        val since = closedStillSinceMs ?: sample.timestampMs
        if (sample.timestampMs - since < DRIFT_REBASELINE_STABLE_MS) {
            closedStillSinceMs = since
            return
        }
        // Held closed and still long enough: the current orientation is the new closed zero.
        val rebased = EntryOrientationMath.canonicalizeSign(sample.quaternion)
        val fresh = EntryDetectionPolicy(rebased, model, settings ?: EntryProfileSettings())
        baseline = rebased
        policy = fresh
        // Nothing is open in the closed-still state, so the only thing worth carrying across the
        // rebuild is the episode counter, which keeps episode ids unique for the whole session.
        policyState = fresh.initialState().copy(episodeCounter = state.episodeCounter)
        closedStillSinceMs = sample.timestampMs
        liveAngleDeg = 0.0
    }

    /**
     * The clean closed state the drift rebaseline is allowed to act from: the door within the
     * close band and on-axis, with no episode open, no mount displacement, and no source-loss
     * health episode in progress. From anywhere else the current orientation is not a trustworthy
     * closed reference.
     */
    private fun isClosedAndStill(
        state: EntryDetectionPolicy.State,
        rel: EntryQuaternion,
        axis: DoubleArray,
        model: EntryHingeModel,
    ): Boolean {
        if (state.doorEpisode != null || state.mountMoved || state.sourceUnavailableSinceMs != null) {
            return false
        }
        val closeThresholdDeg = (settings?.closeThresholdDegrees ?: return false).toDouble()
        val angleDeg = EntryOrientationMath.doorAngleDeltaDeg(rel, axis)
        val swingDeg = EntryOrientationMath.swingResidualDeg(rel, axis)
        return angleDeg <= closeThresholdDeg && swingDeg <= model.effectiveResidualToleranceDeg
    }

    /**
     * Asks what the sample stream has stopped being able to say.
     *
     * The freshness gate has always been driven by a sample: it notices a gap when something
     * finally arrives *after* the gap and is judged late. That catches a stuttering source and
     * is blind to the one that matters most — a stream that stops dead. No sample means no
     * evaluation, no evaluation means no verdict, and a door watch with nothing to report
     * looks exactly like a door that never opened. On a phone whose vendor freezes background
     * apps, which is most of them, that is the difference between a watch and the appearance
     * of one.
     *
     * So something outside the stream has to ask. Called on a timer while the session is
     * armed: if nothing has arrived for [maxGapMs], the last known orientation is judged again
     * as a stale sample, which is what it has become, and the detection policy produces the
     * same `SourceUnavailable` it would have produced had a late sample carried the news. A
     * session that never received anything at all — a listener that failed to register, a
     * source the phone refused — has no orientation to judge and says so once directly.
     *
     * @return verdicts produced by the silence; empty while the session is healthy.
     */
    fun onSilence(
        nowMs: Long,
        maxGapMs: Long = EntrySourceFreshnessTracker.DEFAULT_MAX_GAP_MS,
    ): List<EntryDetectionVerdict> {
        synchronized(lock) {
            if (model == null) return emptyList()
            val since = lastActivityAtMs
            if (since == null) {
                lastActivityAtMs = nowMs
                return emptyList()
            }
            if (nowMs - since <= maxGapMs) return emptyList()
            closedStillSinceMs = null
            val detection = policy
            val lastKnown = lastQuaternion
            if (detection == null || lastKnown == null) {
                if (silentArmAnnounced) return emptyList()
                silentArmAnnounced = true
                return listOf(EntryDetectionVerdict.SourceUnavailable)
            }
            val (verdict, newState) = detection.evaluate(
                policyState ?: detection.initialState(),
                EntryOrientationSample(timestampMs = nowMs, quaternion = lastKnown, fresh = false),
            )
            policyState = newState
            return listOfNotNull(verdict)
        }
    }

    /** Live relative door angle in degrees for the UI; null with no active session/baseline. */
    fun liveAngleDeg(): Double? = liveAngleDeg

    /** Live off-axis residual in degrees; null with no active session/baseline. */
    fun liveSwingResidualDeg(): Double? = liveSwingResidualDeg

    /** Live signed twist about the hinge axis; null with no active session/baseline. */
    fun liveTwistSignedDeg(): Double? = liveTwistSignedDeg

    /** The residual tolerance this session actually enforces; null when not armed. */
    fun enforcedResidualToleranceDeg(): Double? =
        synchronized(lock) { model?.effectiveResidualToleranceDeg }

    companion object {
        /**
         * How long the door must read continuously closed and still before the baseline is
         * re-captured to the current orientation. Two minutes is far longer than any real door
         * spends crossing the close band, so a genuine opening always escapes it first; it is
         * also short enough that even a badly drifting sensor moves only a fraction of the close
         * band between re-captures, so the tracked-out drift never re-enters the open threshold.
         */
        const val DRIFT_REBASELINE_STABLE_MS: Long = 120_000L

        /**
         * How far the phone's tilt may differ from the commissioned one before the model is
         * treated as describing some other mounting. Wide enough that re-seating a phone in
         * its own cradle never trips it, narrow enough that a different cradle, a different
         * angle or a different door does.
         */
        const val MOUNT_POSE_TOLERANCE_DEG: Double = 30.0

        /**
         * Rotation within [DOOR_MOTION_WINDOW_MS] that proves the door physically moved.
         *
         * Two degrees in half a second is four degrees a second. The drift this stands against
         * runs at a few degrees an *hour* — about a thousandth of a degree in the same window —
         * and the jitter of a still phone's reported orientation is smaller again, so neither
         * reaches it. Any real opening passes it in the first moments of the swing.
         */
        const val DOOR_MOTION_MIN_DEG: Double = 2.0
        const val DOOR_MOTION_WINDOW_MS: Long = 500L
    }
}
