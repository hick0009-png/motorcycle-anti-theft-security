package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncidentEngineTest {
    private val engine = IncidentEngine(
        idGenerator = IncidentIdGenerator { "incident-1" },
        correlationWindowMs = 15_000L,
    )

    @Test
    fun sustainedVibrationCreatesOneWarningAndRepeatedEvidenceUpdatesIt() {
        val first = engine.accept(
            accepted(SensorKind.VIBRATION, 1_000L, 2.2),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        val second = engine.accept(
            accepted(SensorKind.VIBRATION, 2_000L, 2.4),
            ProtectionState.ALERT_ACTIVE,
        ) as IncidentUpdate.Updated

        assertEquals("incident-1", first.incident.id)
        assertEquals("incident-1", second.incident.id)
        assertEquals(2, second.incident.evidence.size)
        assertEquals(IncidentSeverity.WARNING, second.incident.severity)
        assertEquals(IncidentType.VIBRATION, second.incident.type)
    }

    @Test
    fun vibrationAndLightWithinFifteenSecondsEscalateSameIncidentToCritical() {
        engine.accept(
            accepted(SensorKind.VIBRATION, 1_000L, 2.2),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.LIGHT, 15_999L, 120.0),
            ProtectionState.ALERT_ACTIVE,
        ) as IncidentUpdate.Escalated

        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
        assertEquals(IncidentType.TAMPER, update.incident.type)
        assertEquals(
            setOf(SensorKind.VIBRATION, SensorKind.LIGHT),
            update.incident.evidence.map { it.kind }.toSet(),
        )
    }

    @Test
    fun lightThenVibrationWithinWindowOpensOneCriticalTamperIncident() {
        assertEquals(
            IncidentUpdate.Ignored,
            engine.accept(
                accepted(SensorKind.LIGHT, 1_000L, 120.0),
                ProtectionState.ARMED_HEALTHY,
            ),
        )

        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 15_999L, 2.2),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened

        assertEquals(IncidentSeverity.CRITICAL, update.incident.severity)
        assertEquals(IncidentType.TAMPER, update.incident.type)
        assertEquals(
            listOf(SensorKind.LIGHT, SensorKind.VIBRATION),
            update.incident.evidence.map { it.kind },
        )
    }

    @Test
    fun lightOrAudioAloneDoesNotOpenRealIncident() {
        assertEquals(
            IncidentUpdate.Ignored,
            engine.accept(accepted(SensorKind.LIGHT, 1_000L, 120.0), ProtectionState.ARMED_HEALTHY),
        )
        assertEquals(
            IncidentUpdate.Ignored,
            engine.accept(accepted(SensorKind.MICROPHONE, 2_000L, 0.82), ProtectionState.ARMED_HEALTHY),
        )
    }

    @Test
    fun powerDisconnectIsCriticalButThermalIsMeasuredWarning() {
        val power = engine.accept(
            accepted(SensorKind.POWER_THERMAL, 1_000L, 1.0, "charger_disconnected"),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, power.incident.severity)
        assertEquals(IncidentType.POWER, power.incident.type)

        engine.close(2_000L, "test reset")
        val thermal = engine.accept(
            accepted(SensorKind.POWER_THERMAL, 3_000L, 46.5, "temperature_celsius"),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.WARNING, thermal.incident.severity)
        assertEquals(IncidentType.THERMAL, thermal.incident.type)
        assertEquals(46.5, thermal.incident.evidence.single().normalizedValue, 0.001)
    }

    @Test
    fun quietIncidentClosesOnceInsteadOfRepeatingAlerts() {
        engine.accept(
            accepted(SensorKind.VIBRATION, 1_000L, 2.2),
            ProtectionState.ARMED_HEALTHY,
        )

        assertNull(engine.closeIfQuiet(nowElapsedMs = 30_999L, quietWindowMs = 30_000L))
        val closed = engine.closeIfQuiet(
            nowElapsedMs = 31_000L,
            quietWindowMs = 30_000L,
        ) as IncidentUpdate.Closed
        assertEquals(IncidentLifecycle.CLOSED, closed.incident.lifecycle)
        assertNull(engine.closeIfQuiet(nowElapsedMs = 61_000L, quietWindowMs = 30_000L))
    }

    @Test
    fun observationsAreIgnoredWhenRealProtectionIsNotArmed() {
        assertEquals(
            IncidentUpdate.Ignored,
            engine.accept(accepted(SensorKind.VIBRATION, 1_000L, 2.2), ProtectionState.DISARMED_ONLINE),
        )
    }
}

private fun accepted(
    kind: SensorKind,
    eventElapsedMs: Long,
    normalizedValue: Double,
    diagnostic: String? = null,
): SensorObservation = SensorObservation(
    kind = kind,
    eventElapsedMs = eventElapsedMs,
    wallClockMs = 1_700_000_000_000L + eventElapsedMs,
    normalizedValue = normalizedValue,
    baselineDelta = normalizedValue,
    valid = true,
    diagnostic = diagnostic,
)
