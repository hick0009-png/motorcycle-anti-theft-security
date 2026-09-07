package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The close of an Entry incident is delivered as a message, and that message must say the
 * door shut — not repeat whatever was last said while it was open.
 *
 * The formatter chooses its copy from the last entry diagnostic recorded on the incident, and
 * the engine closes an incident by copying it to CLOSED. For a while it did that without first
 * landing the terminal verdict as evidence, so a confirmed close was rendered from the stale
 * diagnostic before it — a mount-moved, or a door-open — and the owner was told the phone had
 * been moved, or that the door was still open, on the very message that meant it had finally
 * closed. It is the whole path that has to be exercised, verdict observation through the engine
 * to the formatted string: a formatter test that hand-builds the closed incident's evidence
 * (as [IncidentMessageFormatterTest] does) never sees what the engine actually leaves on it,
 * which is exactly how this passed review while the phone in the field said the wrong thing.
 */
class EntryCloseMessageIntegrationTest {

    private lateinit var engine: IncidentEngine
    private val formatter = IncidentMessageFormatter()

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "incident-${System.nanoTime()}" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun entry(elapsedMs: Long, diagnostic: String, angleDeg: Double = 18.0) = SensorObservation(
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

    private fun feed(observation: SensorObservation): IncidentUpdate = engine.accept(
        observation = observation,
        protectionState = ProtectionState.ARMED_HEALTHY,
        // No movement signal on this device, so an entry verdict hosts on its own — the
        // corroboration gate is the subject of EntryCorroborationTest, not of this file.
        movementCorroborationArmed = false,
        doorAngleWatch = true,
    )

    @Test
    fun aDoorThatOpenedThenClosedIsAnnouncedAsClosed() {
        assertTrue(feed(entry(10_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN)) is IncidentUpdate.Opened)

        val closed = feed(entry(40_000L, ProtectionDiagnostics.ENTRY_DOOR_CLOSED, angleDeg = 0.0))

        assertTrue(closed is IncidentUpdate.Closed)
        assertFalse(engine.hasActiveIncident)
        assertEquals("ประตูปิดและนิ่งแล้ว", formatter.format(closed))
    }

    /**
     * The reported field failure. A shake during the opening tripped the residual gate and
     * raised a mount-moved, escalating the open-door incident to critical. When the door was
     * then shut, the confirmed close borrowed the mount-moved copy, and the owner saw "the
     * phone was moved, recalibrate" three times and never a word that the door had closed.
     */
    @Test
    fun aDoorClosedAfterAMountMovedIsStillAnnouncedAsClosed() {
        assertTrue(feed(entry(10_000L, ProtectionDiagnostics.ENTRY_DOOR_OPEN)) is IncidentUpdate.Opened)
        assertTrue(feed(entry(20_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0)) is IncidentUpdate.Escalated)

        val closed = feed(entry(80_000L, ProtectionDiagnostics.ENTRY_DOOR_CLOSED, angleDeg = 0.0))

        assertTrue(closed is IncidentUpdate.Closed)
        val message = formatter.format(closed)
        assertEquals("ประตูปิดและนิ่งแล้ว", message)
        assertNotEquals("โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่", message)
    }

    /**
     * A displacement that came back on its own resolves the displacement incident, and the
     * close says the mount returned — not the stale mount-moved it is resolving.
     */
    @Test
    fun aRestoredMountIsAnnouncedAsRestoredNotAsStillDisplaced() {
        assertTrue(feed(entry(10_000L, ProtectionDiagnostics.ENTRY_MOUNT_MOVED, angleDeg = 0.0)) is IncidentUpdate.Opened)

        val closed = feed(entry(70_000L, ProtectionDiagnostics.ENTRY_MOUNT_RESTORED, angleDeg = 0.0))

        assertTrue(closed is IncidentUpdate.Closed)
        assertEquals(
            "โทรศัพท์กลับเข้าตำแหน่งเดิมแล้ว การเฝ้าประตูทำงานต่อตามปกติ",
            formatter.format(closed),
        )
    }
}
