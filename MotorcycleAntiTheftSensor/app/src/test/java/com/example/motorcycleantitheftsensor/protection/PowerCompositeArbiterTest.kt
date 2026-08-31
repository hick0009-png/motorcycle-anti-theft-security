package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the pure-domain Power Guard composite-state arbiter (parent spec
 * section 4.3 "Decision contract" and "Power episode arbitration and idempotency"):
 * four charging/witness combinations, per-state continuous debounce windows, one
 * episode per abnormality sequence, escalation/crossover semantics, and honest
 * degradation on stale or ambiguous evidence.
 */
class PowerCompositeArbiterTest {

    private fun arbiter() = PowerCompositeArbiter(
        model = PowerWitnessModel(
            darkMinLux = 2.0,
            darkMaxLux = 4.0,
            litMinLux = 120.0,
            litMaxLux = 123.0,
            guardBandLux = 20.0,
            algorithmVersion = 1,
            sensorIdentity = "light#1",
            hoodSignature = "hood-A",
        ),
        settings = PowerProfileSettings(),
    )

    private fun sample(charging: Boolean, lit: Boolean, t: Long, fresh: Boolean = true) =
        PowerSignalSample(
            chargingConnected = charging,
            witnessLux = if (lit) 121.0 else 3.0,
            fresh = fresh,
            timestampMs = t,
        )

    /** Feeds one sample per second starting at t=0; returns the last non-null verdict. */
    private fun feed(
        a: PowerCompositeArbiter,
        vararg points: Triple<Long, Boolean, Boolean>,
    ): Pair<PowerArbiterVerdict?, PowerCompositeArbiter.State> {
        var state = a.initialState()
        var last: PowerArbiterVerdict? = null
        for ((t, charging, lit) in points) {
            val (verdict, next) = a.evaluate(state, sample(charging, lit, t))
            state = next
            if (verdict != null) last = verdict
        }
        return last to state
    }

    private fun steady(from: Long, until: Long, charging: Boolean, lit: Boolean) =
        (from..until step 1_000L).map { Triple(it, charging, lit) }

    @Test
    fun healthyDualSignalProducesNoIncident() {
        val a = arbiter()
        val (verdict, state) = feed(a, *steady(0L, 40_000L, charging = true, lit = true).toTypedArray())
        assertNull(verdict)
        assertNull(state.episodeId)
    }

    @Test
    fun chargingOnlyLossOpensHealthAlertAfterTenSecondsNeverOutage() {
        val a = arbiter()
        val points = steady(0L, 20_000L, charging = false, lit = true)
        var firedAt: Long? = null
        var state = a.initialState()
        var opened: PowerArbiterVerdict.ChargingHealthAlert? = null
        for ((t, charging, lit) in points) {
            val (verdict, next) = a.evaluate(state, sample(charging, lit, t))
            state = next
            if (verdict is PowerArbiterVerdict.ChargingHealthAlert && opened == null) {
                opened = verdict
                firedAt = t
            }
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }
        assertEquals(10_000L, firedAt)
        assertEquals("POWER-1", opened!!.episodeId)
    }

    @Test
    fun witnessOnlyLossOpensHealthAlertAfterTenSecondsNeverOutage() {
        val a = arbiter()
        val points = steady(0L, 20_000L, charging = true, lit = false)
        var firedAt: Long? = null
        var state = a.initialState()
        var opened: PowerArbiterVerdict.WitnessHealthAlert? = null
        for ((t, charging, lit) in points) {
            val (verdict, next) = a.evaluate(state, sample(charging, lit, t))
            state = next
            if (verdict is PowerArbiterVerdict.WitnessHealthAlert && opened == null) {
                opened = verdict
                firedAt = t
            }
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }
        assertEquals(10_000L, firedAt)
        assertEquals("POWER-1", opened!!.episodeId)
    }

    @Test
    fun dualLossRequiresContinuousTenSecondsFromZero() {
        val a = arbiter()
        var state = a.initialState()
        var openedAt: Long? = null
        // 9 seconds of dual loss alone must not open anything.
        for (t in 0L..9_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, false, t))
            state = next
            assertNull(verdict)
        }
        // Continuing the same dual loss: confirmation lands exactly at 10 s.
        val (verdict, _) = a.evaluate(state, sample(false, false, 10_000L))
        assertTrue(verdict is PowerArbiterVerdict.ConfirmedLossOpened)
        openedAt = 10_000L
        assertEquals(10_000L, openedAt)
    }

    @Test
    fun oneSignalTimerDoesNotInheritIntoDualLossWindow() {
        val a = arbiter()
        var state = a.initialState()
        // Charging lost for 9 s: its own timer runs but has not fired.
        for (t in 0L..9_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, true, t))
            state = next
        }
        // Witness goes dark at t=10_000: the dual-loss window must start from zero.
        for (t in 10_000L..19_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, false, t))
            state = next
            assertNull("dual loss fired early at $t", verdict)
        }
        val (verdict, _) = a.evaluate(state, sample(false, false, 20_000L))
        assertTrue(verdict is PowerArbiterVerdict.ConfirmedLossOpened)
    }

    @Test
    fun enteringDualLossCancelsUndeliveredOneSignalTimer() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..9_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, true, t))
            state = next
        }
        // Brief dual excursion, then the witness returns: the charging timer restarts.
        val (dualVerdict, dualState) = a.evaluate(state, sample(false, false, 10_000L))
        assertNull(dualVerdict)
        val (backVerdict, backState) = a.evaluate(dualState, sample(false, true, 10_500L))
        assertNull(backVerdict)
        // Only 9.5 s of continuous charging-loss after the excursion: still nothing.
        val (early, earlyState) = a.evaluate(backState, sample(false, true, 20_000L))
        assertNull(early)
        // At exactly 10 s of continuous charging loss the health alert opens.
        val (fired, _) = a.evaluate(earlyState, sample(false, true, 20_500L))
        assertTrue(fired is PowerArbiterVerdict.ChargingHealthAlert)
    }

    @Test
    fun escalationSupersedesOneSignalInsideSameEpisode() {
        val a = arbiter()
        var state = a.initialState()
        var chargingEpisodeId: String? = null
        for (t in 0L..15_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, true, t))
            state = next
            if (verdict is PowerArbiterVerdict.ChargingHealthAlert) chargingEpisodeId = verdict.episodeId
        }
        assertEquals("POWER-1", chargingEpisodeId)
        // Witness goes dark: the SAME episode escalates to confirmed loss after 10 s.
        var escalated: PowerArbiterVerdict.ConfirmedLossOpened? = null
        for (t in 16_000L..26_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, false, t))
            state = next
            if (verdict is PowerArbiterVerdict.ConfirmedLossOpened) escalated = verdict
        }
        assertEquals("POWER-1", escalated!!.episodeId)
    }

    @Test
    fun crossoverRemainsSameEpisodeAndEmitsConditionChanged() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, false, t))
            state = next
        }
        // Partial recovery: the witness returns while charging is still out.
        val (partial, partialState) = a.evaluate(state, sample(false, true, 11_000L))
        assertTrue(partial is PowerArbiterVerdict.PartialRecovery)
        state = partialState
        // After the new one-signal state is stable for 10 s: CONDITION_CHANGED, same episode.
        var changed: PowerArbiterVerdict.ConditionChanged? = null
        for (t in 12_000L..21_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, true, t))
            state = next
            if (verdict is PowerArbiterVerdict.ConditionChanged) changed = verdict
        }
        assertEquals(PowerCompositeArbiter.SemanticState.DUAL_LOST, changed!!.from)
        assertEquals(PowerCompositeArbiter.SemanticState.CHARGING_LOST, changed.to)
        assertEquals("POWER-1", changed.episodeId)
    }

    @Test
    fun closeRequiresBothSignalsHealthyTenSeconds() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, false, t))
            state = next
        }
        // Both signals healthy again: no close before 10 s.
        for (t in 11_000L..20_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(true, true, t))
            state = next
            assertTrue(verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, closedState) = a.evaluate(state, sample(true, true, 21_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
        assertEquals("POWER-1", (closed as PowerArbiterVerdict.RecoveredClosed).episodeId)
        assertNull(closedState.episodeId)
        // A later abnormality receives a NEW powerEpisodeId.
        var reopened: PowerArbiterVerdict.ConfirmedLossOpened? = null
        var walk = closedState
        for (t in 22_000L..32_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(false, false, t))
            walk = next
            if (verdict is PowerArbiterVerdict.ConfirmedLossOpened) reopened = verdict
        }
        assertEquals("POWER-2", reopened!!.episodeId)
    }

    @Test
    fun guardBandDuringRecoveryPreservesHealthyTimer() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = false, t = t))
            state = next
        }

        val (_, healthyState) = a.evaluate(state, sample(charging = true, lit = true, t = 11_000L))
        state = healthyState
        for (t in 12_000L..14_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = true, t = t))
            state = next
        }
        val (guardBandVerdict, guardBandState) = a.evaluate(
            state,
            PowerSignalSample(
                chargingConnected = true,
                witnessLux = 50.0,
                fresh = true,
                timestampMs = 15_000L,
            ),
        )
        assertNull(guardBandVerdict)

        var walk = guardBandState
        for (t in 16_000L..20_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(charging = true, lit = true, t = t))
            walk = next
            assertTrue("should not close early at $t", verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, _) = a.evaluate(walk, sample(charging = true, lit = true, t = 21_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
    }

    @Test
    fun chargingLossDuringGuardBandRestartsHealthyTimer() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = false, t = t))
            state = next
        }
        for (t in 11_000L..14_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = true, t = t))
            state = next
        }

        val (_, disconnectedState) = a.evaluate(
            state,
            PowerSignalSample(
                chargingConnected = false,
                witnessLux = 50.0,
                fresh = true,
                timestampMs = 15_000L,
            ),
        )

        var walk = disconnectedState
        for (t in 16_000L..25_000L step 1_000L) {
            val (verdict, next) = a.evaluate(walk, sample(charging = true, lit = true, t = t))
            walk = next
            assertTrue("should restart recovery after charging loss at $t", verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
        val (closed, _) = a.evaluate(walk, sample(charging = true, lit = true, t = 26_000L))
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
    }

    @Test
    fun chargerDisconnectedWithGuardBandLuxUsesFreshLastKnownWitness() {
        val a = arbiter()
        var state = a.initialState()
        val (_, healthyState) = a.evaluate(
            state,
            sample(charging = true, lit = true, t = 0L),
        )
        state = healthyState

        var alert: PowerArbiterVerdict? = null
        for (t in 1_000L..11_000L step 1_000L) {
            val (verdict, next) = a.evaluate(
                state,
                PowerSignalSample(false, 50.0, fresh = true, timestampMs = t),
            )
            state = next
            if (verdict != null) alert = verdict
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }

        assertTrue(alert is PowerArbiterVerdict.ChargingHealthAlert)
    }

    @Test
    fun witnessLostThenChargerDisconnectsWithGuardBandEscalatesToDualLoss() {
        val a = arbiter()
        var state = a.initialState()
        val (_, witnessLostState) = a.evaluate(
            state,
            sample(charging = true, lit = false, t = 0L),
        )
        state = witnessLostState

        var verdict: PowerArbiterVerdict? = null
        for (t in 1_000L..11_000L step 1_000L) {
            val evaluated = a.evaluate(
                state,
                PowerSignalSample(false, 50.0, fresh = true, timestampMs = t),
            )
            verdict = evaluated.first ?: verdict
            state = evaluated.second
        }

        assertTrue(verdict is PowerArbiterVerdict.ConfirmedLossOpened)
    }

    @Test
    fun staleWitnessBaselineCannotEscalateGuardBandCableLossToOutage() {
        val a = arbiter()
        var state = a.initialState()
        val (_, witnessLostState) = a.evaluate(
            state,
            sample(charging = true, lit = false, t = 0L),
        )
        state = witnessLostState
        val (_, staleState) = a.evaluate(
            state,
            PowerSignalSample(false, null, fresh = false, timestampMs = 1_000L),
        )
        state = staleState

        var alert: PowerArbiterVerdict? = null
        for (t in 2_000L..12_000L step 1_000L) {
            val (verdict, next) = a.evaluate(
                state,
                PowerSignalSample(false, 50.0, fresh = true, timestampMs = t),
            )
            state = next
            if (verdict != null) alert = verdict
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }

        assertTrue(alert is PowerArbiterVerdict.ChargingHealthAlert)
    }

    @Test
    fun chargerDisconnectWithoutWitnessBaselineReportsChargingOnly() {
        val a = arbiter()
        var state = a.initialState()
        var alert: PowerArbiterVerdict? = null

        for (t in 0L..10_000L step 1_000L) {
            val (verdict, next) = a.evaluate(
                state,
                PowerSignalSample(false, 50.0, fresh = true, timestampMs = t),
            )
            state = next
            if (verdict != null) alert = verdict
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }

        assertTrue(alert is PowerArbiterVerdict.ChargingHealthAlert)
    }

    @Test
    fun witnessLostThenLitAgainRecoverAfterTenSeconds() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = false, t = t))
            state = next
        }
        for (t in 11_000L..20_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(charging = true, lit = true, t = t))
            state = next
            assertTrue("should not close early at $t", verdict !is PowerArbiterVerdict.RecoveredClosed)
        }

        val (closed, closedState) = a.evaluate(
            state,
            sample(charging = true, lit = true, t = 21_000L),
        )
        assertTrue(closed is PowerArbiterVerdict.RecoveredClosed)
        assertNull(closedState.episodeId)
    }

    @Test
    fun recoveredLightMayFluctuateBelowTheRecordedLitMinimum() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(charging = true, lit = false, t = t))
            state = next
        }

        var recovered: PowerArbiterVerdict? = null
        for (t in 11_000L..41_000L step 1_000L) {
            val (verdict, next) = a.evaluate(
                state,
                PowerSignalSample(
                    chargingConnected = true,
                    witnessLux = 90.0,
                    fresh = true,
                    timestampMs = t,
                ),
            )
            state = next
            if (verdict != null) recovered = verdict
        }

        assertTrue(recovered is PowerArbiterVerdict.RecoveredClosed)
    }

    @Test
    fun partialRecoveryKeepsEpisodeOpenWithoutCloseMessage() {
        val a = arbiter()
        var state = a.initialState()
        for (t in 0L..10_000L step 1_000L) {
            val (_, next) = a.evaluate(state, sample(false, false, t))
            state = next
        }
        val (partial, partialState) = a.evaluate(state, sample(false, true, 11_000L))
        assertTrue(partial is PowerArbiterVerdict.PartialRecovery)
        assertEquals("POWER-1", partialState.episodeId)
        // Continued one-signal state never closes the episode by itself.
        for (t in 12_000L..60_000L step 1_000L) {
            val (verdict, next) = a.evaluate(partialState, sample(false, true, t))
            assertTrue(verdict !is PowerArbiterVerdict.RecoveredClosed)
        }
    }

    @Test
    fun staleLightSamplesCannotProduceOutageButFreshCableLossStillAlerts() {
        val a = arbiter()
        var state = a.initialState()
        // Stale light source for a full minute: no outage conclusion may be drawn.
        for (t in 0L..60_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, PowerSignalSample(false, null, fresh = false, timestampMs = t))
            state = next
            assertNull(verdict)
            assertNull(state.episodeId)
        }
        // Guard-band lux cannot confirm an outage, but the fresh cable signal is
        // still enough for a charging-only health alert.
        var chargingAlerts = 0
        for (t in 61_000L..90_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, PowerSignalSample(false, 10.0, fresh = true, timestampMs = t))
            state = next
            if (verdict is PowerArbiterVerdict.ChargingHealthAlert) chargingAlerts++
            assertTrue(verdict !is PowerArbiterVerdict.ConfirmedLossOpened)
        }
        assertEquals(1, chargingAlerts)
    }

    @Test
    fun repeatedSamplesDoNotAdvanceEpisodeState() {
        val a = arbiter()
        var state = a.initialState()
        var openings = 0
        for (t in 0L..60_000L step 1_000L) {
            val (verdict, next) = a.evaluate(state, sample(false, false, t))
            state = next
            if (verdict is PowerArbiterVerdict.ConfirmedLossOpened) openings++
            if (verdict is PowerArbiterVerdict.LossStillConfirmed) openings += 10
        }
        assertEquals(1, openings)
    }
}
