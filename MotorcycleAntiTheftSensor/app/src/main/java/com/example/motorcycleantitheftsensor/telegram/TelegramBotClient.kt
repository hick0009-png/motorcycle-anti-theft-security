package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.PairingResult
import com.example.motorcycleantitheftsensor.telephony.EncryptedSmsCodec
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
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
import kotlinx.coroutines.withContext

/**
 * COM-01: TelegramBotClient
 * Connects to Telegram Bot API using TLS 1.3 + Certificate Pinning.
 * Handles long polling and the supported owner command set,
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
    private val botVerifier = TelegramBotVerifier()
    private var commandJob: Job = SupervisorJob()
    private var commandScope = CoroutineScope(commandJob + Dispatchers.IO)
    private var commandQueue = Channel<QueuedCommand>(Channel.UNLIMITED)

    @Volatile
    private var isPolling = false
    @Volatile
    private var activePollCall: Call? = null
    @Volatile
    private var pollingThread: Thread? = null
    private val pollingEpoch = AtomicLong(0L)
    private var lastUpdateId = 0L

    @Synchronized
    fun startPolling(): Boolean {
        stopPolling()
        val rawToken = prefsManager.getBotToken() ?: return false
        val cleanToken = normalizeTelegramBotToken(rawToken)
        if (cleanToken.isBlank()) return false

        lastUpdateId = prefsManager.getLastTelegramUpdateId()
        commandJob = SupervisorJob()
        commandScope = CoroutineScope(commandJob + Dispatchers.IO)
        commandQueue = Channel(Channel.UNLIMITED)
        commandScope.launch { consumeCommands() }
        val epoch = pollingEpoch.incrementAndGet()
        isPolling = true
        val worker = thread(start = false, name = "TelegramBotPollingThread") {
            try {
                while (isPolling && epoch == pollingEpoch.get()) {
                    try {
                        pollUpdates(cleanToken, epoch)
                    } catch (e: Exception) {
                        if (!isPolling || epoch != pollingEpoch.get()) return@thread
                        Log.w(TRANSPORT_TAG, "Telegram polling failed")
                        try { Thread.sleep(3000) } catch (ignored: Exception) {}
                    }
                }
            } finally {
                synchronized(this@TelegramBotClient) {
                    if (pollingThread === Thread.currentThread()) pollingThread = null
                }
            }
        }
        pollingThread = worker
        worker.start()
        return true
    }

    @Synchronized
    fun stopPolling() {
        stopPollingLocked()
    }

    suspend fun stopPollingAndAwait() {
        val (worker, commands) = synchronized(this) {
            (pollingThread to commandJob).also { stopPollingLocked() }
        }
        awaitTelegramPollingSessionShutdown(worker, commands)
        synchronized(this) {
            if (pollingThread === worker) pollingThread = null
        }
    }

    private fun stopPollingLocked() {
        isPolling = false
        pollingEpoch.incrementAndGet()
        activePollCall?.cancel()
        commandQueue.close()
        commandJob.cancel()
        pollingThread?.interrupt()
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
            android.util.Log.i(TRANSPORT_TAG, "Fetching getUpdates offset ${lastUpdateId + 1}")
            call.execute().use { response ->
                android.util.Log.i(TRANSPORT_TAG, "getUpdates response code: ${response.code}")
                if (!response.isSuccessful) return
                val bodyString = response.body?.string() ?: return
                android.util.Log.i(TRANSPORT_TAG, "getUpdates response body: $bodyString")
                val json = org.json.JSONObject(bodyString)
                if (!json.optBoolean("ok", false)) return
                if (!isPolling || epoch != pollingEpoch.get()) return
                onTelegramContact(System.currentTimeMillis())

                val resultArray = json.getJSONArray("result")
                android.util.Log.i(TRANSPORT_TAG, "getUpdates result array length: ${resultArray.length()}")
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
        val dispatcher = PrioritizedCommandDispatcher(
            scope = commandScope,
            isArm = { queued: QueuedCommand -> queued.command == RemoteCommand.Arm },
            isDisarm = { queued: QueuedCommand -> queued.command is RemoteCommand.Disarm },
            execute = ::execute,
        )
        for (queued in commandQueue) {
            dispatcher.submit(queued)
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
        android.util.Log.i(TRANSPORT_TAG, "sendTelegramMessage called for $chatId")
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
        android.util.Log.i(TRANSPORT_TAG, "sendTelegramMessageSync CALLED for $chatId")
        val botToken = prefsManager.getBotToken() ?: return false
        android.util.Log.i(TRANSPORT_TAG, "botToken read: ${botToken.take(5)}...")
        return try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val json = org.json.JSONObject()
            json.put("chat_id", chatId)
            json.put("text", textMarkdown)
            json.put("parse_mode", "Markdown")

            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(),
                json.toString()
            )
            val request = okhttp3.Request.Builder().url(url).post(body).build()
            TlsPinningClient.client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                android.util.Log.i(TRANSPORT_TAG, "sendMessage response: ${response.code} $bodyStr")
                val sent = response.isSuccessful && bodyStr
                    ?.let { body -> org.json.JSONObject(body).optBoolean("ok", false) } == true
                sent.also {
                    if (sent) onTelegramContact(System.currentTimeMillis())
                }
            }
        } catch (_: Exception) {
            Log.w(TRANSPORT_TAG, "Telegram send failed")
            false
        }
    }

    internal fun verifyBotTokenResult(
        token: String,
        onResult: (TelegramBotVerificationResult) -> Unit,
    ) {
        val cleanToken = normalizeTelegramBotToken(token)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (cleanToken.isBlank()) {
            mainHandler.post { onResult(TelegramBotVerificationResult.Rejected) }
            return
        }
        thread {
            val result = botVerifier.verify(cleanToken)
            mainHandler.post { onResult(result) }
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

    private companion object {
        const val TRANSPORT_TAG = "TelegramTransport"
    }
}

internal suspend fun awaitTelegramPollingSessionShutdown(
    worker: Thread?,
    commandJob: Job,
) {
    commandJob.cancelAndJoin()
    if (worker != null && worker !== Thread.currentThread()) {
        withContext(Dispatchers.IO) { worker.join() }
    }
}

internal fun normalizeTelegramBotToken(token: String): String {
    val trimmed = token.trim()
    return if (trimmed.startsWith("bot", ignoreCase = true)) trimmed.drop(3) else trimmed
}
