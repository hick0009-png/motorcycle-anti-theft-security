package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.PairingResult
import com.example.motorcycleantitheftsensor.telephony.EncryptedSmsCodec
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * COM-01: TelegramBotClient
 * Connects to Telegram Bot API using TLS 1.3 + Certificate Pinning.
 * Handles Long Polling, remote commands (/status, /arm, /disarm, /location, /decode),
 * TOTP OTP verification for sensitive commands, and SMS Decode Engine.
 */
class TelegramBotClient(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager,
    private val totpAuthenticator: TotpAuthenticator
) {

    private val pairingCodePolicy = PairingCodePolicy()

    private var isPolling = false
    private var lastUpdateId = 0L

    fun startPolling() {
        stopPolling()
        val rawToken = prefsManager.getBotToken() ?: return
        val cleanToken = rawToken.trim().removePrefix("bot").removePrefix("BOT")
        if (cleanToken.isBlank()) return

        isPolling = true
        thread(name = "TelegramBotPollingThread") {
            while (isPolling) {
                try {
                    pollUpdates(cleanToken)
                } catch (e: Exception) {
                    e.printStackTrace()
                    try { Thread.sleep(3000) } catch (ignored: Exception) {}
                }
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private fun pollUpdates(botToken: String) {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
        val request = Request.Builder().url(url).build()

        TlsPinningClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val bodyString = response.body?.string() ?: return
            val json = JSONObject(bodyString)
            if (!json.optBoolean("ok", false)) return

            val resultArray = json.getJSONArray("result")
            for (i in 0 until resultArray.length()) {
                val update = resultArray.getJSONObject(i)
                lastUpdateId = update.getLong("update_id")

                val message = update.optJSONObject("message") ?: continue
                val chatId = message.getJSONObject("chat").getLong("id").toString()
                val text = message.optString("text", "")

                if (prefsManager.getAllowedChatIds().isEmpty()) {
                    val pairingCode = text.trim().split("\\s+".toRegex()).getOrNull(1)
                    if (text.startsWith("/pair") && pairingCode != null) {
                        when (prefsManager.claimPairingCode(chatId, pairingCode, pairingCodePolicy)) {
                            PairingResult.Accepted -> sendTelegramMessage(chatId, "✅ *OWNER PAIRED.* Remote control is now enabled.")
                            PairingResult.Expired -> sendTelegramMessage(chatId, "⚠️ Pairing code expired. Generate a new code on the device.")
                            else -> sendTelegramMessage(chatId, "⛔ Pairing rejected. Generate a pairing code on the device first.")
                        }
                    } else {
                        sendTelegramMessage(chatId, "⛔ This bot is not paired. Use the pairing code displayed on the device.")
                    }
                    continue
                }

                if (!prefsManager.isChatIdAllowed(chatId)) {
                    sendTelegramMessage(chatId, "⚠️ Unauthorized access attempt blocked from Chat ID: $chatId")
                    continue
                }

                handleCommand(chatId, text)
            }
        }
    }

    private fun handleCommand(chatId: String, text: String) {
        val parts = text.trim().split("\\s+".toRegex())
        val command = parts.getOrNull(0)?.lowercase() ?: return
        val arg = parts.getOrNull(1)

        when (command) {
            "/start", "/help" -> {
                sendTelegramMessage(
                    chatId,
                    "🛡️ *Motorcycle Anti-Theft Guard Bot*\n\n" +
                            "Available Commands:\n" +
                            "• `/status` - Check battery, armed state, and sensors\n" +
                            "• `/arm` - Arm anti-theft protection\n" +
                            "• `/disarm <code>` - Disarm protection (Requires TOTP Code)\n" +
                            "• `/sensitivity 1-10` - Set shake sensitivity\n" +
                            "• `/decode <text>` - Decode encrypted SMS fallback message"
                )
            }

            "/status" -> {
                val armed = if (prefsManager.isSystemArmed()) "🟢 ARMED" else "🔴 DISARMED"
                val sens = prefsManager.getSensitivity()
                val uuid = prefsManager.getOrCreateDeviceUuid()
                sendTelegramMessage(
                    chatId,
                    "📊 *System Status Report*\n" +
                            "Status: $armed\n" +
                            "Sensitivity: $sens / 10\n" +
                            "Device UUID: `${uuid.take(8)}...`"
                )
            }

            "/arm" -> {
                prefsManager.setSystemArmed(true)
                context.sendBroadcast(android.content.Intent("com.example.motorcycleantitheftsensor.SYSTEM_ARM"))
                sendTelegramMessage(chatId, "🛡️ *SYSTEM ARMED!* Anti-Theft sensors are active.")
            }

            "/disarm" -> {
                val totpSeed = prefsManager.getTotpSeed()
                if (totpSeed == null) {
                    prefsManager.setSystemArmed(false)
                    context.sendBroadcast(android.content.Intent("com.example.motorcycleantitheftsensor.SYSTEM_DISARM"))
                    sendTelegramMessage(chatId, "🔓 *SYSTEM DISARMED.* Anti-Theft sensors paused.")
                    return
                }

                if (arg == null) {
                    sendTelegramMessage(chatId, "⚠️ Disarm requires 6-digit TOTP code from Google Authenticator.\nUsage: `/disarm 123456`")
                    return
                }

                val result = totpAuthenticator.verifyCode(arg)
                if (result == TotpAuthenticator.VerificationResult.SUCCESS) {
                    prefsManager.setSystemArmed(false)
                    context.sendBroadcast(android.content.Intent("com.example.motorcycleantitheftsensor.SYSTEM_DISARM"))
                    sendTelegramMessage(chatId, "🔓 *SYSTEM DISARMED.* Anti-Theft sensors paused.")
                } else if (result == TotpAuthenticator.VerificationResult.LOCKED_OUT) {
                    sendTelegramMessage(chatId, "⛔ *LOCKOUT TRIGGERED!* Too many invalid TOTP attempts. Try again in 5 minutes.")
                } else {
                    sendTelegramMessage(chatId, "❌ *Invalid TOTP Code.* Check Google Authenticator.")
                }
            }

            "/sensitivity" -> {
                val level = arg?.toIntOrNull()
                if (level != null && level in 1..10) {
                    prefsManager.setSensitivity(level)
                    sendTelegramMessage(chatId, "⚙️ Sensitivity updated to level $level / 10.")
                } else {
                    sendTelegramMessage(chatId, "⚠️ Usage: `/sensitivity 1-10`")
                }
            }

            "/decode" -> {
                val secretPass = prefsManager.getSmsAesKey()
                if (secretPass == null) {
                    sendTelegramMessage(chatId, "⚠️ SMS AES key not configured. Set it first in Security Settings.")
                    return
                }
                val decrypted = EncryptedSmsCodec.decryptSmsPayload(text.removePrefix("/decode").trim(), secretPass)
                if (decrypted != null) {
                    sendTelegramMessage(chatId, "🔓 *Decrypted SMS Alarm Payload:*\n`$decrypted`")
                } else {
                    sendTelegramMessage(chatId, "❌ Failed to decrypt SMS. Ensure message starts with `[ENC_ALARM]` and key matches.")
                }
            }
        }
    }

    fun sendTelegramMessage(chatId: String, textMarkdown: String) {
        val botToken = prefsManager.getBotToken() ?: return
        thread {
            try {
                val url = "https://api.telegram.org/bot$botToken/sendMessage"
                val body = FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", textMarkdown)
                    .add("parse_mode", "Markdown")
                    .build()

                val request = Request.Builder().url(url).post(body).build()
                TlsPinningClient.client.newCall(request).execute().close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Verifies Bot Token with Telegram API (getMe) and fetches Bot Username & ID.
     * Guaranteed to return callback results on Main UI Thread.
     */
    fun verifyBotToken(token: String, onResult: (isValid: Boolean, botUsername: String?, botId: String?) -> Unit) {
        val cleanToken = token.trim().removePrefix("bot").removePrefix("BOT")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (cleanToken.isBlank()) {
            mainHandler.post { onResult(false, null, null) }
            return
        }
        thread {
            try {
                val url = "https://api.telegram.org/bot$cleanToken/getMe"
                val request = Request.Builder().url(url).build()
                TlsPinningClient.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: return@use
                        val json = JSONObject(bodyString)
                        if (json.optBoolean("ok", false)) {
                            val res = json.getJSONObject("result")
                            val username = res.optString("username", "UnknownBot")
                            val botId = res.optLong("id", 0L).toString()
                            mainHandler.post {
                                onResult(true, username, botId)
                            }
                            return@thread
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post {
                onResult(false, null, null)
            }
        }
    }

    /**
     * Sends a test alert message to all whitelisted Owner Chat IDs.
     */
    fun sendTestAlertToOwners(onComplete: (Boolean) -> Unit) {
        val allowedChatIds = prefsManager.getAllowedChatIds()
        if (allowedChatIds.isEmpty()) {
            onComplete(false)
            return
        }
        thread {
            var count = 0
            allowedChatIds.forEach { chatId ->
                sendTelegramMessage(
                    chatId,
                    "🧪 *TEST SECURITY ALERT*\nYour Motorcycle Guard anti-theft notification system is active & connected!"
                )
                count++
            }
            onComplete(count > 0)
        }
    }
}
