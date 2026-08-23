package com.example.motorcycleantitheftsensor.protection

/**
 * Timestamped ambient-light reading used by Power Guard witness commissioning and
 * armed-session witness evaluation. `fresh = false` marks a stale/unavailable source.
 */
data class PowerWitnessSample(
    val lux: Double,
    val timestampMs: Long,
    val fresh: Boolean,
)

/**
 * Commissioned witness-lamp evidence for one physical setup (parent spec section
 * 4.3): the dark/lit lux ranges the phone actually observes through the hood, the
 * guard band that must separate them, and every field whose change invalidates the
 * commissioning.
 */
data class PowerWitnessModel(
    val darkMinLux: Double,
    val darkMaxLux: Double,
    val litMinLux: Double,
    val litMaxLux: Double,
    val guardBandLux: Double,
    val algorithmVersion: Int,
    val sensorIdentity: String,
    val hoodSignature: String,
)

/**
 * Pure state machine for Power Guard witness commissioning (parent spec section
 * 4.3 "Physical setup and readiness"): with the charger connected, the guided lamp
 * off/on cycle records separate dark and lit sample windows. Acceptance requires
 * both windows continuous, stable within [maxRangeSpanLux], and separated by at
 * least [guardBandLux]; a bright value alone is never proof the phone sees the lamp.
 */
class PowerWitnessCommissioningPolicy(
    private val windowDurationMs: Long,
    private val maxSampleGapMs: Long,
    private val maxRangeSpanLux: Double,
    private val guardBandLux: Double,
    private val sensorIdentity: String,
    private val hoodSignature: String,
) {

    enum class Phase { IDLE, DARK_WINDOW, LIT_WINDOW, COMMISSIONED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val windowStartMs: Long? = null,
        val lastSampleMs: Long? = null,
        val windowMinLux: Double? = null,
        val windowMaxLux: Double? = null,
        val darkMinLux: Double? = null,
        val darkMaxLux: Double? = null,
        val model: PowerWitnessModel? = null,
        val rejectionReason: String? = null,
    )

    fun start(): State = State(phase = Phase.DARK_WINDOW)

    fun onSample(state: State, sample: PowerWitnessSample): State = when (state.phase) {
        Phase.DARK_WINDOW -> handleWindowSample(state, sample, litPhase = false)
        Phase.LIT_WINDOW -> handleWindowSample(state, sample, litPhase = true)
        else -> state
    }

    private fun handleWindowSample(
        state: State,
        sample: PowerWitnessSample,
        litPhase: Boolean,
    ): State {
        // Stale evidence can never advance a window; restart it so continuity is honest.
        if (!sample.fresh) return resetWindow(state)
        val start = state.windowStartMs
        val last = state.lastSampleMs
        if (start == null || (last != null && sample.timestampMs - last > maxSampleGapMs)) {
            return beginWindow(state, sample)
        }
        val min = minOf(state.windowMinLux ?: sample.lux, sample.lux)
        val max = maxOf(state.windowMaxLux ?: sample.lux, sample.lux)
        if (max - min > maxRangeSpanLux) {
            // Excessive variance: the current window restarts from this sample.
            return beginWindow(state, sample)
        }
        val updated = state.copy(
            windowStartMs = start,
            lastSampleMs = sample.timestampMs,
            windowMinLux = min,
            windowMaxLux = max,
        )
        if (sample.timestampMs - start < windowDurationMs) return updated
        return if (!litPhase) {
            updated.copy(
                phase = Phase.LIT_WINDOW,
                darkMinLux = min,
                darkMaxLux = max,
                windowStartMs = null,
                lastSampleMs = null,
                windowMinLux = null,
                windowMaxLux = null,
            )
        } else {
            finishLitWindow(updated, darkMin = state.darkMinLux!!, darkMax = state.darkMaxLux!!, litMin = min, litMax = max)
        }
    }

    private fun beginWindow(state: State, sample: PowerWitnessSample): State = state.copy(
        windowStartMs = sample.timestampMs,
        lastSampleMs = sample.timestampMs,
        windowMinLux = sample.lux,
        windowMaxLux = sample.lux,
    )

    private fun resetWindow(state: State): State = state.copy(
        windowStartMs = null,
        lastSampleMs = null,
        windowMinLux = null,
        windowMaxLux = null,
    )

    private fun finishLitWindow(
        state: State,
        darkMin: Double,
        darkMax: Double,
        litMin: Double,
        litMax: Double,
    ): State {
        if (litMin - darkMax < guardBandLux) {
            // Ranges not separated by the safe guard band: full retry from darkness.
            return State(phase = Phase.DARK_WINDOW, rejectionReason = REJECTION_NOT_SEPARATED)
        }
        return State(
            phase = Phase.COMMISSIONED,
            model = PowerWitnessModel(
                darkMinLux = darkMin,
                darkMaxLux = darkMax,
                litMinLux = litMin,
                litMaxLux = litMax,
                guardBandLux = guardBandLux,
                algorithmVersion = ALGORITHM_VERSION,
                sensorIdentity = sensorIdentity,
                hoodSignature = hoodSignature,
            ),
        )
    }

    data class CommissioningContext(
        val sensorIdentity: String,
        val hoodSignature: String,
        val algorithmVersion: Int,
        val powerUseContinuous: Boolean,
    )

    companion object {
        const val ALGORITHM_VERSION = 1
        const val REJECTION_NOT_SEPARATED = "ranges-not-separated-by-guard-band"

        /**
         * Hood signature pinned for this slice; the real value is confirmed during
         * Task 7 device acceptance and must stay stable afterwards or every stored
         * witness model invalidates.
         */
        const val DEFAULT_HOOD_SIGNATURE = "hood-default-v1"

        /**
         * Stable, inspectable fingerprint covering every invalidating field: recorded
         * ranges, guard band, algorithm version, sensor identity, and hood signature.
         */
        fun fingerprint(model: PowerWitnessModel): String =
            "power-witness|v=${model.algorithmVersion}" +
                "|dark=${"%.3f".format(model.darkMinLux)},${"%.3f".format(model.darkMaxLux)}" +
                "|lit=${"%.3f".format(model.litMinLux)},${"%.3f".format(model.litMaxLux)}" +
                "|band=${"%.3f".format(model.guardBandLux)}" +
                "|sensor=${model.sensorIdentity}" +
                "|hood=${model.hoodSignature}"

        /**
         * Invalidation matrix (parent spec section 4.3): remounting/hood shift, light
         * sensor identity change, or an algorithm bump require recommissioning, as does
         * any discontinuity of POWER use. Loss/recovery confirmation times and
         * notification preferences live in settings and never invalidate the model.
         */
        fun requiresRecommission(
            previous: CommissioningContext,
            current: CommissioningContext,
        ): Boolean =
            previous.sensorIdentity != current.sensorIdentity ||
                previous.hoodSignature != current.hoodSignature ||
                previous.algorithmVersion != current.algorithmVersion ||
                !current.powerUseContinuous

        /**
         * Rebuilds the commissioning context a stored model was commissioned under. A
         * commissioned model always predates continuous POWER use, so
         * [CommissioningContext.powerUseContinuous] is true; any later discontinuity is
         * expressed by the current context supplied at Arm time.
         */
        fun storedContextOf(model: PowerWitnessModel): CommissioningContext = CommissioningContext(
            sensorIdentity = model.sensorIdentity,
            hoodSignature = model.hoodSignature,
            algorithmVersion = model.algorithmVersion,
            powerUseContinuous = true,
        )
    }
}
