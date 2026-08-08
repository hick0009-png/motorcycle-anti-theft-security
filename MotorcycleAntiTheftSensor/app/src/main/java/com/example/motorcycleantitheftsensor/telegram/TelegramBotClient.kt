package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.PairingResult
import com.example.motorcycleantitheftsensor.telephony.EncryptedSmsCodec
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * COM-01: TelegramBotClient
 * Connects to Telegram Bot API using TLS 1.3 + Certificate Pinning.
 * Handles Long Polling, remote commands (/status, /arm, /disarm, /location, /decode),
 * TOTP OTP verification for sensitive commands, and SMS Decode Engine.
 */
class TelegramBotClient(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager,
    private val totpAuthenticator: TotpAuthenticator,
    private val commandHandler: TelegramCommandHandler? = null,
    private val onTelegramContact: (Long) -> Unit = {},
) {

    private val pairingCodePolicy = PairingCodePolicy()
    private var commandJob: Job = SupervisorJob()
    private var commandScope = CoroutineScope(commandJob + Dispatchers.IO)
    private var commandQueue = Channel<QueuedCommand>(Channel.UNLIMITED)

    @Volatile
    private var isPolling = false
    @Volatile
    private var activePollCall: Call? = null
    private val pollingEpoch = AtomicLong(0L)
    private var lastUpdateId = 0L

    @Synchronized
    fun startPolling(): Boolean {
        stopPolling()
        val rawToken = prefsManager.getBotToken() ?: return false
        val cleanToken = rawToken.trim().removePrefix("bot").removePrefix("BOT")
        if (cleanToken.isBlank()) return false

        lastUpdateId = prefsManager.getLastTelegramUpdateId()
        commandJob = SupervisorJob()
        commandScope = CoroutineScope(commandJob + Dispatchers.IO)
        commandQueue = Channel(Channel.UNLIMITED)
        commandScope.launch { consumeCommands() }
        val epoch = pollingEpoch.incrementAndGet()
        isPolling = true
        thread(name = "TelegramBotPollingThread") {
            while (isPolling && epoch == pollingEpoch.get()) {
                try {
                    pollUpdates(cleanToken, epoch)
                } catch (e: Exception) {
                    if (!isPolling || epoch != pollingEpoch.get()) return@thread
                    e.printStackTrace()
                    try { Thread.sleep(3000) } catch (ignored: Exception) {}
                }
            }
        }
        return true
    }

    @Synchronized
    fun stopPolling() {
        isPolling = false
        pollingEpoch.incrementAndGet()
        activePollCall?.cancel()
        commandQueue.close()
        commandJob.cancel()
    }

    private fun pollUpdates(botToken: String, epoch: Long) {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
        val request = Request.Builder().url(url).build()
        val call = TlsPinningClient.client.newCall(request)
        activePollCall = call
        if (!isPolling || epoch != pollingEpoch.get()) {
            call.cancel()
            return
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return
                val bodyString = response.body?.string() ?: return
                val json = JSONObject(bodyString)
                if (!json.optBoolean("ok", false)) return
                if (!isPolling || epoch != pollingEpoch.get()) return
                onTelegramContact(System.currentTimeMillis())

                val resultArray = json.getJSONArray("result")
                for (i in 0 until resultArray.length()) {
                    if (!isPolling || epoch != pollingEpoch.get()) return
                    val update = resultArray.getJSONObject(i)
                    val updateId = update.getLong("update_id")
                    if (updateId <= lastUpdateId) continue
                    if (!prefsManager.commitLastTelegramUpdateId(updateId)) return
                    lastUpdateId = updateId

                    val message = update.optJSONObject("message") ?: continue
                    val chatId = message.getJSONObject("chat").getLong("id").toString()
                    val text = message.optString("text", "")
                    val command = RemoteCommand.parse(text)
                    val commandId = "telegram-update-$updateId"

                    if (prefsManager.getAllowedChatIds().isEmpty()) {
                        if (command is RemoteCommand.Pair && command.code != null) {
                            when (prefsManager.claimPairingCode(chatId, command.code, pairingCodePolicy)) {
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
                        sendTelegramMessage(chatId, "Unauthorized command.")
                        continue
                    }

                    handleAuthorizedCommand(chatId, commandId, command)
                }
            }
        } finally {
            if (activePollCall === call) activePollCall = null
        }
    }

    private fun handleAuthorizedCommand(chatId: String, commandId: String, command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Disarm -> {
                val totpSeed = prefsManager.getTotpSeed()
                if (totpSeed == null) {
                    sendTelegramMessage(chatId, "TOTP must be configured on the device before remote disarm is allowed.")
                    return
                }

                if (command.code == null) {
                    sendTelegramMessage(chatId, "⚠️ Disarm requires 6-digit TOTP code from Google Authenticator.\nUsage: `/disarm 123456`")
                    return
                }

                when (totpAuthenticator.verifyCode(command.code)) {
                    TotpAuthenticator.VerificationResult.SUCCESS -> delegate(chatId, commandId, command)
                    TotpAuthenticator.VerificationResult.LOCKED_OUT ->
                        sendTelegramMessage(chatId, "TOTP verification locked. Try again later.")

                    else -> sendTelegramMessage(chatId, "Invalid TOTP code.")
                }
            }

            is RemoteCommand.Decode -> {
                val secretPass = prefsManager.getSmsAesKey()
                if (secretPass == null) {
                    sendTelegramMessage(chatId, "⚠️ SMS AES key not configured. Set it first in Security Settings.")
                    return
                }
                val decrypted = EncryptedSmsCodec.decryptSmsPayload(command.payload, secretPass)
                if (decrypted != null) {
                    sendTelegramMessage(chatId, "🔓 *Decrypted SMS Alarm Payload:*\n`$decrypted`")
                } else {
                    sendTelegramMessage(chatId, "❌ Failed to decrypt SMS. Ensure message starts with `[ENC_ALARM]` and key matches.")
                }
            }

            RemoteCommand.Unknown,
            is RemoteCommand.Pair,
            -> sendTelegramMessage(chatId, "Unknown command. Use /help.")

            else -> delegate(chatId, commandId, command)
        }
    }

    private fun delegate(chatId: String, commandId: String, command: RemoteCommand) {
        if (commandHandler == null) {
            sendTelegramMessage(chatId, "Command service unavailable.")
            return
        }
        if (commandQueue.trySend(QueuedCommand(chatId, commandId, command)).isFailure) {
            sendTelegramMessage(chatId, "Command queue unavailable; request /status.")
        }
    }

    private suspend fun consumeCommands() {
        var activeArm: Job? = null
        for (queued in commandQueue) {
            when (queued.command) {
                RemoteCommand.Arm -> {
                    activeArm?.cancelAndJoin()
                    activeArm = commandScope.launch { execute(queued) }
                }

                is RemoteCommand.Disarm -> {
                    activeArm?.cancelAndJoin()
                    activeArm = null
                    execute(queued)
                }

                else -> execute(queued)
            }
        }
    }

    private suspend fun execute(queued: QueuedCommand) {
        commandHandler?.handle(
            commandId = queued.commandId,
            command = queued.command,
            reply = { message -> sendTelegramMessageSync(queued.chatId, message) },
        )
        val sensitivity = (queued.command as? RemoteCommand.Sensitivity)?.level
        if (sensitivity?.let { it in 1..10 } == true) prefsManager.setSensitivity(sensitivity)
    }

    fun sendTelegramMessage(chatId: String, textMarkdown: String) {
        thread {
            sendTelegramMessageSync(chatId, textMarkdown)
        }
    }

    fun sendTelegramAlert(textMarkdown: String): Boolean {
        val ownerIds = prefsManager.getAllowedChatIds()
        if (ownerIds.isEmpty()) return false
        return ownerIds.map { chatId -> sendTelegramMessageSync(chatId, textMarkdown) }.all { it }
    }

    private fun sendTelegramMessageSync(chatId: String, textMarkdown: String): Boolean {
        val botToken = prefsManager.getBotToken() ?: return false
        return try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val body = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", textMarkdown)
                .build()
            val request = Request.Builder().url(url).post(body).build()
            TlsPinningClient.client.newCall(request).execute().use { response ->
                val sent = response.isSuccessful && response.body
                    ?.string()
                    ?.let { body -> JSONObject(body).optBoolean("ok", false) } == true
                sent.also {
                    if (sent) onTelegramContact(System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
            val sent = allowedChatIds.map { chatId ->
                sendTelegramMessageSync(
                    chatId,
                    "🧪 *TEST SECURITY ALERT*\nYour Motorcycle Guard anti-theft notification system is active & connected!"
                )
            }.all { it }
            onComplete(sent)
        }
    }

    private data class QueuedCommand(
        val chatId: String,
        val commandId: String,
        val command: RemoteCommand,
    )
}
