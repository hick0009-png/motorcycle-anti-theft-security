package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host proofs for the typed Power Guard incident handling (parent spec sections 4.3/5):
 * one-signal conditions open WARNING POWER incidents that are never outages, confirmed
 * dual loss opens or escalates CRITICAL, recovery closes only a POWER incident, and
 * disarmed states never produce power incidents.
 */
class IncidentEnginePowerTest {

    private lateinit var engine: IncidentEngine

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "power-incident-test" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun powerObservation(diagnostic: String, elapsedMs: Long): SensorObservation = SensorObservation(
        kind = SensorKind.LIGHT,
        source = SensorSource.AMBIENT_LIGHT,
        capability = SensorCapability.LIGHT,
        role = SensorRole.PRIMARY,
        unit = SensorUnit.LUX_RATIO,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = 5.0,
        baselineDelta = 0.0,
        valid = true,
        diagnostic = diagnostic,
    )

    @Test
    fun witnessHealthAlertOpensWarningPowerIncident() {
        val update = engine.accept(
            powerObservation("power_witness_dark", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        update as IncidentUpdate.Opened
        assertEquals(IncidentType.POWER, update.incident.type)
        assertEquals(IncidentSeverity.WARNING, update.incident.severity)
    }

    @Test
    fun chargingHealthAlertOpensWarningPowerIncident() {
        val update = engine.accept(
            powerObservation("power_charging_health", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        update as IncidentUpdate.Opened
        assertEquals(IncidentType.POWER, update.incident.type)
        assertEquals(IncidentSeverity.WARNING, update.incident.severity)
    }

    @Test
    fun confirmedLossOpensCriticalPowerIncident() {
        val update = engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        update as IncidentUpdate.Opened
        assertEquals(IncidentType.POWER, update.incident.type)
        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
    }

    @Test
    fun healthAlertThenConfirmedLossEscalatesToCritical() {
        engine.accept(
            powerObservation("power_witness_dark", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            powerObservation("power_confirmed_loss", 12_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Escalated)
        update as IncidentUpdate.Escalated
        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
    }

    @Test
    fun chargingBackWhileWitnessStaysDarkKeepsTheCriticalIncidentOpenAndTellsTheOwner() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        val update = engine.accept(
            powerObservation("power_partial_witness_dark", 12_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Updated)
        update as IncidentUpdate.Updated
        assertTrue(update.ownerVisibleConditionChange)
        // One signal is still lost, so the reached severity must not be walked back.
        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
        assertEquals(IncidentLifecycle.OPEN, update.incident.lifecycle)
        assertTrue(engine.hasActiveIncident)
    }

    @Test
    fun routineHealthAlertInsideAnOpenIncidentStaysASilentUpdate() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        val update = engine.accept(
            powerObservation("power_witness_dark", 12_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Updated)
        assertFalse((update as IncidentUpdate.Updated).ownerVisibleConditionChange)
    }

    @Test
    fun recoveredClosesOnlyTheOpenPowerIncident() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            powerObservation("power_recovered", 40_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Closed)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun recoveredClosureCarriesRecoveryEvidenceForTelegramCopy() {
        engine.accept(
            powerObservation("power_witness_dark", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        val update = engine.accept(
            powerObservation("power_recovered", 40_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Closed)
        update as IncidentUpdate.Closed
        assertEquals("power_recovered", update.incident.evidence.last().diagnostic)
        assertEquals(
            "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว",
            IncidentMessageFormatter().formatTelegram(update),
        )
    }

    @Test
    fun recoveredWithoutPowerIncidentIsIgnored() {
        val update = engine.accept(
            powerObservation("power_recovered", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun restoredPowerIncidentTreatsTheSameConditionAsAnUpdateNotANewOpening() {
        val opened = engine.accept(
            powerObservation("power_witness_dark", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        val restored = IncidentEngine(
            idGenerator = IncidentIdGenerator { "must-not-open-another-incident" },
            correlationWindowMs = 15_000L,
            restoredActiveIncident = opened.incident,
            restoredAtElapsedMs = 5_000L,
        )

        val update = restored.accept(
            powerObservation("power_witness_dark", 6_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Updated)
        update as IncidentUpdate.Updated
        assertEquals("power-incident-test", update.incident.id)
    }

    @Test
    fun recoveredSignalClosesAPowerIncidentRestoredAfterProcessRestart() {
        val opened = engine.accept(
            powerObservation("power_witness_dark", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        val restored = IncidentEngine(
            idGenerator = IncidentIdGenerator { "must-not-open-another-incident" },
            correlationWindowMs = 15_000L,
            restoredActiveIncident = opened.incident,
            restoredAtElapsedMs = 5_000L,
        )

        val update = restored.accept(
            powerObservation("power_recovered", 35_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Closed)
        update as IncidentUpdate.Closed
        assertEquals("power-incident-test", update.incident.id)
        assertEquals("power_recovered", update.incident.evidence.last().diagnostic)
    }

    @Test
    fun powerVerdictsIgnoredWhenNotArmed() {
        val update = engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.DISARMED_ONLINE,
        )
        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
    }

    private fun vibrationObservation(elapsedMs: Long): SensorObservation = SensorObservation(
        kind = SensorKind.VIBRATION,
        source = SensorSource.ACCELEROMETER,
        capability = SensorCapability.MOVEMENT,
        role = SensorRole.PRIMARY,
        unit = SensorUnit.METERS_PER_SECOND_SQUARED,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = 3.0,
        baselineDelta = 2.0,
        valid = true,
        diagnostic = "movement",
    )

    /**
     * Fix B1: reaching for the charging cable is movement. If a rival incident owns the
     * single active slot, a confirmed power loss used to open a second incident and
     * silently orphan the first, so the owner received two messages for one event.
     */
    @Test
    fun confirmedLossSettlesAForeignActiveIncidentInsteadOfOrphaningIt() {
        val opened = engine.accept(vibrationObservation(1_000L), ProtectionState.ARMED_HEALTHY)
        assertTrue(opened is IncidentUpdate.Opened)
        assertTrue(engine.hasActiveIncident)

        val update = engine.accept(
            powerObservation("power_confirmed_loss", 12_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue(update is IncidentUpdate.Opened)
        update as IncidentUpdate.Opened
        assertEquals(IncidentType.POWER, update.incident.type)
        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
        val superseded = update.supersededIncident
        assertNotNull("the rival incident must be settled, not orphaned", superseded)
        assertEquals(IncidentLifecycle.CLOSED, superseded?.lifecycle)
        assertEquals(IncidentType.VIBRATION, superseded?.type)
        assertEquals("superseded by a confirmed power episode", superseded?.closeReason)
    }

    /**
     * Fix B2: once a power episode owns the incident, movement evidence must not
     * reclassify it into another type. A reclassified incident is no longer a POWER
     * incident, so the recovery verdict was discarded and the owner never learned the
     * supply came back.
     */
    @Test
    fun movementEvidenceCannotReclassifyAnOpenPowerIncidentSoRecoveryStillCloses() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        engine.accept(vibrationObservation(5_000L), ProtectionState.ARMED_HEALTHY)

        val update = engine.accept(
            powerObservation("power_recovered", 20_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue("recovery must close the power incident", update is IncidentUpdate.Closed)
        update as IncidentUpdate.Closed
        assertEquals(IncidentType.POWER, update.incident.type)
        assertEquals(
            "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว",
            IncidentMessageFormatter().formatTelegram(update),
        )
        assertFalse(engine.hasActiveIncident)
    }

    /**
     * Fix: the 30-second quiet window is a movement-domain rule — "no more vibration,
     * the event is over". A power episode is quiet precisely while the supply is still
     * cut: the arbiter fires one verdict and an on-change light sensor reports nothing
     * more while the lamp stays dark. Closing on silence sent the owner
     * "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว" while the cable was still out, and left
     * the real recovery with no incident to close, so switching the lamp back on was
     * met with silence.
     */
    @Test
    fun theQuietWindowNeverClosesAPowerEpisode() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        val closed = engine.closeIfQuiet(nowElapsedMs = 91_000L, quietWindowMs = 30_000L)

        assertNull("silence is not recovery for a power episode", closed)
        assertTrue(engine.hasActiveIncident)
    }

    @Test
    fun aPowerEpisodeKeptOpenBySilenceStillClosesOnItsOwnRecovery() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        engine.closeIfQuiet(nowElapsedMs = 91_000L, quietWindowMs = 30_000L)

        val update = engine.accept(
            powerObservation("power_recovered", 120_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        assertTrue("recovery must still own the close", update is IncidentUpdate.Closed)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun disarmStillClosesAPowerEpisodeThatNeverRecovered() {
        engine.accept(
            powerObservation("power_confirmed_loss", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )

        val closed = engine.close(nowMs = 1_700_000_120_000L, reason = "owner disarmed")

        assertNotNull("an episode must never outlive the armed session", closed)
        assertFalse(engine.hasActiveIncident)
    }

    @Test
    fun theQuietWindowStillClosesAMovementIncident() {
        engine.accept(vibrationObservation(1_000L), ProtectionState.ARMED_HEALTHY)

        val closed = engine.closeIfQuiet(nowElapsedMs = 31_000L, quietWindowMs = 30_000L)

        assertNotNull("stillness is what ends a movement incident", closed)
        assertFalse(engine.hasActiveIncident)
    }
}
