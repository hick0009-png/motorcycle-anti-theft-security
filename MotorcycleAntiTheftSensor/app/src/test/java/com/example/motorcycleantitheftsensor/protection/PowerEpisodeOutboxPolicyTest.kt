package com.example.motorcycleantitheftsensor.protection

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host proofs for the durable Power Guard episode/outbox model (parent spec section
 * 4.3 "Power episode arbitration and idempotency"): one monotonic transition ordinal
 * per genuine semantic change, one durable outbox key per transition, supersede/
 * in-flight/accepted/uncertain/final-failure state rules, and the additive codec
 * extension that persists the commissioned witness model.
 */
class PowerEpisodeOutboxPolicyTest {

    private fun policy() = PowerEpisodeOutboxPolicy(nowMs = { 5_000L })

    private fun enqueue(
        p: PowerEpisodeOutboxPolicy,
        state: PowerEpisodeOutboxPolicy.State,
        kind: PowerTransitionKind = PowerTransitionKind.CONFIRMED_LOSS_OPENING,
        episode: String = "POWER-1",
        text: String = "ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง",
    ): Pair<PowerOutboxItem, PowerEpisodeOutboxPolicy.State> =
        p.enqueue(state, armedSessionId = "session-1", powerEpisodeId = episode, kind = kind, text = text)

    @Test
    fun ordinalAdvancesOnlyOnGenuineSemanticChange() {
        val p = policy()
        var state = PowerEpisodeOutboxPolicy.State()
        val (first, s1) = enqueue(p, state)
        assertEquals(1L, first.transitionOrdinal)
        state = s1
        val (second, s2) = enqueue(p, state, kind = PowerTransitionKind.CONDITION_CHANGED)
        assertEquals(2L, second.transitionOrdinal)
        state = s2
        val (third, _) = enqueue(p, state, kind = PowerTransitionKind.CONFIRMED_CLOSE)
        assertEquals(3L, third.transitionOrdinal)
    }

    @Test
    fun outboxKeyIsSessionEpisodeOrdinalKind() {
        val p = policy()
        val (item, _) = enqueue(p, PowerEpisodeOutboxPolicy.State())
        assertEquals("session-1|POWER-1|1|CONFIRMED_LOSS_OPENING", item.outboxKey)
    }

    @Test
    fun pendingObsoleteCopyIsSupersededBeforeEnqueueingNewCopy() {
        val p = policy()
        var state = PowerEpisodeOutboxPolicy.State()
        val (first, s1) = enqueue(p, state)
        state = s1
        val (second, s2) = enqueue(p, state, kind = PowerTransitionKind.CONDITION_CHANGED)
        val storedFirst = s2.items.first { it.outboxKey == first.outboxKey }
        assertEquals(PowerOutboxState.SUPERSEDED, storedFirst.state)
        assertEquals(PowerOutboxState.PENDING, second.state)
    }

    @Test
    fun supersededItemNeverSendsLater() {
        val p = policy()
        var state = PowerEpisodeOutboxPolicy.State()
        val (first, s1) = enqueue(p, state)
        state = s1
        val (_, s2) = enqueue(p, state, kind = PowerTransitionKind.CONDITION_CHANGED)
        val superseded = s2.items.first { it.outboxKey == first.outboxKey }
        assertFalse(p.canDispatch(superseded))
        val after = p.markInFlight(s2, superseded)
        val still = after.items.first { it.outboxKey == first.outboxKey }
        assertEquals(PowerOutboxState.SUPERSEDED, still.state)
    }

    @Test
    fun inFlightBlocksParallelImmediateRetry() {
        val p = policy()
        val (item, s1) = enqueue(p, PowerEpisodeOutboxPolicy.State())
        val s2 = p.markInFlight(s1, item)
        val inFlight = s2.items.single()
        assertEquals(PowerOutboxState.IN_FLIGHT, inFlight.state)
        assertFalse(p.canDispatch(inFlight))
        // A second dispatch attempt cannot start while the first is unresolved.
        val again = p.markInFlight(s2, inFlight)
        assertEquals(PowerOutboxState.IN_FLIGHT, again.items.single().state)
    }

    @Test
    fun acceptedReceiptSuppressesDuplicateDelivery() {
        val p = policy()
        val (item, s1) = enqueue(p, PowerEpisodeOutboxPolicy.State())
        val s2 = p.markAccepted(p.markInFlight(s1, item), item)
        val accepted = s2.items.single()
        assertEquals(PowerOutboxState.ACCEPTED, accepted.state)
        assertFalse(p.canDispatch(accepted))
    }

    @Test
    fun uncertainMarksDeliveryUncertainNotFailure() {
        val p = policy()
        val (item, s1) = enqueue(p, PowerEpisodeOutboxPolicy.State())
        val s2 = p.markUncertain(p.markInFlight(s1, item), item)
        val uncertain = s2.items.single()
        assertEquals(PowerOutboxState.DELIVERY_UNCERTAIN, uncertain.state)
        assertFalse(uncertain.state == PowerOutboxState.FAILED_FINAL)
    }

    @Test
    fun failedFinalRetiresEpisodeForNewPowerEpisodeId() {
        val p = policy()
        val (item, s1) = enqueue(p, PowerEpisodeOutboxPolicy.State(), episode = "POWER-1")
        val s2 = p.markFailedFinal(p.markInFlight(s1, item), item)
        val failed = s2.items.single()
        assertEquals(PowerOutboxState.FAILED_FINAL, failed.state)
        assertFalse(p.canDispatch(failed))
        // The caller may open a NEW episode afterwards; its ordinal keeps advancing.
        val (next, _) = enqueue(p, s2, episode = "POWER-2")
        assertEquals(2L, next.transitionOrdinal)
        assertEquals("session-1|POWER-2|2|CONFIRMED_LOSS_OPENING", next.outboxKey)
    }

    @Test
    fun codecRoundTripPreservesWitnessModelAndSetupFlip() {
        val codec = ProtectionProfileCodec()
        val base = ProtectionProfilePolicy(nowMs = { 1_000L }).newStoreState()
        val commissioned = base.copy(
            profiles = base.profiles.toMutableMap().apply {
                val power = getValue(ProtectionProfile.POWER)
                put(
                    ProtectionProfile.POWER,
                    power.copy(
                        setupState = ProfileSetupState.READY,
                        powerWitnessModel = PowerWitnessModel(
                            darkMinLux = 2.0,
                            darkMaxLux = 4.0,
                            litMinLux = 120.0,
                            litMaxLux = 123.0,
                            guardBandLux = 20.0,
                            algorithmVersion = 1,
                            sensorIdentity = "light#1",
                            hoodSignature = "hood-A",
                        ),
                    ),
                )
            },
        )
        assertEquals(commissioned, codec.decode(codec.encode(commissioned)))
        // Default store still round-trips with a null witness model.
        assertEquals(base, codec.decode(codec.encode(base)))
    }

    @Test
    fun legacyPayloadLoadsWithNullWitnessModel() {
        val codec = ProtectionProfileCodec()
        val base = ProtectionProfilePolicy(nowMs = { 1_000L }).newStoreState()
        val root = JSONObject(codec.encode(base))
        // Simulate an older payload written before the POWER witness-model field existed.
        val profiles = root.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            profiles.getJSONObject(i).remove("powerWitnessModel")
        }
        val decoded = codec.decode(root.toString())
        assertNull(decoded.profiles.getValue(ProtectionProfile.POWER).powerWitnessModel)
        assertNull(decoded.profiles.getValue(ProtectionProfile.ENTRY).entryHingeModel)
    }
}
