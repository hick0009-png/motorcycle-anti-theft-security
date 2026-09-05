package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.ArmedProfileSnapshot
import com.example.motorcycleantitheftsensor.protection.BreadcrumbEvent
import com.example.motorcycleantitheftsensor.protection.EntryArmedCalibrationSnapshot
import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryModeFacts
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

class HeartbeatPingerTest {

    @Test
    fun recoveryStartsFirstHeartbeatOnlyAfterFullInterval() {
        val scheduler = RecordingHeartbeatScheduler()
        val pinger = HeartbeatPinger(
            context = mock(Context::class.java),
            prefsManager = mock(EncryptedPrefsManager::class.java),
            telegramBotClient = mock(TelegramBotClient::class.java),
            scheduler = scheduler,
        )

        pinger.startHeartbeat()

        assertEquals(15L, scheduler.initialDelayMinutes)
        assertEquals(15L, scheduler.periodMinutes)
        pinger.stopHeartbeat()
    }

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

/**
 * The ceiling warning borrows this tick rather than scheduling an eight-hour delay of its
 * own, which would survive neither doze nor the process being killed — the two things that
 * happen routinely during the overnight session it exists to warn about.
 */
class HeartbeatCeilingWarningTest {

    private val nowMs = 1_700_000_000_000L
    private val config = SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED, 1_000L)

    private fun overCeilingSnapshot(sessionId: String = "session-1") =
        ProtectionSnapshot.offline(nowMs).copy(
            state = ProtectionState.ARMED_HEALTHY,
            protectionActivatedAtMs = nowMs - 9 * 3_600_000L,
            armedProfileSnapshot = ArmedProfileSnapshot(
                armedSessionId = sessionId,
                profile = ProtectionProfile.ENTRY,
                resolvedPresetVersion = 1,
                effectiveConfiguration = config,
                configurationFingerprint = "fp",
                commissionedModelFingerprint = "model-fp",
                armedCalibrationSnapshot = EntryArmedCalibrationSnapshot(1L, "model-fp"),
                entryLevel = EntryWatchLevel.DOOR_ANGLE,
            ),
            modeContext = ProtectionModeContext(
                selectedProfile = ProtectionProfile.ENTRY,
                entryLevel = EntryWatchLevel.DOOR_ANGLE,
                modeFacts = EntryModeFacts(
                    angleThresholdDegrees = 15,
                    openConfirmationMs = 750L,
                    closeThresholdDegrees = 3,
                    closeConfirmationMs = 5_000L,
                    hingeModelCommissioned = true,
                    driftVerdict = EntryDriftVerdict.Limited(hoursToThreshold = 8.0),
                ),
            ),
        )

    @Test
    fun repeatedTicksWarnOnceAndLeaveARecordThatItHappened() {
        val prefs = mock(EncryptedPrefsManager::class.java)
        val telegram = mock(TelegramBotClient::class.java)
        val scheduler = RecordingHeartbeatScheduler()
        `when`(prefs.getAllowedChatIds()).thenReturn(setOf("12345"))
        // The durable memory of what has been warned, as the real preferences behave.
        var warned: String? = null
        `when`(prefs.getEntryCeilingWarnedSessionId()).thenAnswer { warned }
        doAnswer { invocation -> warned = invocation.getArgument(0); Unit }
            .`when`(prefs).setEntryCeilingWarnedSessionId(org.mockito.kotlin.any())

        val crumbs = mutableListOf<BreadcrumbEvent>()
        val pinger = HeartbeatPinger(
            context = mock(Context::class.java),
            prefsManager = prefs,
            telegramBotClient = telegram,
            scheduler = scheduler,
            snapshotSupplier = { overCeilingSnapshot() },
            nowMs = { nowMs },
            breadcrumb = { _, event -> crumbs += event },
        )

        pinger.startHeartbeat()
        repeat(4) { scheduler.tick?.invoke() }

        val messages = argumentCaptor<String>()
        verify(telegram, atLeastOnce()).sendTelegramMessage(eq("12345"), messages.capture())
        val ceilingMessages = messages.allValues.filter { it.contains("เกินเพดานเวลาแล้ว") }
        assertEquals(1, ceilingMessages.size)
        assertTrue(ceilingMessages.single().contains("ยังเฝ้าอยู่ตามปกติระหว่างนี้"))
        assertEquals(listOf(BreadcrumbEvent.CEILING), crumbs)
        pinger.stopHeartbeat()
    }

    @Test
    fun aPingerWithNoSnapshotToReadKeepsItsHeartbeatUnchanged() {
        val prefs = mock(EncryptedPrefsManager::class.java)
        val telegram = mock(TelegramBotClient::class.java)
        val scheduler = RecordingHeartbeatScheduler()
        `when`(prefs.getAllowedChatIds()).thenReturn(setOf("12345"))
        `when`(prefs.isSystemArmed()).thenReturn(true)

        val pinger = HeartbeatPinger(
            context = mock(Context::class.java),
            prefsManager = prefs,
            telegramBotClient = telegram,
            scheduler = scheduler,
        )
        pinger.startHeartbeat()
        scheduler.tick?.invoke()

        val messages = argumentCaptor<String>()
        verify(telegram).sendTelegramMessage(eq("12345"), messages.capture())
        assertTrue(messages.firstValue.contains("รายงานสถานะระบบ"))
        pinger.stopHeartbeat()
    }
}

private class RecordingHeartbeatScheduler : HeartbeatScheduler {
    var initialDelayMinutes: Long? = null
    var periodMinutes: Long? = null

    /** The scheduled body, so a test can drive the tick without waiting fifteen minutes. */
    var tick: (() -> Unit)? = null

    override fun scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: java.util.concurrent.TimeUnit,
        task: () -> Unit,
    ) {
        initialDelayMinutes = unit.toMinutes(initialDelay)
        periodMinutes = unit.toMinutes(period)
        tick = task
    }

    override fun shutdownNow() = Unit

    override val isShutdown: Boolean = false
}
