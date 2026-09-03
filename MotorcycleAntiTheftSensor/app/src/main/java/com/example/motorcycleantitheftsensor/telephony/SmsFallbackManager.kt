package com.example.motorcycleantitheftsensor.telephony

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

fun interface SmsDispatcher {
    fun sendMultipart(
        destinationNumber: String,
        message: String,
        onSent: (Boolean) -> Unit,
    ): () -> Unit
}

/** Encrypts fallback alerts and reports only the platform sent-result callback. */
class SmsFallbackManager internal constructor(
    private val smsKeyProvider: () -> String?,
    private val dispatcher: SmsDispatcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    private val lastSmsSentMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val sendMutex = kotlinx.coroutines.sync.Mutex()

    constructor(
        context: Context,
        prefsManager: EncryptedPrefsManager,
    ) : this(
        smsKeyProvider = prefsManager::getOrCreateSmsAesKey,
        dispatcher = AndroidSmsDispatcher(context.applicationContext),
    )

    /**
     * Seals [message] — the same incident copy Telegram would have carried — and sends it
     * to [destinationNumber]. The message is truncated to [maxPayloadBytes] first, because
     * the ciphertext expands by a third through Base64 and an unbounded Thai alert would
     * turn one event into a dozen SMS parts, each its own chance to arrive alone.
     */
    suspend fun sendEncryptedSmsAlert(
        destinationNumber: String,
        message: String,
    ): Boolean = sendMutex.withLock {
        val deviceKey = smsKeyProvider()?.takeIf { EncryptedSmsCodec.isValidKeyBase64(it) }
            ?: return@withLock false
        if (destinationNumber.isBlank()) return@withLock false
        val now = nowMs()
        val last = lastSmsSentMs.get()
        if (last > 0L && now - last in 0 until minIntervalMs) {
            return@withLock false
        }
        // Body first, timestamp last: if only the leading parts of a multipart SMS arrive,
        // what happened is worth more than exactly when.
        val payload = "${truncateUtf8(message, maxPayloadBytes)}\nTIME:$now"
        val encryptedBody = EncryptedSmsCodec.encryptSmsPayload(payload, deviceKey)
        return try {
            val result = withTimeoutOrNull(sendTimeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val cancel = dispatcher.sendMultipart(destinationNumber, encryptedBody) { sent ->
                        if (continuation.isActive) continuation.resume(sent)
                    }
                    continuation.invokeOnCancellation { cancel() }
                }
            } ?: false
            if (result) {
                lastSmsSentMs.set(nowMs())
            }
            result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    /**
     * Cuts [text] to at most [maxBytes] UTF-8 bytes on a codepoint boundary, so a Thai
     * character is never split into a byte pair that decodes to nothing.
     */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val budget = maxBytes - ELLIPSIS_UTF8_BYTES
        var used = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val charCount = Character.charCount(codePoint)
            val width = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + width > budget) break
            used += width
            end += charCount
        }
        return text.substring(0, end).trimEnd() + "…"
    }

    private companion object {
        const val DEFAULT_SEND_TIMEOUT_MS = 30_000L
        const val DEFAULT_MIN_INTERVAL_MS = 60_000L

        /**
         * 300 plaintext bytes seals into ~440 Base64 characters, which with the scheme
         * prefix stays inside three concatenated GSM-7 parts.
         */
        const val DEFAULT_MAX_PAYLOAD_BYTES = 300
        const val ELLIPSIS_UTF8_BYTES = 3
    }
}

private class AndroidSmsDispatcher(
    private val context: Context,
) : SmsDispatcher {
    override fun sendMultipart(
        destinationNumber: String,
        message: String,
        onSent: (Boolean) -> Unit,
    ): () -> Unit {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (smsManager == null) {
            onSent(false)
            return {}
        }

        val parts = try {
            smsManager.divideMessage(message)
        } catch (_: Exception) {
            onSent(false)
            return {}
        }
        if (parts.isNullOrEmpty()) {
            onSent(false)
            return {}
        }
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val completed = AtomicBoolean(false)
        val remaining = AtomicInteger(parts.size)
        val allSent = AtomicBoolean(true)
        lateinit var receiver: BroadcastReceiver

        fun finish(sent: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { context.unregisterReceiver(receiver) }
            onSent(sent)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode != Activity.RESULT_OK) allSent.set(false)
                if (remaining.decrementAndGet() == 0) finish(allSent.get())
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    index,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            smsManager.sendMultipartTextMessage(
                destinationNumber,
                null,
                ArrayList(parts),
                sentIntents,
                null,
            )
        } catch (error: Exception) {
            finish(false)
        }
        return { finish(false) }
    }
}
