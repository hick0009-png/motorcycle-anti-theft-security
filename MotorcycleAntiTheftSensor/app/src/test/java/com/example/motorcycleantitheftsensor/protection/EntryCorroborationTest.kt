package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The door watch may not raise an alarm on an angle alone.
 *
 * An angle that crossed the threshold is not evidence that a door moved: the reported
 * orientation of a still phone drifts, the baseline is frozen for the whole armed session,
 * and a session that spans a working day gives that drift all day to arrive. A real opening
 * shakes the door; drift does not shake anything. So something else has to have happened
 * nearby, or the verdict does not get to open an incident.
 *
 * The proof to read first is [aDeviceWithoutMovementIsNeverRefused]. Demanding a
 * corroboration the phone cannot produce would silence the door watch on that phone
 * completely, which is a worse failure than the false alert this whole file prevents.
 */
class EntryCorroborationTest {

    private lateinit var engine: IncidentEngine

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "incident-${System.nanoTime()}" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun movement(elapsedMs: Long) = SensorObservation(
        kind = SensorKind.VIBRATION,
        source = SensorSource.ACCELEROMETER,
        capability = SensorCapability.MOVEMENT,
        // What the door watch gives movement: corroborating, never hosting.
        role = SensorRole.SUPPORTING,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = 3.0,
        baselineDelta = 3.0,
        valid = true,
    )

    private fun verdict(elapsedMs: Long, diagnostic: String, angleDeg: Double = 18.0) = SensorObservation(
        kind = SensorKind.VIBRATION,
        source = SensorSource.GAME_ROTATION_VECTOR,
        capability = SensorCapability.MOVEMENT,
        role = SensorRole.PRIMARY,
        unit = SensorUnit.DEGREES,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = angleDeg,
        baselineDelta = angleDeg,
        valid = true,
        diagnostic = diagnostic,
    )

    private fun feed(
        observation: SensorObservation,
        corroborationArmed: Boolean = true,
        doorAngleWatch: Boolean = false,
    ): IncidentUpdate = engine.accept(
        observation = observation,
        protectionState = ProtectionState.ARMED_HEALTHY,
        movementCorroborationArmed = corroborationArmed,
        doorAngleWatch = doorAngleWatch,
    )

    @Test
    fun aDeviceWithoutMovementIsNeverRefused() {
        // The regression this change could cause. A use that runs no movement sensor, or a
        // phone that has none, must keep the door watch it has today.
        val update = feed(verdict(60_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN), corroborationArmed = false)

        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(IncidentType.ENTRY_DOOR, (update as IncidentUpdate.Opened).incident.type)
    }

    @Test
    fun anAngleThatCrossedWithNothingElseHappeningOpensNothing() {
        // Eight hours into an armed session, the angle has walked past the threshold and the
        // night has been completely silent. This is the 03:00 false alert.
        val update = feed(verdict(8L * 3_600_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN))

        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun anAngleWithAShakeBesideItOpensAsBefore() {
        feed(movement(120_000L))
        val update = feed(verdict(122_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN))

        assertTrue(update is IncidentUpdate.Opened)
        val incident = (update as IncidentUpdate.Opened).incident
        assertEquals(IncidentType.ENTRY_DOOR, incident.type)
        assertEquals(IncidentSeverity.WARNING, incident.severity)
    }

    @Test
    fun aShakeAnHourEarlierDoesNotCount() {
        feed(movement(120_000L))
        val update = feed(verdict(120_000L + 3_600_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN))

        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun mountMovedNeedsTheSameProof() {
        // Drift crosses the residual gate before the angle gate on most mountings, and this
        // one is worse than a false door alert: it is critical, and it stops the session from
        // detecting anything else at all.
        val refused = feed(verdict(4L * 3_600_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0))
        assertEquals(IncidentUpdate.Ignored, refused)

        feed(movement(5L * 3_600_000L))
        val accepted = feed(verdict(5L * 3_600_000L + 1_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0))

        assertTrue(accepted is IncidentUpdate.Opened)
        assertEquals(IncidentSeverity.CRITICAL, (accepted as IncidentUpdate.Opened).incident.severity)
    }

    /**
     * The degraded displaced watch has to be able to speak. Reported as an escalation to a
     * severity the owner has already been told, a second displacement is swallowed by the
     * rule that says so — which is right about severity and wrong about this: it is a new
     * fact, that somebody is handling the phone again.
     */
    @Test
    fun aSecondDisplacementOnAnOpenCriticalIncidentStillReachesTheOwner() {
        feed(movement(100_000L))
        val opened = feed(verdict(101_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0))
        assertTrue(opened is IncidentUpdate.Opened)

        feed(movement(400_000L))
        val again = feed(verdict(401_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0))

        assertTrue(again is IncidentUpdate.Updated)
        assertTrue((again as IncidentUpdate.Updated).ownerVisibleConditionChange)
    }

    @Test
    fun aRestoredMountClosesTheDisplacementIncident() {
        feed(movement(100_000L))
        assertTrue(
            feed(verdict(101_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0))
                is IncidentUpdate.Opened,
        )

        val closed = feed(verdict(160_000L, ProtectionDiagnostics.ENTRY_MOUNT_RESTORED, angleDeg = 0.0))

        assertTrue(closed is IncidentUpdate.Closed)
        assertFalse(engine.hasActiveIncident)
    }

    /**
     * The hole this whole file was supposed to close, found in the device's black box after
     * it had already fired three times on a shut door. The orientation sensor the watch reads
     * is also sampled by the general detector set, which forwards its raw angle deltas as
     * movement — so the drift arrived twice, once as the thing to be corroborated and once as
     * the corroboration, and the gate waved it through.
     */
    @Test
    fun theAngleTheDoorWatchReadsMayNotCorroborateItself() {
        // Eight hours in, on a silent night, the raw form of the same drift lands first.
        val driftAsMovement = movement(8L * 3_600_000L)
            .copy(source = SensorSource.GAME_ROTATION_VECTOR)
        feed(driftAsMovement, doorAngleWatch = true)

        val update = feed(
            verdict(8L * 3_600_000L + 1_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN),
            doorAngleWatch = true,
        )

        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun arealShakeStillCorroboratesUnderTheAngleWatch() {
        feed(movement(100_000L), doorAngleWatch = true)

        val update = feed(
            verdict(101_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN),
            doorAngleWatch = true,
        )

        assertTrue(update is IncidentUpdate.Opened)
    }

    @Test
    fun aHealthEpisodeIsNotAnAlarmAndIsNeverRefused() {
        // Losing the orientation source is a fact about the phone, not a claim about a door.
        // Refusing it for want of a shake would hide the one thing the owner has to know.
        val update = feed(verdict(90_000L, ProtectionDiagnostics.ENTRY_SOURCE_UNAVAILABLE, angleDeg = 0.0))

        assertTrue(update is IncidentUpdate.Opened)
    }

    @Test
    fun onceAnEpisodeIsOpenItsOwnUpdatesDoNotNeedANewShake() {
        feed(movement(200_000L))
        assertTrue(feed(verdict(201_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN)) is IncidentUpdate.Opened)

        // The door is still open half an hour later. Nothing is shaking, because nothing is
        // moving — the episode is already believed and closing it is the close path's job.
        val update = feed(verdict(201_000L + 1_800_000L, ProtectionDiagnostics.ENTRY_DOOR_STILL_OPEN))

        assertNotNull(update.incidentOrNull())
        assertTrue(engine.hasActiveIncident)
    }

    @Test
    fun aRefusedVerdictLeavesTheSlotFreeForARealOne() {
        assertEquals(IncidentUpdate.Ignored, feed(verdict(600_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN)))

        feed(movement(700_000L))
        val update = feed(verdict(700_500L, ProtectionDiagnostics.ENTRY_DOOR_OPEN))

        assertTrue(update is IncidentUpdate.Opened)
        assertNull((update as IncidentUpdate.Opened).supersededIncident)
    }

    @Test
    fun theWindowIsMeasuredInBothDirections() {
        assertTrue(EntryCorroborationPolicy.mayOpen(10_000L, 5_000L, corroborationArmed = true))
        assertTrue(EntryCorroborationPolicy.mayOpen(10_000L, 20_000L - 10_000L, corroborationArmed = true))
        assertTrue(EntryCorroborationPolicy.mayOpen(10_000L, 0L, corroborationArmed = true))
        assertFalse(EntryCorroborationPolicy.mayOpen(10_001L, 0L, corroborationArmed = true))
        assertFalse(EntryCorroborationPolicy.mayOpen(10_000L, null, corroborationArmed = true))
        assertTrue(EntryCorroborationPolicy.mayOpen(10_000L, null, corroborationArmed = false))
    }

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        else -> null
    }
}
