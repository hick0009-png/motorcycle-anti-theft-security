package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossChannelMessageConsistencyTest {

    @Test
    fun crossChannelPresentationsPreserveSameSeverityAndIncidentId() {
        val incident = SecurityIncident(
            id = "INC-20260820-9999",
            type = IncidentType.VIBRATION,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.VIBRATION,
                    source = SensorSource.ACCELEROMETER,
                    capability = SensorCapability.MOVEMENT,
                    role = SensorRole.PRIMARY,
                    unit = SensorUnit.METERS_PER_SECOND_SQUARED,
                    eventElapsedMs = 12345L,
                    wallClockMs = 1787245200000L,
                    normalizedValue = 18.0,
                    baselineDelta = 8.2,
                    diagnostic = null,
                )
            ),
            openedAtMs = 1787245200000L,
            updatedAtMs = 1787245200000L,
            closedAtMs = null,
            protectionState = ProtectionState.ALERT_ACTIVE,
            deliveryState = DeliveryState.SENT,
            location = IncidentLocation(13.7563, 100.5018, 15f, 1787245200000L),
        )

        val presentation = IncidentMessagePresentationFactory.create(incident)
        val telegram = IncidentMessagePresentationFactory.formatTelegramMessage(presentation)
        val sms = IncidentMessagePresentationFactory.formatSmsMessage(presentation)

        // Both channels must contain the exact same incident ID and severity
        assertTrue(telegram.contains("INC-20260820-9999"))
        assertTrue(sms.contains("INC-20260820-9999"))

        assertTrue(telegram.contains("วิกฤต"))
        assertTrue(sms.contains("วิกฤต"))

        // Telegram contains location label, SMS MUST NOT leak any GPS coordinates
        assertTrue(telegram.contains("ความแม่นยำประมาณ 15 เมตร"))
        assertFalse(sms.contains("13.7563"))
        assertFalse(sms.contains("100.5018"))
        assertFalse(sms.contains("ความแม่นยำ"))

        // Neutral guidance must be consistent
        assertEquals("ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม", presentation.recommendedAction)
        assertTrue(telegram.contains("ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม"))
    }
}
