package com.example.motorcycleantitheftsensor.protection

/**
 * Process-wide holder for one armed Power Guard session (parent spec section 4.3).
 *
 * The commissioned witness model is frozen at Arm; every charging/witness observation
 * is evaluated through [PowerCompositeArbiter] with the frozen model and settings. A
 * sensor generation change (listener re-registration) resets the debounce windows but
 * keeps the frozen model, so an open episode and its ordinal history survive.
 */
class PowerArmedSessionController {

    private val lock = Any()
    private var model: PowerWitnessModel? = null
    private var settings: PowerProfileSettings? = null
    private var arbiter: PowerCompositeArbiter? = null
    private var arbiterState: PowerCompositeArbiter.State? = null
    private var generation: Long = Long.MIN_VALUE

    val isActive: Boolean
        get() = synchronized(lock) { model != null }

    /**
     * Arms a fresh session for [model]. Any previous episode or debounce state is
     * discarded first, so re-arm always starts a clean episode counter.
     */
    fun begin(generation: Long, model: PowerWitnessModel, settings: PowerProfileSettings) {
        synchronized(lock) {
            this.generation = generation
            this.model = model
            this.settings = settings
            val evaluator = PowerCompositeArbiter(model, settings)
            this.arbiter = evaluator
            this.arbiterState = evaluator.initialState()
        }
    }

    /** Owner disarm or controlled stop: clears the runtime arbiter and all state. */
    fun end() {
        synchronized(lock) {
            model = null
            settings = null
            arbiter = null
            arbiterState = null
        }
    }

    /**
     * Feeds one composite charging/witness sample. Returns the verdicts produced by the
     * sample — empty while no session is active (no compatible calibration exists, so no
     * outage claim is possible) or when the sample causes no transition.
     */
    fun onSample(
        sample: PowerSignalSample,
        currentGeneration: Long,
    ): List<PowerArbiterVerdict> {
        synchronized(lock) {
            val activeModel = model ?: return emptyList()
            if (currentGeneration != generation) {
                // Listener re-registration: debounce windows restart from zero, but the
                // frozen model and any open episode survive.
                generation = currentGeneration
                arbiterState = arbiterState?.copy(
                    streakStartMs = null,
                    streakFired = false,
                    healthySinceMs = null,
                )
            }
            val evaluator = arbiter ?: return emptyList()
            val (verdict, newState) = evaluator.evaluate(
                arbiterState ?: evaluator.initialState(),
                sample,
            )
            arbiterState = newState
            return listOfNotNull(verdict)
        }
    }
}
