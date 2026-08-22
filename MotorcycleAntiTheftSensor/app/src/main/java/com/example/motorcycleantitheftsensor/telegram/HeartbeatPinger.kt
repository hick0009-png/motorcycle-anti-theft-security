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

interface HeartbeatScheduler {
    val isShutdown: Boolean

    fun scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: () -> Unit,
    )

    fun shutdownNow()
}

private class ExecutorHeartbeatScheduler(
    private val executor: ScheduledExecutorService,
) : HeartbeatScheduler {
    override val isShutdown: Boolean get() = executor.isShutdown

    override fun scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: () -> Unit,
    ) {
        executor.scheduleAtFixedRate(task, initialDelay, period, unit)
    }

    override fun shutdownNow() {
        executor.shutdownNow()
    }
}

/**
 * COM-02: HeartbeatPinger
 * Sends a silent heartbeat "alive" status ping every 15 minutes to Telegram Bot.
 * Note: Fixes VULN-08 by omitting cleartext GPS coordinates from heartbeat messages.
 */
class HeartbeatPinger(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager,
    private val telegramBotClient: TelegramBotClient,
    private var scheduler: HeartbeatScheduler? = null,
) {
    private var started = false

    @Synchronized
    fun startHeartbeat() {
        if (started && scheduler != null && !scheduler!!.isShutdown) return

        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = ExecutorHeartbeatScheduler(Executors.newSingleThreadScheduledExecutor())
        }
        scheduler?.scheduleAtFixedRate(
            initialDelay = HEARTBEAT_INTERVAL_MINUTES,
            period = HEARTBEAT_INTERVAL_MINUTES,
            unit = TimeUnit.MINUTES,
        ) {
            try {
                sendHeartbeatPing()
            } catch (ignored: Exception) {}
        }
        started = true
    }

    @Synchronized
    fun stopHeartbeat() {
        scheduler?.shutdownNow()
        scheduler = null
        started = false
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

    private companion object {
        const val HEARTBEAT_INTERVAL_MINUTES = 15L
    }
}
