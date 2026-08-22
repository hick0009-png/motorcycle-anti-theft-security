package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationTextCatalogTest {

    @Test
    fun allCapabilitiesHaveNonEmptyThaiNames() {
        SensorCapability.entries.forEach { capability ->
            val name = PresentationTextCatalog.capabilityName(capability)
            assertNotNull(name)
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun allSourcesHaveNonEmptyThaiNames() {
        SensorSource.entries.forEach { source ->
            val name = PresentationTextCatalog.sourceName(source)
            assertNotNull(name)
            assertTrue(name.isNotBlank())
        }
    }

    @Test
    fun neutralIncidentGuidanceMatchesApprovedConstant() {
        assertEquals("ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม", PresentationTextCatalog.NEUTRAL_INCIDENT_GUIDANCE)
    }

    @Test
    fun smsFormattingNeverContainsLocation() {
        val incident = SecurityIncident(
            id = "INC-TEST-001",
            type = IncidentType.VIBRATION,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            evidence = emptyList(),
            openedAtMs = 1787245200000L,
            updatedAtMs = 1787245200000L,
            closedAtMs = null,
            protectionState = ProtectionState.ARMED_HEALTHY,
            deliveryState = DeliveryState.SENT,
            location = IncidentLocation(13.7563, 100.5018, 10f, 1787245200000L),
        )

        val presentation = IncidentMessagePresentationFactory.create(incident)
        val sms = IncidentMessagePresentationFactory.formatSmsMessage(presentation)

        assertFalse("SMS fallback must never leak GPS coordinates", sms.contains("13.7563"))
        assertFalse("SMS fallback must never leak GPS coordinates", sms.contains("100.5018"))
        assertTrue(sms.contains("INC-TEST-001"))
        assertTrue(sms.contains("วิกฤต"))
    }
}
