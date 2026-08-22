package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * COM-02: HeartbeatPinger
 * Sends a silent heartbeat "alive" status ping every 15 minutes to Telegram Bot.
 * Note: Fixes VULN-08 by omitting cleartext GPS coordinates from heartbeat messages.
 */
class HeartbeatPinger(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager,
    private val telegramBotClient: TelegramBotClient
) {

    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun startHeartbeat() {
        if (executor != null && !executor!!.isShutdown) return

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleAtFixedRate({
            try {
                sendHeartbeatPing()
            } catch (ignored: Exception) {}
        }, 0, 15, TimeUnit.MINUTES)
    }

    @Synchronized
    fun stopHeartbeat() {
        executor?.shutdownNow()
        executor = null
    }

    private fun sendHeartbeatPing() {
        val allowedChatIds = prefsManager.getAllowedChatIds()
        if (allowedChatIds.isEmpty()) return

        val protectionStatus = if (prefsManager.isSystemArmed()) {
            "เปิดใช้งาน | Protection: Armed"
        } else {
            "ปิดใช้งาน | Protection: Disarmed"
        }
        val timestamp = SimpleDateFormat(
            "d MMM yyyy HH:mm 'ICT'",
            Locale.forLanguageTag("th-TH"),
        ).apply {
            timeZone = TimeZone.getTimeZone("Asia/Bangkok")
        }.format(Date())

        for (chatId in allowedChatIds) {
            telegramBotClient.sendTelegramMessage(
                chatId,
                "รายงานสถานะระบบ / System Status\n" +
                    "บริการ: ออนไลน์ | Service: Online\n" +
                    "การป้องกัน: $protectionStatus\n" +
                    "รายงานเมื่อ: $timestamp",
            )
        }
    }
}
