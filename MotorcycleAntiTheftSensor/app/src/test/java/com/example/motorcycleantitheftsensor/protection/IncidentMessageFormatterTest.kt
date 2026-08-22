package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentMessageFormatterTest {
    private val formatter = IncidentMessageFormatter {
        ProtectionSnapshot.offline(0L).copy(
            batteryLevelPercent = 82,
            batteryTemperatureCelsius = 31.5f
        )
    }

    @Test
    fun formatsIncidentWithEvidenceCorrectly() {
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.LIGHT,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 20.0,
                baselineDelta = 15.2,
                diagnostic = "ambient_lux"
            ),
            IncidentEvidence(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 12.3,
                baselineDelta = 2.5,
                diagnostic = "accelerometer"
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        val expectedBase = UserGuidanceCatalog.content(GuidanceCode.INCIDENT_OPENED).telegramTh!!.replace("{incidentType}", "TAMPER")

        assertTrue(message.contains(expectedBase))
        assertTrue(message.contains("รายละเอียด:"))
        assertTrue(message.contains("แสงสว่างลอดเข้าใต้เบาะ (ambient_lux): ตรวจพบความสว่างเปลี่ยนไป Δ 15.20"))
        assertTrue(message.contains("รถถูกขยับหรือมุมเอียงเปลี่ยนไป (accelerometer): ตรวจพบการเอียง/สั่น Δ 2.50"))
        assertTrue(message.contains("🔋 แบตเตอรี่: 82% (31.5°C)"))
    }

    @Test
    fun formatsOpenedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        assertTrue(message.contains(UserGuidanceCatalog.content(GuidanceCode.INCIDENT_OPENED).telegramTh!!.replace("{incidentType}", "TAMPER")))
    }

    @Test
    fun formatsInterruptedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.AUDIO,
            lifecycle = IncidentLifecycle.INTERRUPTED
        )

        val message = formatter.format(incident)
        assertTrue(message.contains(UserGuidanceCatalog.content(GuidanceCode.INCIDENT_OPENED).telegramTh!!.replace("{incidentType}", "AUDIO")))
    }

    @Test
    fun formatsClosedIncidentCorrectly() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.CLOSED
        )

        val message = formatter.format(incident)
        assertEquals(UserGuidanceCatalog.content(GuidanceCode.INCIDENT_CLOSED).telegramTh!!, message)
    }

    @Test
    fun noGpsDataInOrdinaryIncident() {
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = 0L,
                wallClockMs = 0L,
                normalizedValue = 12.3,
                baselineDelta = 2.5,
                diagnostic = "accelerometer"
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.format(incident)
        assertTrue("Ordinary incident must not contain GPS", !message.contains("ตำแหน่ง"))
        assertTrue("Ordinary incident must not contain lat/lon", !message.contains("lat="))
    }

    @Test
    fun formatTelegramWithPresentationIncludesMapsUrlAndAccuracyAndLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )
        val presentation = LocationPresentation(
            labelTh = "ถ.สุขุมวิท, วัฒนา, กรุงเทพฯ",
            mapsUrl = "https://maps.google.com/?q=13.756300,100.501800",
            accuracyMeters = 8,
        )

        val message = formatter.formatTelegram(incident, presentation)
        assertTrue(message.contains("📍 ตำแหน่ง: ถ.สุขุมวิท, วัฒนา, กรุงเทพฯ"))
        assertTrue(message.contains("🗺️ แผนที่: https://maps.google.com/?q=13.756300,100.501800 (ความแม่นยำ ~8m)"))
    }

    @Test
    fun formatTelegramWithoutPresentationExcludesMapsUrlAndLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            lifecycle = IncidentLifecycle.OPEN
        )

        val message = formatter.formatTelegram(incident, null)
        assertFalse(message.contains("🗺️ แผนที่:"))
        assertFalse(message.contains("📍 ตำแหน่ง:"))
        assertFalse(message.contains("maps.google.com"))
    }

    @Test
    fun formatSmsNeverIncludesMapsUrlCoordinatesOrLabel() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 12.3,
                    baselineDelta = 2.5,
                    diagnostic = "accelerometer"
                ),
                IncidentEvidence(
                    kind = SensorKind.LOCATION,
                    eventElapsedMs = 0L,
                    wallClockMs = 0L,
                    normalizedValue = 1.0,
                    baselineDelta = 0.0,
                    diagnostic = "fix age_ms=100 accuracy_m=8.0"
                )
            ),
            lifecycle = IncidentLifecycle.OPEN,
            location = IncidentLocation(13.7563, 100.5018, 8f, 1000L)
        )

        val message = formatter.formatSms(incident)
        assertFalse(message.contains("maps.google.com"))
        assertFalse(message.contains("13.7563"))
        assertFalse(message.contains("100.5018"))
        assertFalse(message.contains("ตำแหน่ง"))
        assertFalse(message.contains("พิกัด"))
    }

    @Test
    fun formatsTypedAudioThreatEvidenceCorrectly() {
        val audioThreat = AudioThreatMetadata(
            category = AudioThreatCategory.POWER_TOOL,
            confidence = 0.92,
            loudnessDeltaDb = 14.5,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 2000L,
            occurrenceCount = 2,
            onsetElapsedMs = 1000L,
            onsetCoherent = true,
        )
        val evidenceList = listOf(
            IncidentEvidence(
                kind = SensorKind.MICROPHONE,
                eventElapsedMs = 1000L,
                wallClockMs = 1000L,
                normalizedValue = 0.92,
                baselineDelta = 14.5,
                diagnostic = "power_tool",
                audioThreat = audioThreat,
            )
        )
        val incident = criticalIncident().copy(
            type = IncidentType.AUDIO,
            evidence = evidenceList,
            lifecycle = IncidentLifecycle.OPEN,
        )

        val message = formatter.format(incident)
        assertTrue(message.contains("เสียง: เสียงเครื่องมือช่าง/หินเจียร์ (ความมั่นใจ 92%, +14.5 dB, 2 ครั้ง) [ตรงกับการสั่น]"))
    }

    @Test
    fun formatsEscalatedIncidentWithDistinctHeaderAndUrgencyMarker() {
        val incident = criticalIncident().copy(
            type = IncidentType.TAMPER,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    eventElapsedMs = 1000L,
                    wallClockMs = 1000L,
                    normalizedValue = 15.0,
                    baselineDelta = 5.0,
                    diagnostic = "accelerometer",
                )
            ),
            lifecycle = IncidentLifecycle.OPEN,
        )
        val update = IncidentUpdate.Escalated(incident)

        val message = formatter.format(update)
        assertTrue("Message must contain escalated header", message.contains("🚨 เหตุยกระดับเป็นวิกฤต"))
        assertTrue("Message must contain urgency marker", message.contains("⚠️ ระดับความรุนแรง: วิกฤต (CRITICAL)"))
        assertTrue("Message must contain evidence chain", message.contains("รถถูกขยับหรือมุมเอียงเปลี่ยนไป"))
        assertFalse("Message must not contain opened header", message.contains("🚨 ตรวจพบ TAMPER"))
    }

    private fun criticalIncident() = SecurityIncident(
        id = "incident-1",
        severity = IncidentSeverity.CRITICAL,
        type = IncidentType.TAMPER,
        lifecycle = IncidentLifecycle.OPEN,
        evidence = emptyList(),
        openedAtMs = 0L,
        closedAtMs = null,
        protectionState = ProtectionState.ALERT_ACTIVE,
        updatedAtMs = 0L,
        deliveryState = DeliveryState.PENDING,
    )
}
