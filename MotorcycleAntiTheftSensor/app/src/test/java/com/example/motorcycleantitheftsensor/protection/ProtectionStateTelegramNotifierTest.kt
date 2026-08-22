package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionStateTelegramNotifierTest {
    @Test
    fun generatesExpectedMessagesForStateTransitions() {
        val notifier = ProtectionStateTelegramNotifier()

        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์"),
            notifier.messagesFor(ProtectionState.DISARMED_ONLINE, ProtectionState.ARMING)
        )
        assertEquals(
            listOf("ℹ️ กำลังเปิดการป้องกัน รอการปรับเทียบเซนเซอร์"),
            notifier.messagesFor(ProtectionState.SETUP_REQUIRED, ProtectionState.ARMING)
        )
        assertEquals(
            listOf("✅ การป้องกันทำงานปกติ"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY)
        )
        // Transient warmup reasons (GPS lock wait, mic noise floor calibration) resolve to healthy
        assertEquals(
            listOf("✅ การป้องกันทำงานปกติ"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED, setOf("LOCATION not healthy", "MICROPHONE not healthy"))
        )
        // Hard failure reasons format in Thai
        assertEquals(
            listOf("⚠️ การป้องกันทำงานแบบจำกัด: เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED, setOf("VIBRATION not healthy"))
        )
        // Disarm from ARMING with failure degradation reasons reports failure
        assertEquals(
            listOf("⚠️ การเปิดระบบล้มเหลว: SENSOR_INIT_FAILED"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.DISARMED_ONLINE, setOf("SENSOR_INIT_FAILED"))
        )
        // Disarm from ARMING without degradation reasons reports normal disarm
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ARMING, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            listOf("ℹ️ ปิดการป้องกันแล้ว"),
            notifier.messagesFor(ProtectionState.ALERT_ACTIVE, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(null, ProtectionState.DISARMED_ONLINE)
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_HEALTHY)
        )
        // Flapping between ARMED_HEALTHY and ARMED_DEGRADED must be silent to prevent message loops
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.ARMED_DEGRADED, setOf("TELEGRAM unreachable"))
        )
        assertEquals(
            emptyList<String>(),
            notifier.messagesFor(ProtectionState.ARMED_DEGRADED, ProtectionState.ARMED_HEALTHY)
        )
    }
}
