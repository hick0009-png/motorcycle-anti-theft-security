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
    ): IncidentUpdate = engine.accept(
        observation = observation,
        protectionState = ProtectionState.ARMED_HEALTHY,
        movementCorroborationArmed = corroborationArmed,
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
