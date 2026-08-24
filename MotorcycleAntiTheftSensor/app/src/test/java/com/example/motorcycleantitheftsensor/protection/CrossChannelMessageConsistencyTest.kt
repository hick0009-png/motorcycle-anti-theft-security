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

    private fun incident(deliveryState: DeliveryState, type: IncidentType = IncidentType.VIBRATION) =
        SecurityIncident(
            id = "INC-CROSS-1",
            type = type,
            severity = IncidentSeverity.CRITICAL,
            lifecycle = IncidentLifecycle.OPEN,
            evidence = emptyList(),
            openedAtMs = 1787245200000L,
            updatedAtMs = 1787245200000L,
            closedAtMs = null,
            protectionState = ProtectionState.ALERT_ACTIVE,
            deliveryState = deliveryState,
        )

    @Test
    fun deliverySummaryMatchesSharedCatalogLabelForEveryTypedState() {
        DeliveryState.entries.forEach { state ->
            val presentation = IncidentMessagePresentationFactory.create(incident(state))
            assertEquals(
                "delivery summary must equal the shared catalog label for $state",
                PresentationTextCatalog.deliveryStateLabel(state),
                presentation.deliverySummary,
            )
        }
    }

    @Test
    fun deliveryIsNeverCalledSuccessfulBeforeRecordedSentState() {
        listOf(DeliveryState.PENDING, DeliveryState.FAILED, DeliveryState.NOT_ELIGIBLE).forEach { state ->
            val summary = IncidentMessagePresentationFactory.create(incident(state)).deliverySummary
            assertFalse("state $state must not read as successful: '$summary'", summary.contains("ส่งสำเร็จ"))
            assertFalse("state $state must not read as done: '$summary'", summary.contains("แล้ว"))
        }
    }

    @Test
    fun sharedChannelsNeverEchoSecretsOrVehicleOnlyWording() {
        val telegram = IncidentMessagePresentationFactory.formatTelegramMessage(
            IncidentMessagePresentationFactory.create(incident(DeliveryState.SENT)),
        )
        val sms = IncidentMessagePresentationFactory.formatSmsMessage(
            IncidentMessagePresentationFactory.create(incident(DeliveryState.SENT)),
        )
        val banned = listOf(
            "token",
            "chat_id",
            "chatId",
            "pairing",
            "/pair",
            "/sensitivity",
            "AES",
            "secret",
            "สถานะรถ",
            "ไฟดับทั้งอาคาร",
        )
        listOf(telegram, sms).forEach { message ->
            banned.forEach { fragment ->
                assertFalse(
                    "banned '$fragment' leaked into shared channel message: '$message'",
                    message.contains(fragment, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun smsContainsNoGpsWordingOrMapLinks() {
        val sms = IncidentMessagePresentationFactory.formatSmsMessage(
            IncidentMessagePresentationFactory.create(incident(DeliveryState.SENT)),
        )
        listOf("GPS", "พิกัด", "latitude", "longitude", "maps.", "http", "13.7563", "100.5018").forEach { banned ->
            assertFalse("SMS leaked '$banned'", sms.contains(banned, ignoreCase = true))
        }
    }

    @Test
    fun singleSensorEvidenceNeverClaimsTheftOrForcedEntry() {
        val telegram = IncidentMessagePresentationFactory.formatTelegramMessage(
            IncidentMessagePresentationFactory.create(incident(DeliveryState.SENT)),
        )
        listOf("ถูกขโมย", "ลักทรัพย์").forEach { claim ->
            assertFalse(
                "single-sensor message must not claim theft: '$telegram'",
                telegram.contains(claim),
            )
        }
    }

    @Test
    fun powerIncidentSharedCopyNeverClaimsBuildingOutage() {
        val telegram = IncidentMessagePresentationFactory.formatTelegramMessage(
            IncidentMessagePresentationFactory.create(incident(DeliveryState.SENT, IncidentType.POWER)),
        )
        assertFalse(
            "power copy must not claim a building outage: '$telegram'",
            telegram.contains("อาคาร"),
        )
    }
}
