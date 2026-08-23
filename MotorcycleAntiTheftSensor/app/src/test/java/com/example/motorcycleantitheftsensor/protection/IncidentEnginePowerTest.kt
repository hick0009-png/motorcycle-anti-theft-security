package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun recoveredWithoutPowerIncidentIsIgnored() {
        val update = engine.accept(
            powerObservation("power_recovered", 1_000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertEquals(IncidentUpdate.Ignored, update)
        assertFalse(engine.hasActiveIncident)
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
}
