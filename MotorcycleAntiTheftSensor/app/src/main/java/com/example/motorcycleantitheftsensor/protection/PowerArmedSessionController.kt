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
    private var armReferenceStartMs: Long? = null
    private val armReferenceSamples = mutableListOf<Double>()
    private var armReferenceLux: Double? = null
    private var activeWitnessModel: PowerWitnessModel? = null

    val isActive: Boolean
        get() = synchronized(lock) { model != null }

    /**
     * Arms a fresh session for [model]. Any previous episode or debounce state is
     * discarded first, so re-arm always starts a clean episode counter.
     */
    fun begin(
        generation: Long,
        model: PowerWitnessModel,
        settings: PowerProfileSettings,
        resumedSemantic: PowerCompositeArbiter.SemanticState? = null,
    ) {
        synchronized(lock) {
            this.generation = generation
            this.model = model
            this.settings = settings
            this.armReferenceStartMs = null
            this.armReferenceSamples.clear()
            this.armReferenceLux = null
            if (resumedSemantic == null) {
                this.arbiter = null
                this.arbiterState = null
                this.activeWitnessModel = null
            } else {
                val evaluator = PowerCompositeArbiter(model, settings)
                this.arbiter = evaluator
                this.arbiterState = evaluator.initialState().copy(
                    episodeId = RESTORED_EPISODE_ID,
                    episodeCounter = 1,
                    currentSemantic = resumedSemantic,
                    streakFired = true,
                    confirmedLossOpen = resumedSemantic == PowerCompositeArbiter.SemanticState.DUAL_LOST,
                    ownerVisibleOpening = true,
                    openedAs = resumedSemantic,
                )
                this.activeWitnessModel = model
            }
        }
    }

    /** Owner disarm or controlled stop: clears the runtime arbiter and all state. */
    fun end() {
        synchronized(lock) {
            model = null
            settings = null
            arbiter = null
            arbiterState = null
            armReferenceStartMs = null
            armReferenceSamples.clear()
            armReferenceLux = null
            activeWitnessModel = null
        }
    }

    /** The thresholds currently used by the armed arbiter, or null while Arm is calibrating. */
    fun activeWitnessModel(): PowerWitnessModel? = synchronized(lock) { activeWitnessModel }

    /**
     * Whether the arbiter's last conclusive reading found the witness lamp lit, or null
     * when no armed session has reached a conclusion yet.
     *
     * The owner asking "/status" in this mode is asking exactly one question — is the power
     * still on — and the arbiter is the only thing that knows, because a raw lux value means
     * nothing without the commissioned bands it is judged against.
     */
    fun witnessLit(): Boolean? = synchronized(lock) { arbiterState?.lastConclusiveWitnessLit }

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
                if (arbiter == null) {
                    armReferenceStartMs = null
                    armReferenceSamples.clear()
                } else {
                    val evaluator = requireNotNull(arbiter)
                    arbiterState = arbiterState?.let(evaluator::invalidateEvidenceContinuity)
                }
            }
            if (arbiter == null) return captureArmReference(activeModel, sample)
            val evaluator = arbiter ?: return emptyList()
            val (verdict, newState) = evaluator.evaluate(
                arbiterState ?: evaluator.initialState(),
                sample,
            )
            arbiterState = newState
            adaptArmReference(activeModel, sample, newState)
            return listOfNotNull(verdict)
        }
    }

    /**
     * The existing 10-second ARMING state is a real observation window: no power
     * verdict is emitted until a current on-lamp reference has been captured.
     */
    private fun captureArmReference(
        commissionedModel: PowerWitnessModel,
        sample: PowerSignalSample,
    ): List<PowerArbiterVerdict> {
        if (!sample.fresh || sample.witnessLux == null) return emptyList()
        val start = armReferenceStartMs
        if (start == null) {
            armReferenceStartMs = sample.timestampMs
            armReferenceSamples += sample.witnessLux
            return emptyList()
        }
        armReferenceSamples += sample.witnessLux
        if (sample.timestampMs - start < ARM_REFERENCE_WINDOW_MS) return emptyList()

        val reference = armReferenceSamples.average()
        armReferenceLux = reference
        val activeModel = commissionedModel.scaledForLitReference(reference)
        activeWitnessModel = activeModel
        val evaluator = PowerCompositeArbiter(activeModel, requireNotNull(settings))
        arbiter = evaluator
        val (verdict, next) = evaluator.evaluate(evaluator.initialState(), sample)
        arbiterState = next
        adaptArmReference(commissionedModel, sample, next)
        return listOfNotNull(verdict)
    }

    /** Slowly follows normal ambient drift, only while both signals are healthy. */
    private fun adaptArmReference(
        commissionedModel: PowerWitnessModel,
        sample: PowerSignalSample,
        state: PowerCompositeArbiter.State,
    ) {
        val witnessLux = sample.witnessLux ?: return
        val evaluatingModel = activeWitnessModel ?: commissionedModel
        if (
            state.currentSemantic != PowerCompositeArbiter.SemanticState.HEALTHY_DUAL ||
            !sample.fresh || sample.chargingConnected != true ||
            witnessLux < evaluatingModel.witnessLitThresholdLux
        ) return
        val current = armReferenceLux ?: return
        val adapted = current + (witnessLux - current) * AMBIENT_ADAPTATION_FRACTION
        if (adapted == current) return
        armReferenceLux = adapted
        val activeModel = commissionedModel.scaledForLitReference(adapted)
        activeWitnessModel = activeModel
        arbiter = PowerCompositeArbiter(activeModel, requireNotNull(settings))
    }

    /**
     * Returns the elapsed-realtime deadline at which the current stable condition must be
     * re-evaluated. Android light sensors may be on-change sources and therefore may not
     * deliver another callback while a dark or lit value remains stable.
     */
    fun nextConfirmationAtMs(): Long? = synchronized(lock) {
        val activeSettings = settings ?: return@synchronized null
        if (arbiter == null) return@synchronized armReferenceStartMs?.plus(ARM_REFERENCE_WINDOW_MS)
        val state = arbiterState ?: return@synchronized null
        when {
            state.currentSemantic == PowerCompositeArbiter.SemanticState.HEALTHY_DUAL &&
                state.episodeId != null ->
                state.healthySinceMs?.plus(activeSettings.recoveryConfirmationMs)

            state.currentSemantic != null &&
                state.currentSemantic != PowerCompositeArbiter.SemanticState.HEALTHY_DUAL &&
                !state.streakFired ->
                state.streakStartMs?.plus(activeSettings.lossConfirmationMs)

            else -> null
        }
    }

    private companion object {
        const val ARM_REFERENCE_WINDOW_MS = 10_000L
        const val AMBIENT_ADAPTATION_FRACTION = 0.02
        const val RESTORED_EPISODE_ID = "POWER-RESTORED"
    }
}
