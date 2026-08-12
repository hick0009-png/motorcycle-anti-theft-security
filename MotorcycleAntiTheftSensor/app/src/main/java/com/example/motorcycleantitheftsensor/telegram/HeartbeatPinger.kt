package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
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

    fun startHeartbeat() {
        if (executor != null && !executor!!.isShutdown) return

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleAtFixedRate({
            sendHeartbeatPing()
        }, 0, 15, TimeUnit.MINUTES)
    }

    fun stopHeartbeat() {
        executor?.shutdownNow()
        executor = null
    }

    private fun sendHeartbeatPing() {
        if (!prefsManager.isSystemArmed()) return

        val allowedChatIds = prefsManager.getAllowedChatIds()
        val armedState = if (prefsManager.isSystemArmed()) "Armed 🟢" else "Disarmed 🔴"
        val timestamp = System.currentTimeMillis() / 1000L

        for (chatId in allowedChatIds) {
            telegramBotClient.sendTelegramMessage(
                chatId,
                "💓 *Heartbeat Ping* — Vehicle Security Online\nState: $armedState\nTimestamp: $timestamp"
            )
        }
    }
}
