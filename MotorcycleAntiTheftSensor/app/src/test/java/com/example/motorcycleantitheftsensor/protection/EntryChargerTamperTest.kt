package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bugs #2 and #3: under a door watch the charging line is tamper with the guarding phone, not a
 * supply signal. A pulled charger must warn — a thief who only unplugs the phone must still be
 * heard — but in door words, and it must never open or relabel the episode as a POWER outage.
 *
 * The failure this pins came off the field black box: a nudged charger in door mode retyped the
 * open door episode to POWER and the owner was told "แหล่งจ่ายไฟผิดปกติ" while watching a door.
 * The whole path is exercised, charger observation through the engine to the formatted string,
 * because the retype happened inside the engine's classification and never showed in a formatter
 * test that hand-built the incident's type.
 */
class EntryChargerTamperTest {

    private lateinit var engine: IncidentEngine
    private val formatter = IncidentMessageFormatter()

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "incident-${System.nanoTime()}" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun door(elapsedMs: Long, diagnostic: String, angleDeg: Double = 18.0) = SensorObservation(
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

    private fun chargerPull(elapsedMs: Long) = SensorObservation(
        kind = SensorKind.POWER_THERMAL,
        // Stamped SUPPORTING the way signalRoles(ENTRY, DOOR_ANGLE) stamps the charging line;
        // the door-mode tamper path hosts it regardless, and this proves it does not lean on a
        // primary role to do so.
        role = SensorRole.SUPPORTING,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = 1.0,
        baselineDelta = 0.0,
        valid = true,
        diagnostic = ProtectionDiagnostics.CHARGER_DISCONNECTED,
    )

    private fun feed(observation: SensorObservation): IncidentUpdate = engine.accept(
        observation = observation,
        protectionState = ProtectionState.ARMED_HEALTHY,
        movementCorroborationArmed = false,
        doorAngleWatch = true,
    )

    private val doorModeChargerCopy = "สายชาร์จของโทรศัพท์ที่เฝ้าประตูถูกถอด อาจมีคนแตะโทรศัพท์ กรุณาตรวจสอบ"

    @Test
    fun aChargerPullInDoorModeOpensADoorCriticalOnItsOwnNotAPowerIncident() {
        val opened = feed(chargerPull(10_000L))

        assertTrue(opened is IncidentUpdate.Opened)
        val incident = (opened as IncidentUpdate.Opened).incident
        assertEquals(IncidentType.ENTRY_DOOR, incident.type)
        assertEquals(IncidentSeverity.CRITICAL, incident.severity)

        val message = formatter.format(opened)
        assertEquals(doorModeChargerCopy, message)
        assertFalse(message.contains("แหล่งจ่ายไฟ"))
        assertFalse(message.contains("ไฟเลี้ยง"))
    }

    @Test
    fun aChargerPullDuringAnOpenDoorEpisodeEscalatesItInDoorWordsWithoutBecomingPower() {
        assertTrue(feed(door(10_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN, angleDeg = 22.0)) is IncidentUpdate.Opened)

        val escalated = feed(chargerPull(20_000L))

        assertTrue(escalated is IncidentUpdate.Escalated)
        val incident = (escalated as IncidentUpdate.Escalated).incident
        assertEquals("the door episode must not be relabelled POWER", IncidentType.ENTRY_DOOR, incident.type)
        assertEquals(IncidentSeverity.CRITICAL, incident.severity)
        assertEquals(doorModeChargerCopy, formatter.format(escalated))
    }

    @Test
    fun theDoorEpisodeAChargerPullOpenedStillClosesOnADoorClosedVerdict() {
        assertTrue(feed(chargerPull(10_000L)) is IncidentUpdate.Opened)

        val closed = feed(door(60_000L, ProtectionDiagnostics.ENTRY_DOOR_CLOSED, angleDeg = 0.0))

        assertTrue(closed is IncidentUpdate.Closed)
        assertFalse(engine.hasActiveIncident)
        assertEquals("ประตูปิดและนิ่งแล้ว", formatter.format(closed))
    }
}
