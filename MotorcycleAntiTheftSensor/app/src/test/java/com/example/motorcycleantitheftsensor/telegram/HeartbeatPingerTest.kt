package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

class HeartbeatPingerTest {

    @Test
    fun startAndStopHeartbeatLifecycle() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(EncryptedPrefsManager::class.java)
        val mockTelegram = mock(TelegramBotClient::class.java)

        `when`(mockPrefs.getAllowedChatIds()).thenReturn(setOf("12345"))
        `when`(mockPrefs.isSystemArmed()).thenReturn(true)

        val pinger = HeartbeatPinger(mockContext, mockPrefs, mockTelegram)
        assertNotNull(pinger)

        pinger.startHeartbeat()
        // Idempotent start
        pinger.startHeartbeat()

        pinger.stopHeartbeat()
        // Idempotent stop
        pinger.stopHeartbeat()
    }

    @Test
    fun heartbeatSendsProfessionalBilingualArmedStatus() {
        val mockContext = mock(Context::class.java)
        val mockPrefs = mock(EncryptedPrefsManager::class.java)
        val mockTelegram = mock(TelegramBotClient::class.java)
        `when`(mockPrefs.getAllowedChatIds()).thenReturn(setOf("12345"))
        `when`(mockPrefs.isSystemArmed()).thenReturn(true)
        val pinger = HeartbeatPinger(mockContext, mockPrefs, mockTelegram)

        HeartbeatPinger::class.java
            .getDeclaredMethod("sendHeartbeatPing")
            .apply { isAccessible = true }
            .invoke(pinger)

        val messageCaptor = argumentCaptor<String>()
        verify(mockTelegram).sendTelegramMessage(eq("12345"), messageCaptor.capture())
        val message = messageCaptor.firstValue
        assertTrue(message.startsWith(
            "รายงานสถานะระบบ / System Status\n" +
                "บริการ: ออนไลน์ | Service: Online\n" +
                "การป้องกัน: เปิดใช้งาน | Protection: Armed\n" +
                "รายงานเมื่อ: "
        ))
        assertTrue(message.lineSequence().last().matches(
            Regex("รายงานเมื่อ: \\d{1,2} [ก-๙.]+ \\d{4} \\d{2}:\\d{2} ICT"),
        ))
        assertFalse(message.contains("💓"))
        assertFalse(message.contains("*"))
    }
}
