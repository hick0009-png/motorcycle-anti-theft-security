package com.example.motorcycleantitheftsensor.protection

import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentMessageFormatterTest {
    private val formatter = IncidentMessageFormatter(
        timeZone = TimeZone.getTimeZone("UTC"),
    )

    @Test
    fun demoMessageIsExplicitAndUnavailableLocationIsHonest() {
        val demo = criticalDemoIncident().copy(
            evidence = criticalDemoIncident().evidence + IncidentEvidence(
                kind = SensorKind.LOCATION,
                eventElapsedMs = 1_000L,
                wallClockMs = 1_000L,
                normalizedValue = 0.0,
                baselineDelta = 0.0,
                diagnostic = "no recent fix",
            ),
        )

        val message = formatter.format(demo)

        assertTrue(message.startsWith("DEMO — CRITICAL TAMPER"))
        assertTrue(message.contains("Location: unavailable (no recent fix)"))
        assertTrue(message.contains("Protection: ALERT_ACTIVE"))
        assertTrue(message.contains("Incident: demo-1"))
    }

    @Test
    fun audioEvidenceIsDescribedAsRelativeAmplitudeNotCalibratedDecibels() {
        val incident = warningRealIncident().copy(
            type = IncidentType.AUDIO,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.MICROPHONE,
                    eventElapsedMs = 1_000L,
                    wallClockMs = 1_000L,
                    normalizedValue = 0.82,
                    baselineDelta = 0.22,
                    diagnostic = "relative_amplitude",
                ),
            ),
        )

        val message = formatter.format(incident)

        assertTrue(message.contains("Relative audio amplitude: 0.82"))
        assertFalse(message.contains("dB"))
        assertFalse(message.contains("power cutoff", ignoreCase = true))
    }
}

private fun criticalDemoIncident(): SecurityIncident = incident(
    id = "demo-1",
    updatedAtMs = 1_000L,
    source = IncidentSource.DEMO,
    severity = IncidentSeverity.CRITICAL,
).copy(type = IncidentType.TAMPER)

private fun warningRealIncident(): SecurityIncident = incident(
    id = "real-1",
    updatedAtMs = 1_000L,
    source = IncidentSource.REAL,
    severity = IncidentSeverity.WARNING,
)
