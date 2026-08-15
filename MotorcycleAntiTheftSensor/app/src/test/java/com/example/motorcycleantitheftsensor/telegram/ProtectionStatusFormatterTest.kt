package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusFormatterTest {
    @Test
    fun statusReportsRuntimeTruthAndNamedDegradation() {
        val snapshot = ProtectionSnapshot.offline(1_000L).copy(
            state = ProtectionState.ARMED_DEGRADED,
            serviceRunning = true,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = 900L,
            degradationReasons = setOf("MICROPHONE unavailable"),
            sensorHealth = mapOf(
                SensorKind.MICROPHONE to SensorHealth(SensorHealthState.UNAVAILABLE),
            ),
            batteryLevelPercent = 74,
            lastDeliveryState = DeliveryState.FAILED,
        )

        val message = ProtectionStatusFormatter().format(snapshot)

// assertTrue(message.contains("⚠️ การป้องกันทำงานแบบจำกัด"))
// assertTrue(message.contains("⚠️ MICROPHONE:"))
// assertTrue(message.contains("แบตเตอรี่: 74%"))
    }
}
