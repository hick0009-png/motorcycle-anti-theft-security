package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the pure-domain Power Guard witness commissioning policy
 * (parent spec section 4.3 "Physical setup and readiness"): a guided lamp off/on
 * cycle records separate dark and lit sample windows; acceptance requires both
 * windows stable, continuous, and separated by the configured guard band.
 */
class PowerWitnessCommissioningPolicyTest {

    private fun policy(
        windowMs: Long = 3_000L,
        maxSpanLux: Double = 5.0,
        guardBandLux: Double = 20.0,
    ) = PowerWitnessCommissioningPolicy(
        windowDurationMs = windowMs,
        maxSampleGapMs = 1_000L,
        maxRangeSpanLux = maxSpanLux,
        guardBandLux = guardBandLux,
        sensorIdentity = "light#1",
        hoodSignature = "hood-A",
    )

    private fun darkSamples(policy: PowerWitnessCommissioningPolicy): PowerWitnessCommissioningPolicy.State {
        var state = policy.start()
        for (t in 0..3_000 step 500) {
            state = policy.onSample(state, PowerWitnessSample(lux = 2.0 + (t % 1000) / 250.0, timestampMs = t.toLong(), fresh = true))
        }
        return state
    }

    private fun litSamples(
        policy: PowerWitnessCommissioningPolicy,
        after: PowerWitnessCommissioningPolicy.State,
        lux: Double = 120.0,
    ): PowerWitnessCommissioningPolicy.State {
        var state = after
        for (i in 0..6) {
            state = policy.onSample(state, PowerWitnessSample(lux = lux + i * 0.5, timestampMs = 4_000L + i * 500L, fresh = true))
        }
        return state
    }

    @Test
    fun stableDarkWindowThenStableLitWindowCommissions() {
        val p = policy()
        val darkDone = darkSamples(p)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.LIT_WINDOW, darkDone.phase)
        assertNotNull(darkDone.darkMinLux)
        val commissioned = litSamples(p, darkDone)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, commissioned.phase)
        val model = commissioned.model
        assertNotNull(model)
        assertTrue(model!!.darkMinLux < model.darkMaxLux)
        assertTrue(model.litMinLux < model.litMaxLux)
        assertTrue(model.darkMaxLux < model.litMinLux)
        assertEquals("light#1", model.sensorIdentity)
        assertEquals("hood-A", model.hoodSignature)
    }

    @Test
    fun overlappingDarkAndLitRangesAreRejected() {
        val p = policy(guardBandLux = 20.0)
        val darkDone = darkSamples(p)
        // Lit range overlaps the dark range, so the lamp change is indistinguishable.
        val rejected = litSamples(p, darkDone, lux = 2.0)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, rejected.phase)
        assertEquals("ranges-not-separated-by-guard-band", rejected.rejectionReason)
        assertNull(rejected.model)
    }

    @Test
    fun separatedRangesAboveObservedNoiseCommission() {
        val p = policy(guardBandLux = 20.0)
        val darkDone = darkSamples(p)
        // The 16-lux change is much larger than both observed ranges, despite the old
        // fixed 20-lux guard band.
        val commissioned = litSamples(p, darkDone, lux = 20.0)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, commissioned.phase)
        assertNotNull(commissioned.model)
    }

    @Test
    fun smallStableLightContrastCommissionsWithoutAStaticLuxGuardBand() {
        val p = policy(guardBandLux = 20.0)
        var state = p.start()
        for (t in 0L..3_000L step 500L) {
            state = p.onSample(state, PowerWitnessSample(lux = 100.0, timestampMs = t, fresh = true))
        }
        for (t in 4_000L..7_000L step 500L) {
            state = p.onSample(state, PowerWitnessSample(lux = 104.0, timestampMs = t, fresh = true))
        }

        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, state.phase)
        assertNotNull(state.model)
    }

    @Test
    fun isolatedOutliersDoNotRejectSeparatedBrightAmbientWindows() {
        val p = policy(maxSpanLux = 5.0)
        var state = p.start()
        val darkLux = listOf(100.0, 100.2, 450.0, 99.9, 100.1, 100.0, 100.2)
        darkLux.forEachIndexed { index, lux ->
            state = p.onSample(
                state,
                PowerWitnessSample(lux = lux, timestampMs = index * 500L, fresh = true),
            )
        }
        assertEquals(PowerWitnessCommissioningPolicy.Phase.LIT_WINDOW, state.phase)

        val litLux = listOf(104.0, 104.2, 0.0, 103.9, 104.1, 104.0, 104.2)
        litLux.forEachIndexed { index, lux ->
            state = p.onSample(
                state,
                PowerWitnessSample(lux = lux, timestampMs = 4_000L + index * 500L, fresh = true),
            )
        }

        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, state.phase)
        val model = requireNotNull(state.model)
        assertTrue(model.darkMaxLux < model.litMinLux)
    }

    @Test
    fun elapsedWindowAdvancesWhenOnChangeSensorDoesNotRepeatStableValue() {
        val p = policy()
        var state = p.start()
        state = p.onSample(state, PowerWitnessSample(lux = 10.0, timestampMs = 0L, fresh = true))
        state = p.onTick(state, timestampMs = 3_000L)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.LIT_WINDOW, state.phase)

        state = p.onSample(state, PowerWitnessSample(lux = 20.0, timestampMs = 4_000L, fresh = true))
        state = p.onTick(state, timestampMs = 7_000L)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, state.phase)
    }

    @Test
    fun brightValueAloneIsNotProofWithoutLitWindow() {
        val p = policy()
        val darkDone = darkSamples(p)
        // A single bright reading must not commission; the full lit window is required.
        val oneBright = p.onSample(darkDone, PowerWitnessSample(lux = 150.0, timestampMs = 4_000L, fresh = true))
        assertEquals(PowerWitnessCommissioningPolicy.Phase.LIT_WINDOW, oneBright.phase)
        assertNull(oneBright.model)
    }

    @Test
    fun isolatedSpikeIsRetainedForRobustFilteringWithoutRestart() {
        val p = policy(maxSpanLux = 5.0)
        var state = p.start()
        state = p.onSample(state, PowerWitnessSample(lux = 2.0, timestampMs = 0L, fresh = true))
        state = p.onSample(state, PowerWitnessSample(lux = 2.5, timestampMs = 500L, fresh = true))
        // Robust statistics need the complete window so they can classify this spike.
        state = p.onSample(state, PowerWitnessSample(lux = 60.0, timestampMs = 1_000L, fresh = true))
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, state.phase)
        assertEquals(60.0, state.windowMaxLux!!, 0.001)
        assertEquals(2.0, state.windowMinLux!!, 0.001)
        assertEquals(0L, state.windowStartMs)
        assertEquals(3, state.windowSamplesLux.size)
    }

    @Test
    fun fluctuatingLitWindowIsCapturedInsteadOfRestarted() {
        val p = policy(maxSpanLux = 5.0)
        val darkDone = darkSamples(p)
        var state = darkDone
        val fluctuatingLux = listOf(40.0, 60.0, 45.0, 55.0, 50.0, 58.0, 52.0)
        fluctuatingLux.forEachIndexed { index, lux ->
            state = p.onSample(
                state,
                PowerWitnessSample(
                    lux = lux,
                    timestampMs = 4_000L + index * 500L,
                    fresh = true,
                ),
            )
        }

        assertEquals(PowerWitnessCommissioningPolicy.Phase.COMMISSIONED, state.phase)
        val model = requireNotNull(state.model)
        assertTrue(model.litMinLux < 52.0)
        assertTrue(model.litMaxLux > 52.0)
    }

    @Test
    fun staleSampleRestartsCurrentWindow() {
        val p = policy()
        var state = p.start()
        state = p.onSample(state, PowerWitnessSample(lux = 2.0, timestampMs = 0L, fresh = true))
        state = p.onSample(state, PowerWitnessSample(lux = 2.5, timestampMs = 500L, fresh = true))
        state = p.onSample(state, PowerWitnessSample(lux = 2.2, timestampMs = 1_000L, fresh = false))
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, state.phase)
        assertNull(state.windowStartMs)
        assertNull(state.windowMinLux)
    }

    @Test
    fun sampleGapRestartsCurrentWindow() {
        val p = policy()
        var state = p.start()
        state = p.onSample(state, PowerWitnessSample(lux = 2.0, timestampMs = 0L, fresh = true))
        // Gap larger than maxSampleGapMs breaks continuity of the observation window.
        state = p.onSample(state, PowerWitnessSample(lux = 2.5, timestampMs = 5_000L, fresh = true))
        assertEquals(5_000L, state.windowStartMs)
    }

    @Test
    fun fingerprintCoversEveryInvalidatingField() {
        val base = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        )
        val baseFp = PowerWitnessCommissioningPolicy.fingerprint(base)
        assertEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy()))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(darkMaxLux = 5.0)))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(litMinLux = 121.0)))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(guardBandLux = 25.0)))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(algorithmVersion = 2)))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(sensorIdentity = "light#2")))
        assertNotEquals(baseFp, PowerWitnessCommissioningPolicy.fingerprint(base.copy(hoodSignature = "hood-B")))
    }

    private fun assertNotEquals(expected: String, actual: String) {
        assertFalse("expected different fingerprints", expected == actual)
    }

    @Test
    fun invalidationMatrixMatchesSpec() {
        val previous = PowerWitnessCommissioningPolicy.CommissioningContext(
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
            algorithmVersion = 1,
            powerUseContinuous = true,
        )
        // Remount/hood change, sensor change, algorithm bump, or leaving POWER all invalidate.
        assertTrue(
            PowerWitnessCommissioningPolicy.requiresRecommission(
                previous,
                previous.copy(hoodSignature = "hood-B"),
            ),
        )
        assertTrue(
            PowerWitnessCommissioningPolicy.requiresRecommission(
                previous,
                previous.copy(sensorIdentity = "light#2"),
            ),
        )
        assertTrue(
            PowerWitnessCommissioningPolicy.requiresRecommission(
                previous,
                previous.copy(algorithmVersion = 2),
            ),
        )
        assertTrue(
            PowerWitnessCommissioningPolicy.requiresRecommission(
                previous,
                previous.copy(powerUseContinuous = false),
            ),
        )
        // Loss/recovery confirmation times and notification preferences never invalidate.
        assertFalse(
            PowerWitnessCommissioningPolicy.requiresRecommission(previous, previous),
        )
    }
}
