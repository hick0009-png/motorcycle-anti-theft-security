package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.network.TlsPinningClient
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

private const val TRANSPORT_TAG = "TelegramTransport"

/**
 * COM-01: TelegramBotClient
 * Connects to Telegram Bot API using TLS 1.3 + Certificate Pinning.
 * Handles long polling and the supported owner command set,
 * and SMS Decode Engine.
 */
class TelegramBotClient(
    private val prefsManager: EncryptedPrefsManager,
    private val commandHandler: TelegramCommandExecutor? = null,
    private val onTelegramContact: (Long) -> Unit = {},
    private val httpClient: okhttp3.OkHttpClient = TlsPinningClient.client,
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
                    } catch (_: Exception) {
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
        val call = httpClient.newCall(request)
        activePollCall = call
        if (!isPolling || epoch != pollingEpoch.get()) {
            call.cancel()
            return
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return
                val bodyString = response.body?.string() ?: return
                val json = org.json.JSONObject(bodyString)
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
                                PairingResult.Accepted -> sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.PAIRING_ACCEPTED).telegramTh!!)
                                PairingResult.Expired -> sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.PAIRING_INVALID_OR_EXPIRED).telegramTh!!)
                                else -> sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.PAIRING_INVALID_OR_EXPIRED).telegramTh!!)
                            }
                        } else {
                            sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.PAIRING_REQUIRED).telegramTh!!)
                        }
                        continue
                    }

                    if (!prefsManager.isChatIdAllowed(chatId)) {
                        sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.UNAUTHORIZED_COMMAND).telegramTh!!)
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
            RemoteCommand.Disarm -> delegate(chatId, commandId, command)

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
            -> sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN).telegramTh!!)

            else -> delegate(chatId, commandId, command)
        }
    }

    private fun delegate(chatId: String, commandId: String, command: RemoteCommand) {
        if (commandHandler == null) {
            sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE).telegramTh!!)
            return
        }
        if (commandQueue.trySend(QueuedCommand(chatId, commandId, command)).isFailure) {
            sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE).telegramTh!!)
        }
    }

    private suspend fun consumeCommands() {
        val dispatcher = PrioritizedCommandDispatcher(
            scope = commandScope,
            isArm = { queued: QueuedCommand -> queued.command == RemoteCommand.Arm },
            isDisarm = { queued: QueuedCommand -> queued.command == RemoteCommand.Disarm },
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
        val sent = sendTelegramMessageToApi(httpClient, botToken, chatId, textMarkdown)
        if (sent) onTelegramContact(System.currentTimeMillis())
        return sent
    }

    internal suspend fun verifyBotTokenResult(
        token: String,
    ): TelegramBotVerificationResult {
        val cleanToken = normalizeTelegramBotToken(token)
        if (cleanToken.isBlank()) {
            return TelegramBotVerificationResult.Rejected
        }
        return botVerifier.verify(cleanToken)
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

internal fun sendTelegramMessageToApi(
    httpClient: okhttp3.OkHttpClient,
    botToken: String,
    chatId: String,
    text: String
): Boolean {
    return try {
        val cleanToken = normalizeTelegramBotToken(botToken)
        val url = "https://api.telegram.org/bot$cleanToken/sendMessage"
        val json = org.json.JSONObject()
        json.put("chat_id", chatId)
        json.put("text", text)

        val body = okhttp3.RequestBody.create(
            "application/json; charset=utf-8".toMediaType(),
            json.toString()
        )
        val request = okhttp3.Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string()
            val ok = response.isSuccessful && bodyStr
                ?.let { org.json.JSONObject(it).optBoolean("ok", false) } == true
            if (!ok) {
                Log.w(TRANSPORT_TAG, "Telegram send failed")
            }
            ok
        }
    } catch (_: Exception) {
        Log.w(TRANSPORT_TAG, "Telegram send failed")
        false
    }
}
