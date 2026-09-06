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

    @Volatile
    private var liveAngleDeg: Double? = null

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
            this.policy = null
            this.policyState = null
            this.liveAngleDeg = null
            this.closedStillSinceMs = null
            this.lastActivityAtMs = null
            this.lastQuaternion = null
            this.silentArmAnnounced = false
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
            liveAngleDeg = null
            closedStillSinceMs = null
            lastActivityAtMs = null
            lastQuaternion = null
            silentArmAnnounced = false
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
    ): List<EntryDetectionVerdict> {
        synchronized(lock) {
            val activeModel = model ?: return emptyList()
            lastActivityAtMs = sample.timestampMs
            lastQuaternion = sample.quaternion
            silentArmAnnounced = false
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
                val frozen = EntryOrientationMath.canonicalizeSign(sample.quaternion)
                baseline = frozen
                val detection = EntryDetectionPolicy(
                    baseline = frozen,
                    model = activeModel,
                    settings = settings ?: EntryProfileSettings(),
                )
                policy = detection
                policyState = detection.initialState()
                liveAngleDeg = 0.0
                return emptyList()
            }
            val detection = policy ?: return emptyList()
            val axis = doubleArrayOf(activeModel.axisX, activeModel.axisY, activeModel.axisZ)
            val rel = EntryOrientationMath.relativeRotation(currentBaseline, sample.quaternion)
            liveAngleDeg = EntryOrientationMath.doorAngleDeltaDeg(rel, axis)
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
        return angleDeg <= closeThresholdDeg && swingDeg <= model.residualToleranceDeg
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

    companion object {
        /**
         * How long the door must read continuously closed and still before the baseline is
         * re-captured to the current orientation. Two minutes is far longer than any real door
         * spends crossing the close band, so a genuine opening always escapes it first; it is
         * also short enough that even a badly drifting sensor moves only a fraction of the close
         * band between re-captures, so the tracked-out drift never re-enters the open threshold.
         */
        const val DRIFT_REBASELINE_STABLE_MS: Long = 120_000L
    }
}
