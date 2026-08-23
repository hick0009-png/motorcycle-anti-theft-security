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
            if (currentGeneration != generation) {
                // Listener re-registration: debounce windows restart from zero, but the
                // frozen baseline survives (no auto-rebaseline while armed).
                generation = currentGeneration
                policyState = policyState?.copy(
                    openStreakStartMs = null,
                    closeStreakStartMs = null,
                    recoveryHealthySinceMs = null,
                )
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
            return listOfNotNull(verdict)
        }
    }

    /** Live relative door angle in degrees for the UI; null with no active session/baseline. */
    fun liveAngleDeg(): Double? = liveAngleDeg
}
