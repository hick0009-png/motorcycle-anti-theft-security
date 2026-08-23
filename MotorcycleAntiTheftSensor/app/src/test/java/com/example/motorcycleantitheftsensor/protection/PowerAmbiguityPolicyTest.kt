package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the safety-weighted Power Guard ambiguity policy (parent spec
 * section 4.3): uncertain Telegram acceptance is never claimed as failure or
 * exactly-once; uncertain one-signal messages are never auto-retried; a confirmed
 * outage opening gets at most ONE labeled safety retry after 30 s while still
 * continuously lost; recovery/supersession/close cancels a not-started retry; and
 * settlement copy is chosen from durable owner-notification state.
 */
class PowerAmbiguityPolicyTest {

    private fun policy() = PowerAmbiguityPolicy(nowMs = { 0L })

    private fun openingItem(kind: PowerTransitionKind = PowerTransitionKind.CONFIRMED_LOSS_OPENING) =
        PowerOutboxItem(
            armedSessionId = "session-1",
            powerEpisodeId = "POWER-1",
            transitionOrdinal = 1L,
            kind = kind,
            text = "ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง",
            state = PowerOutboxState.DELIVERY_UNCERTAIN,
            createdAtMs = 0L,
        )

    @Test
    fun uncertainOneSignalMessageIsNeverAutoRetried() {
        val p = policy()
        val item = openingItem(PowerTransitionKind.CHARGING_HEALTH_OPENING)
        val state = p.onUncertain(PowerAmbiguityPolicy.State(), item)
        // One-signal health copy is recorded but no safety retry may ever fire.
        assertFalse(p.safetyRetryDue(state, nowMs = 999_999L, stillContinuouslyLost = true, openingStillCurrent = true))
        assertNull(state.uncertainOpening)
    }

    @Test
    fun confirmedOpeningGetsAtMostOneLabeledRetryAfterThirtySecondsWhileStillLost() {
        val p = policy()
        var state = p.onUncertain(PowerAmbiguityPolicy.State(), openingItem())
        assertFalse("retry before 30 s", p.safetyRetryDue(state, nowMs = 29_999L, stillContinuouslyLost = true, openingStillCurrent = true))
        assertTrue("no retry at 30 s", p.safetyRetryDue(state, nowMs = 30_000L, stillContinuouslyLost = true, openingStillCurrent = true))
        state = p.markSafetyRetryEnqueued(state)
        // At most one labeled retry: after enqueueing, never due again.
        assertFalse(p.safetyRetryDue(state, nowMs = 60_000L, stillContinuouslyLost = true, openingStillCurrent = true))
    }

    @Test
    fun conditionsGuardTheSafetyRetry() {
        val p = policy()
        val state = p.onUncertain(PowerAmbiguityPolicy.State(), openingItem())
        // Episode recovered before the deadline: no stale outage retry.
        assertFalse(p.safetyRetryDue(state, nowMs = 40_000L, stillContinuouslyLost = false, openingStillCurrent = true))
        // A newer semantic superseded the opening: no retry of outdated copy.
        assertFalse(p.safetyRetryDue(state, nowMs = 40_000L, stillContinuouslyLost = true, openingStillCurrent = false))
    }

    @Test
    fun recoveryOrSupersessionOrCloseCancelsNotStartedSafetyRetry() {
        val p = policy()
        var state = p.onUncertain(PowerAmbiguityPolicy.State(), openingItem())
        state = p.cancelNotStartedSafetyRetry(state)
        assertFalse(p.safetyRetryDue(state, nowMs = 99_999L, stillContinuouslyLost = true, openingStillCurrent = true))
    }

    @Test
    fun settlementChosenFromDurableOwnerNotificationState() {
        assertEquals(
            PowerTransitionKind.ONE_SIGNAL_RECOVERY,
            PowerAmbiguityPolicy.settlementKind(PowerOwnerNotificationState.ONE_SIGNAL_OPENING_ACCEPTED),
        )
        assertEquals(
            PowerTransitionKind.CONFIRMED_CLOSE,
            PowerAmbiguityPolicy.settlementKind(PowerOwnerNotificationState.CONFIRMED_OPENING_ACCEPTED),
        )
        assertEquals(
            PowerTransitionKind.UNCERTAINTY_SETTLEMENT,
            PowerAmbiguityPolicy.settlementKind(PowerOwnerNotificationState.OPENING_DELIVERY_UNCERTAIN),
        )
    }

    @Test
    fun silentRetireWhenNoOpeningWasAcceptedOrAmbiguous() {
        assertNull(
            PowerAmbiguityPolicy.settlementKind(PowerOwnerNotificationState.NO_OPENING_REACHED_OWNER),
        )
    }

    @Test
    fun uncertaintySettlementReferencesSameEpisodeId() {
        val text = PowerAmbiguityPolicy.uncertaintySettlementText(
            episodeId = "POWER-1",
            currentCondition = "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว",
        )
        assertTrue(text.contains("POWER-1"))
        assertTrue(text.contains("ผลการส่งครั้งแรกไม่แน่นอน"))
    }

    @Test
    fun retryCarriesVisibleEpisodeIdAndPrefix() {
        val text = PowerAmbiguityPolicy.labeledSafetyRetryText(
            originalText = "ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง",
            episodeId = "POWER-1",
        )
        assertTrue(text.startsWith("ส่งซ้ำเพื่อยืนยันเหตุเดิม—ผลการส่งครั้งแรกไม่แน่นอน"))
        assertTrue(text.contains("POWER-1"))
        assertTrue(text.contains("ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง"))
    }
}
