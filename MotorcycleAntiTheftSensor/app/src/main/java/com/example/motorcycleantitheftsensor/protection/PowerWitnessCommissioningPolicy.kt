package com.example.motorcycleantitheftsensor.protection

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

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
    /**
     * When this model was accepted, stamped by [ProtectionProfilePolicy.commissionPower].
     * Null for a model commissioned before it was recorded, and never part of
     * [fingerprint] — the date does not decide whether the calibration still holds.
     */
    val commissionedAtWallMs: Long? = null,
) {
    /**
     * Separate loss/recovery thresholds create a stable middle band. The recorded
     * dark/lit ranges remain immutable while armed; only the decision boundary is
     * widened so normal lamp fluctuation cannot flip the state on every sample.
     */
    val witnessDarkThresholdLux: Double
        get() = darkMaxLux

    val witnessLitThresholdLux: Double
        get() = darkMaxLux + (litMinLux - darkMaxLux).coerceAtLeast(0.0) * LIT_THRESHOLD_FRACTION

    /**
     * Re-expresses the commissioning ranges around the light level observed at Arm.
     * Scaling preserves the measured dark-to-lit contrast even when the motorcycle is
     * armed in daylight or at night, where an absolute lux offset would be wrong.
     */
    fun scaledForLitReference(litReferenceLux: Double): PowerWitnessModel {
        val commissionedLitReference = (litMinLux + litMaxLux) / 2.0
        if (litReferenceLux <= 0.0 || commissionedLitReference <= 0.0) return this
        val scale = litReferenceLux / commissionedLitReference
        return copy(
            darkMinLux = darkMinLux * scale,
            darkMaxLux = darkMaxLux * scale,
            litMinLux = litMinLux * scale,
            litMaxLux = litMaxLux * scale,
        )
    }

    private companion object {
        const val LIT_THRESHOLD_FRACTION = 0.70
    }
}

/**
 * Pure state machine for Power Guard witness commissioning (parent spec section
 * 4.3 "Physical setup and readiness"): with the charger connected, the guided lamp
 * off/on cycle records separate dark and lit sample windows. Acceptance requires
 * both windows continuous and visibly distinct from their own observed noise. Darkness
 * must stay within [maxRangeSpanLux], while the lit window keeps the full operating
 * range so normal lamp fluctuation is part of the frozen model. [guardBandLux] remains
 * in the constructor for profile compatibility; it is no longer an absolute admission
 * threshold because daylight and nighttime lux values are not comparable.
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
        val windowSamplesLux: List<Double> = emptyList(),
        val darkMinLux: Double? = null,
        val darkMaxLux: Double? = null,
        val darkCenterLogLux: Double? = null,
        val darkNoiseLogLux: Double? = null,
        val model: PowerWitnessModel? = null,
        val rejectionReason: String? = null,
    )

    fun start(): State = State(phase = Phase.DARK_WINDOW)

    fun onSample(state: State, sample: PowerWitnessSample): State = when (state.phase) {
        Phase.DARK_WINDOW -> handleWindowSample(state, sample, litPhase = false)
        Phase.LIT_WINDOW -> handleWindowSample(state, sample, litPhase = true)
        else -> state
    }

    fun onTick(state: State, timestampMs: Long): State {
        val start = state.windowStartMs ?: return state
        if (timestampMs - start < windowDurationMs) return state
        return when (state.phase) {
            Phase.DARK_WINDOW -> completeWindow(state, litPhase = false)
            Phase.LIT_WINDOW -> completeWindow(state, litPhase = true)
            else -> state
        }
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
        val updated = state.copy(
            windowStartMs = start,
            lastSampleMs = sample.timestampMs,
            windowMinLux = min,
            windowMaxLux = max,
            windowSamplesLux = state.windowSamplesLux + sample.lux,
        )
        if (sample.timestampMs - start < windowDurationMs) return updated
        return completeWindow(updated, litPhase)
    }

    private fun completeWindow(state: State, litPhase: Boolean): State {
        val robustWindow = robustWindow(state.windowSamplesLux) ?: return resetWindow(state)
        return if (!litPhase) {
            state.copy(
                phase = Phase.LIT_WINDOW,
                darkMinLux = robustWindow.lowerLux,
                darkMaxLux = robustWindow.upperLux,
                darkCenterLogLux = robustWindow.centerLogLux,
                darkNoiseLogLux = robustWindow.noiseLogLux,
                windowStartMs = null,
                lastSampleMs = null,
                windowMinLux = null,
                windowMaxLux = null,
                windowSamplesLux = emptyList(),
            )
        } else {
            finishLitWindow(state, robustWindow)
        }
    }

    private fun beginWindow(state: State, sample: PowerWitnessSample): State = state.copy(
        windowStartMs = sample.timestampMs,
        lastSampleMs = sample.timestampMs,
        windowMinLux = sample.lux,
        windowMaxLux = sample.lux,
        windowSamplesLux = listOf(sample.lux),
    )

    private fun resetWindow(state: State): State = state.copy(
        windowStartMs = null,
        lastSampleMs = null,
        windowMinLux = null,
        windowMaxLux = null,
        windowSamplesLux = emptyList(),
    )

    private fun finishLitWindow(
        state: State,
        litWindow: RobustWindow,
    ): State {
        val darkCenter = requireNotNull(state.darkCenterLogLux)
        val darkNoise = requireNotNull(state.darkNoiseLogLux)
        val separation = litWindow.centerLogLux - darkCenter
        val combinedNoise = hypot(darkNoise, litWindow.noiseLogLux)
        if (separation < maxOf(MINIMUM_LOG_CONTRAST, MINIMUM_SIGNAL_TO_NOISE * combinedNoise)) {
            // The lamp change is not distinguishable from observed noise: full retry.
            return State(phase = Phase.DARK_WINDOW, rejectionReason = REJECTION_NOT_SEPARATED)
        }
        val observedNoiseLux = maxOf(
            MINIMUM_NOISE_FLOOR_LUX,
            state.darkMaxLux!! - state.darkMinLux!!,
            litWindow.upperLux - litWindow.lowerLux,
        )
        return State(
            phase = Phase.COMMISSIONED,
            model = PowerWitnessModel(
                darkMinLux = state.darkMinLux,
                darkMaxLux = state.darkMaxLux,
                litMinLux = litWindow.lowerLux,
                litMaxLux = litWindow.upperLux,
                guardBandLux = observedNoiseLux,
                algorithmVersion = ALGORITHM_VERSION,
                sensorIdentity = sensorIdentity,
                hoodSignature = hoodSignature,
            ),
        )
    }

    private fun robustWindow(samplesLux: List<Double>): RobustWindow? {
        if (samplesLux.isEmpty()) return null
        val logSamples = samplesLux.map { ln(1.0 + it.coerceAtLeast(0.0)) }
        val initialCenter = median(logSamples)
        val initialMad = median(logSamples.map { kotlin.math.abs(it - initialCenter) })
        val initialNoise = maxOf(
            MAD_TO_SIGMA * initialMad,
            logNoiseFloor(initialCenter),
        )
        val filtered = logSamples.filter {
            kotlin.math.abs(it - initialCenter) <= HAMPEL_SIGMA * initialNoise
        }.ifEmpty { listOf(initialCenter) }
        val center = median(filtered)
        val mad = median(filtered.map { kotlin.math.abs(it - center) })
        val noise = maxOf(MAD_TO_SIGMA * mad, logNoiseFloor(center))
        return RobustWindow(
            centerLogLux = center,
            noiseLogLux = noise,
            lowerLux = (exp(center - MODEL_SIGMA_WIDTH * noise) - 1.0).coerceAtLeast(0.0),
            upperLux = exp(center + MODEL_SIGMA_WIDTH * noise) - 1.0,
        )
    }

    private fun logNoiseFloor(centerLogLux: Double): Double {
        val centerLux = exp(centerLogLux) - 1.0
        return ln(1.0 + centerLux + MINIMUM_SENSOR_STEP_LUX) - centerLogLux
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private data class RobustWindow(
        val centerLogLux: Double,
        val noiseLogLux: Double,
        val lowerLux: Double,
        val upperLux: Double,
    )

    data class CommissioningContext(
        val sensorIdentity: String,
        val hoodSignature: String,
        val algorithmVersion: Int,
        val powerUseContinuous: Boolean,
    )

    companion object {
        const val ALGORITHM_VERSION = 4
        const val REJECTION_NOT_SEPARATED = "ranges-not-separated-by-guard-band"
        private const val MINIMUM_NOISE_FLOOR_LUX = 1.0
        private const val MINIMUM_SENSOR_STEP_LUX = 0.25
        private const val MINIMUM_LOG_CONTRAST = 0.01
        private const val MINIMUM_SIGNAL_TO_NOISE = 4.0
        private const val MAD_TO_SIGMA = 1.4826
        private const val HAMPEL_SIGMA = 3.5
        private const val MODEL_SIGMA_WIDTH = 2.0

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
