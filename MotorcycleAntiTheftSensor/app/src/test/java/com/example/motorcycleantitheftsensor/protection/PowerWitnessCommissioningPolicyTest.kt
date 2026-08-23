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
        assertEquals(2.0, model!!.darkMinLux, 0.001)
        assertEquals(4.0, model.darkMaxLux, 0.001)
        assertEquals(120.0, model.litMinLux, 0.001)
        assertEquals(123.0, model.litMaxLux, 0.001)
        assertEquals("light#1", model.sensorIdentity)
        assertEquals("hood-A", model.hoodSignature)
    }

    @Test
    fun overlappingDarkAndLitRangesAreRejected() {
        val p = policy(guardBandLux = 20.0)
        val darkDone = darkSamples(p)
        // Lit range overlaps the dark range: separation is far below the guard band.
        val rejected = litSamples(p, darkDone, lux = 10.0)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, rejected.phase)
        assertEquals("ranges-not-separated-by-guard-band", rejected.rejectionReason)
        assertNull(rejected.model)
    }

    @Test
    fun rangesInsideGuardBandAreRejected() {
        val p = policy(guardBandLux = 20.0)
        val darkDone = darkSamples(p)
        // Positive but insufficient separation: lit 20..23 minus darkMax 4 = 16 < 20.
        val rejected = litSamples(p, darkDone, lux = 20.0)
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, rejected.phase)
        assertEquals("ranges-not-separated-by-guard-band", rejected.rejectionReason)
        assertNull(rejected.model)
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
    fun excessiveVarianceRestartsCurrentWindow() {
        val p = policy(maxSpanLux = 5.0)
        var state = p.start()
        state = p.onSample(state, PowerWitnessSample(lux = 2.0, timestampMs = 0L, fresh = true))
        state = p.onSample(state, PowerWitnessSample(lux = 2.5, timestampMs = 500L, fresh = true))
        // A spike beyond the allowed span restarts the current window at this sample.
        state = p.onSample(state, PowerWitnessSample(lux = 60.0, timestampMs = 1_000L, fresh = true))
        assertEquals(PowerWitnessCommissioningPolicy.Phase.DARK_WINDOW, state.phase)
        assertEquals(60.0, state.windowMaxLux!!, 0.001)
        assertEquals(60.0, state.windowMinLux!!, 0.001)
        assertEquals(1_000L, state.windowStartMs)
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
