package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What every channel must never say, asked of the formatter that actually ships.
 *
 * These contracts used to be proved against a second presentation pipeline that no production
 * code path called: a message could have leaked a coordinate through Telegram or SMS for as
 * long as the app has existed and this file would still have passed. The pipeline is gone; the
 * contracts stay, pointed at [IncidentMessageFormatter], which is what the delivery coordinator
 * sends. Only the promises that are about the message are kept — the assertions about the dead
 * presentation object's own fields went with it, and so did its rule that SMS must carry no
 * coordinates: production deliberately sends them (there is no map link in an SMS and the owner
 * needs the location), which `IncidentMessageFormatterTest` states and proves against the live
 * formatter. A rule that only the unreachable pipeline obeyed was never a rule.
 */
class CrossChannelMessageConsistencyTest {

    private val formatter = IncidentMessageFormatter()

    private fun incident(
        type: IncidentType = IncidentType.VIBRATION,
        deliveryState: DeliveryState = DeliveryState.SENT,
    ) = SecurityIncident(
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
        location = IncidentLocation(13.7563, 100.5018, 15f, 1787245200000L),
    )

    @Test
    fun neitherChannelEchoesSecretsOrCommands() {
        val banned = listOf("token", "chat_id", "chatId", "pairing", "/pair", "/sensitivity", "AES", "secret")
        listOf(formatter.format(incident()), formatter.formatSms(incident())).forEach { message ->
            banned.forEach { fragment ->
                assertFalse(
                    "banned '$fragment' leaked into a channel message: '$message'",
                    message.contains(fragment, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun singleSensorEvidenceNeverClaimsTheftOrForcedEntry() {
        val telegram = formatter.format(incident())
        listOf("ถูกขโมย", "ลักทรัพย์").forEach { claim ->
            assertFalse("single-sensor message must not claim theft: '$telegram'", telegram.contains(claim))
        }
    }

    @Test
    fun powerCopyNeverClaimsABuildingOutage() {
        val telegram = formatter.format(incident(type = IncidentType.POWER))
        assertFalse(
            "power copy must not claim a building outage: '$telegram'",
            telegram.contains("อาคาร"),
        )
    }

    @Test
    fun doorCopyNeverSpeaksAboutAVehicle() {
        val telegram = formatter.format(incident(type = IncidentType.ENTRY_DOOR))
        listOf("รถ", "ยานพาหนะ").forEach { claim ->
            assertFalse("door copy must not speak vehicle words: '$telegram'", telegram.contains(claim))
        }
    }
}
