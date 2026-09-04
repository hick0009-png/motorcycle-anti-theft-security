package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import android.util.Log
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.BreadcrumbDetail
import com.example.motorcycleantitheftsensor.protection.BreadcrumbEvent
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
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Leaves a mark in the black box for what this client did.
     *
     * The domain is fixed rather than passed, so a caller here cannot file a crumb under
     * somebody else's allowance. Nothing identifying goes through it: the parameter types are
     * closed enums, and a chat id could not be expressed even deliberately.
     */
    private val breadcrumb: (BreadcrumbEvent, List<BreadcrumbDetail>) -> Unit = { _, _ -> },
) {

    private val pairingCodePolicy = PairingCodePolicy()
    private val botVerifier = TelegramBotVerifier()
    @Volatile
    private var sendExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var commandJob: Job = SupervisorJob()
    private var commandScope = CoroutineScope(commandJob + Dispatchers.IO)
    private var commandQueue = Channel<QueuedCommand>(Channel.UNLIMITED)

    private val recentSentMessages = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val DEDUPLICATION_WINDOW_MS = 5_000L

    internal fun isDuplicateMessage(chatId: String, textMarkdown: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = "$chatId:$textMarkdown"
        val lastSent = recentSentMessages[key] ?: return false
        return (nowMs - lastSent) < DEDUPLICATION_WINDOW_MS
    }

    internal fun recordSentMessage(chatId: String, textMarkdown: String, nowMs: Long = System.currentTimeMillis()) {
        val key = "$chatId:$textMarkdown"
        recentSentMessages[key] = nowMs
        if (recentSentMessages.size > 100) {
            val cutoff = nowMs - DEDUPLICATION_WINDOW_MS
            recentSentMessages.entries.removeIf { it.value < cutoff }
        }
    }

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

        if (sendExecutor.isShutdown) {
            sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        }
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
        sendExecutor.shutdown()
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
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code == 401 || code == 404) {
                        Log.e(TRANSPORT_TAG, "Telegram bot token is invalid (HTTP $code). Stopping polling.")
                        isPolling = false
                        return
                    }
                    try { Thread.sleep(3000) } catch (ignored: Exception) {}
                    return
                }
                val bodyString = response.body?.string() ?: run {
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                    return
                }
                val json = org.json.JSONObject(bodyString)
                if (!json.optBoolean("ok", false)) {
                    try { Thread.sleep(3000) } catch (ignored: Exception) {}
                    return
                }
                if (!isPolling || epoch != pollingEpoch.get()) return
                onTelegramContact(System.currentTimeMillis())

                val resultArray = json.getJSONArray("result")
                for (i in 0 until resultArray.length()) {
                    if (!isPolling || epoch != pollingEpoch.get()) return
                    val update = resultArray.getJSONObject(i)
                    val updateId = update.getLong("update_id")
                    if (updateId <= lastUpdateId) continue

                    val message = update.optJSONObject("message")
                    if (message == null) {
                        commitUpdateId(updateId)
                        continue
                    }
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
                        commitUpdateId(updateId)
                        continue
                    }

                    if (!prefsManager.isChatIdAllowed(chatId)) {
                        // Somebody who is not the owner found the bot and is talking to it.
                        // Nothing recorded this before, so an owner had no way of learning
                        // that their bot was being probed at all — and the refusal below
                        // confirms to the prober that it is live.
                        breadcrumb(BreadcrumbEvent.DENIED, emptyList())
                        sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.UNAUTHORIZED_COMMAND).telegramTh!!)
                        commitUpdateId(updateId)
                        continue
                    }

                    handleAuthorizedCommand(chatId, commandId, command, updateId)
                }
            }
        } finally {
            if (activePollCall === call) activePollCall = null
        }
    }

    private fun commitUpdateId(updateId: Long) {
        if (updateId > lastUpdateId) {
            if (prefsManager.commitLastTelegramUpdateId(updateId)) {
                lastUpdateId = updateId
            }
        }
    }

    private val commandDedupStates = LinkedHashMap<String, CommandDedupState>()

    private fun claimCommand(commandId: String): Boolean = synchronized(commandDedupStates) {
        if (commandDedupStates.containsKey(commandId)) return@synchronized false
        evictCompletedCommandsLocked()
        commandDedupStates[commandId] = CommandDedupState.IN_FLIGHT
        true
    }

    private fun completeCommand(commandId: String) {
        synchronized(commandDedupStates) {
            if (commandDedupStates[commandId] == CommandDedupState.IN_FLIGHT) {
                commandDedupStates[commandId] = CommandDedupState.COMPLETED
            }
            evictCompletedCommandsLocked()
        }
    }

    private fun releaseCommand(commandId: String) {
        synchronized(commandDedupStates) {
            if (commandDedupStates[commandId] == CommandDedupState.IN_FLIGHT) {
                commandDedupStates.remove(commandId)
            }
        }
    }

    private fun evictCompletedCommandsLocked() {
        val iterator = commandDedupStates.entries.iterator()
        while (commandDedupStates.size >= 100 && iterator.hasNext()) {
            if (iterator.next().value == CommandDedupState.COMPLETED) {
                iterator.remove()
            }
        }
    }

    private fun handleAuthorizedCommand(chatId: String, commandId: String, command: RemoteCommand, updateId: Long) {
        when (command) {
            RemoteCommand.Disarm -> delegate(chatId, commandId, command, updateId)

            is RemoteCommand.Decode -> {
                // Current alerts open under the device key; anything still in the owner's
                // inbox from before the key was randomised opens under the old passphrase.
                val decrypted =
                    EncryptedSmsCodec.decryptSmsPayload(command.payload, prefsManager.getOrCreateSmsAesKey())
                        ?: prefsManager.getLegacySmsAesKey()?.let { legacyKey ->
                            EncryptedSmsCodec.decryptSmsPayload(command.payload, legacyKey)
                        }
                if (decrypted != null) {
                    sendTelegramMessage(chatId, "🔓 *Decrypted SMS Alarm Payload:*\n`$decrypted`")
                } else {
                    sendTelegramMessage(chatId, "❌ Failed to decrypt SMS. Forward the whole message, starting at `[ENC_ALARM_`.")
                }
                commitUpdateId(updateId)
            }

            RemoteCommand.Unknown,
            is RemoteCommand.Pair,
            -> {
                sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN).telegramTh!!)
                commitUpdateId(updateId)
            }

            else -> delegate(chatId, commandId, command, updateId)
        }
    }

    private fun delegate(chatId: String, commandId: String, command: RemoteCommand, updateId: Long) {
        if (commandHandler == null) {
            sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE).telegramTh!!)
            commitUpdateId(updateId)
            return
        }
        if (!claimCommand(commandId)) return
        // Here rather than where the update arrives, because the update id is committed only
        // after the command has finished: while a location fix is being taken, Telegram keeps
        // redelivering the same message, and one `/where` recorded four times is not a record
        // of anything. `claimCommand` is the point at which the app decides to act on a
        // command exactly once, so it is the point worth remembering.
        //
        // Still before execution and still regardless of outcome: the owner is the only
        // person who should ever ask where the vehicle is, and a request they did not make is
        // the signal whether or not it succeeded.
        if (command is RemoteCommand.Where) {
            breadcrumb(BreadcrumbEvent.WHERE, emptyList())
        }
        if (commandQueue.trySend(QueuedCommand(chatId, commandId, command, updateId)).isFailure) {
            releaseCommand(commandId)
            sendTelegramMessage(chatId, com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE).telegramTh!!)
            commitUpdateId(updateId)
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
        try {
            commandHandler?.handle(
                commandId = queued.commandId,
                command = queued.command,
                reply = { message -> sendTelegramMessageSync(queued.chatId, message) },
            )
            val sensitivity = (queued.command as? RemoteCommand.Sensitivity)?.level
            if (sensitivity?.let { it in 1..10 } == true) prefsManager.setSensitivity(sensitivity)
            completeCommand(queued.commandId)
            commitUpdateId(queued.updateId)
        } catch (error: Exception) {
            releaseCommand(queued.commandId)
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(TRANSPORT_TAG, "Telegram command execution failed")
        }
    }

    fun sendTelegramMessage(chatId: String, textMarkdown: String) {
        val executor = synchronized(this) {
            if (sendExecutor.isShutdown) {
                sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            }
            sendExecutor
        }
        try {
            executor.execute {
                sendTelegramMessageSync(chatId, textMarkdown)
            }
        } catch (_: Exception) {}
    }

    suspend fun sendTelegramAlert(textMarkdown: String): Boolean = withContext(Dispatchers.IO) {
        val ownerIds = prefsManager.getAllowedChatIds()
        if (ownerIds.isEmpty()) return@withContext false
        withTimeoutOrNull(10_000L) {
            ownerIds.map { chatId -> sendTelegramMessageSync(chatId, textMarkdown) }.all { it }
        } ?: false
    }

    private fun sendTelegramMessageSync(chatId: String, textMarkdown: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (isDuplicateMessage(chatId, textMarkdown, nowMs)) {
            return true
        }
        // A missing token is a send that never left the phone, and it is the failure most
        // likely to be silent: nothing is broken, nothing times out, the owner is simply
        // never told anything again.
        val botToken = prefsManager.getBotToken() ?: run {
            breadcrumb(BreadcrumbEvent.FAILED, listOf(BreadcrumbDetail.UNKNOWN))
            return false
        }
        var status: Int? = null
        val sent = sendTelegramMessageToApi(httpClient, botToken, chatId, textMarkdown) { code ->
            // The worst status of the parts wins: a message split into chunks has not been
            // delivered if any chunk was refused.
            if (status == null) status = code
        }
        val tookMs = System.currentTimeMillis() - nowMs
        if (sent) {
            recordSentMessage(chatId, textMarkdown, nowMs)
            onTelegramContact(nowMs)
            breadcrumb(BreadcrumbEvent.OK, listOf(BreadcrumbDetail.latency(tookMs)))
        } else {
            breadcrumb(BreadcrumbEvent.FAILED, listOf(BreadcrumbDetail.httpClass(status)))
        }
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
        commandScope.launch {
            val sent = withContext(Dispatchers.IO) {
                allowedChatIds.map { chatId ->
                    sendTelegramMessageSync(
                        chatId,
                        "🧪 *TEST SECURITY ALERT*\nYour Motorcycle Guard anti-theft notification system is active & connected!"
                    )
                }.all { it }
            }
            onComplete(sent)
        }
    }

    private data class QueuedCommand(
        val chatId: String,
        val commandId: String,
        val command: RemoteCommand,
        val updateId: Long,
    )

    private enum class CommandDedupState {
        IN_FLIGHT,
        COMPLETED,
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

internal fun splitTelegramMessage(text: String, maxLength: Int = 4096): List<String> {
    if (text.length <= maxLength) return listOf(text)
    val chunks = mutableListOf<String>()
    val currentChunk = StringBuilder()

    val lines = text.split("\n")
    for (i in lines.indices) {
        val line = lines[i]
        if (line.length > maxLength) {
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString())
                currentChunk.clear()
            }
            val subChunks = line.chunked(maxLength)
            for (j in 0 until subChunks.size - 1) {
                chunks.add(subChunks[j])
            }
            currentChunk.append(subChunks.last())
        } else {
            val neededLength = if (currentChunk.isEmpty()) line.length else currentChunk.length + 1 + line.length
            if (neededLength <= maxLength) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n")
                }
                currentChunk.append(line)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk.clear()
                }
                currentChunk.append(line)
            }
        }
    }
    if (currentChunk.isNotEmpty()) {
        chunks.add(currentChunk.toString())
    }
    return if (chunks.isEmpty()) listOf(text) else chunks
}

internal fun sendTelegramMessageToApi(
    httpClient: okhttp3.OkHttpClient,
    botToken: String,
    chatId: String,
    text: String,
    /**
     * Reports the HTTP status of a part that failed, or null when it never got one.
     *
     * Optional and ignored by default so that every existing caller is unchanged. It carries a
     * status code and nothing else on purpose — the response body of a Telegram error names
     * the chat it was about, and that must not be within reach of the file.
     */
    onFailure: (Int?) -> Unit = {},
): Boolean {
    val messages = splitTelegramMessage(text)
    var allSuccess = true
    for (msg in messages) {
        val sent = sendSingleTelegramMessageToApi(httpClient, botToken, chatId, msg, onFailure)
        if (!sent) {
            allSuccess = false
        }
    }
    return allSuccess
}

private fun sendSingleTelegramMessageToApi(
    httpClient: okhttp3.OkHttpClient,
    botToken: String,
    chatId: String,
    text: String,
    onFailure: (Int?) -> Unit = {},
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
                onFailure(response.code)
            }
            ok
        }
    } catch (_: Exception) {
        Log.w(TRANSPORT_TAG, "Telegram send failed")
        // No status at all: a timeout, a refused socket, no route out of the phone.
        onFailure(null)
        false
    }
}
