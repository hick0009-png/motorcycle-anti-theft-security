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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * What the radio said about one multipart send.
 *
 * The platform answers a send with a result code naming the cause, and this layer used to
 * compare it against "OK" and throw the rest away. Fifteen failed fallback attempts on the
 * test device were therefore recorded as fifteen shrugs: something went wrong, no idea what,
 * and no way to tell a phone with no coverage from a phone with its radio off from a carrier
 * refusing the message.
 */
enum class SmsRadioResult {
    SENT,

    /** In coverage terms: the radio is on and there is nothing to send through. */
    NO_SERVICE,

    /** Airplane mode, or the radio otherwise down. */
    RADIO_OFF,

    /** The carrier refused it for volume, which is a bill and a rate, not a fault. */
    CARRIER_LIMIT,

    /** Refused for a reason the platform did not narrow down. */
    FAILED,
}

fun interface SmsDispatcher {
    fun sendMultipart(
        destinationNumber: String,
        message: String,
        onResult: (SmsRadioResult) -> Unit,
    ): () -> Unit
}

/** Encrypts fallback alerts and reports only the platform sent-result callback. */
/**
 * What became of one SMS fallback attempt. Every value except [SENT] is a message the owner
 * did not get, and they are separated because the thing to do about each one differs.
 */
enum class SmsSendOutcome {
    SENT,

    /** No usable device key, so nothing could be sealed. Nothing will send until that is fixed. */
    NO_KEY,

    /** No destination number saved: this phone has no fallback at all. */
    NO_DESTINATION,

    /** Held back on purpose — another SMS went out inside the minimum interval. */
    RATE_LIMITED,

    /** Handed to the radio, which did not confirm it in time. */
    TIMED_OUT,

    /** The radio was reachable and there was no service to send through. */
    NO_SERVICE,

    /** The radio was off — airplane mode, or shut down by the platform. */
    RADIO_OFF,

    /** The carrier refused it for volume. A bill and a rate, not a fault in this app. */
    CARRIER_LIMIT,

    /** Refused for a reason the platform did not narrow down, or the attempt threw. */
    FAILED,
}

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
     *
     * Kept as the boolean [IncidentTransport] wants. Callers that have somewhere to record
     * what happened should use [send]: a `false` here covers five different situations, one
     * of which is the fallback deciding on purpose not to send.
     */
    suspend fun sendEncryptedSmsAlert(
        destinationNumber: String,
        message: String,
    ): Boolean = send(destinationNumber, message) == SmsSendOutcome.SENT

    /**
     * The same send, saying which of its outcomes happened.
     *
     * The boolean above was the only answer this class gave, and it made the last channel an
     * owner has unreadable from outside: a night with no SMS looked identical whether the
     * fallback was never configured, held back by its own rate limit, or tried and refused by
     * the radio. Those have three different answers and the black box could record none of them.
     */
    suspend fun send(
        destinationNumber: String,
        message: String,
    ): SmsSendOutcome = sendMutex.withLock {
        val deviceKey = smsKeyProvider()?.takeIf { EncryptedSmsCodec.isValidKeyBase64(it) }
            ?: return@withLock SmsSendOutcome.NO_KEY
        if (destinationNumber.isBlank()) return@withLock SmsSendOutcome.NO_DESTINATION
        val now = nowMs()
        val last = lastSmsSentMs.get()
        if (last > 0L && now - last in 0 until minIntervalMs) {
            return@withLock SmsSendOutcome.RATE_LIMITED
        }
        // Body first, timestamp last: if only the leading parts of a multipart SMS arrive,
        // what happened is worth more than exactly when.
        val payload = "${truncateUtf8(message, maxPayloadBytes)}\nTIME:$now"
        val encryptedBody = EncryptedSmsCodec.encryptSmsPayload(payload, deviceKey)
        return try {
            val result = withTimeoutOrNull(sendTimeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val cancel = dispatcher.sendMultipart(destinationNumber, encryptedBody) { radio ->
                        if (continuation.isActive) continuation.resume(radio)
                    }
                    continuation.invokeOnCancellation { cancel() }
                }
            }
            when (result) {
                null -> SmsSendOutcome.TIMED_OUT
                SmsRadioResult.SENT -> {
                    lastSmsSentMs.set(nowMs())
                    SmsSendOutcome.SENT
                }
                SmsRadioResult.NO_SERVICE -> SmsSendOutcome.NO_SERVICE
                SmsRadioResult.RADIO_OFF -> SmsSendOutcome.RADIO_OFF
                SmsRadioResult.CARRIER_LIMIT -> SmsSendOutcome.CARRIER_LIMIT
                SmsRadioResult.FAILED -> SmsSendOutcome.FAILED
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SmsSendOutcome.FAILED
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
        onResult: (SmsRadioResult) -> Unit,
    ): () -> Unit {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (smsManager == null) {
            onResult(SmsRadioResult.FAILED)
            return {}
        }

        val parts = try {
            smsManager.divideMessage(message)
        } catch (_: Exception) {
            onResult(SmsRadioResult.FAILED)
            return {}
        }
        if (parts.isNullOrEmpty()) {
            onResult(SmsRadioResult.FAILED)
            return {}
        }
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val completed = AtomicBoolean(false)
        val remaining = AtomicInteger(parts.size)
        // The first refusal among the parts wins, and it is kept rather than reduced to a
        // boolean: a message split into chunks has not been delivered if any chunk was
        // refused, and why it was refused is the whole answer a reader needs.
        val firstRefusal = AtomicReference<SmsRadioResult?>(null)
        lateinit var receiver: BroadcastReceiver

        fun finish(result: SmsRadioResult) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { context.unregisterReceiver(receiver) }
            onResult(result)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode != Activity.RESULT_OK) {
                    firstRefusal.compareAndSet(null, radioResultOf(resultCode))
                }
                if (remaining.decrementAndGet() == 0) {
                    finish(firstRefusal.get() ?: SmsRadioResult.SENT)
                }
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
            finish(SmsRadioResult.FAILED)
        }
        return { finish(SmsRadioResult.FAILED) }
    }

    /**
     * The platform's result code as a cause this app can act on. The values are the ones
     * SmsManager has published since the beginning and are referenced by name so a rename
     * cannot silently turn a known cause into an unknown one.
     */
    private fun radioResultOf(resultCode: Int): SmsRadioResult = when (resultCode) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> SmsRadioResult.NO_SERVICE
        SmsManager.RESULT_ERROR_RADIO_OFF -> SmsRadioResult.RADIO_OFF
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SmsRadioResult.CARRIER_LIMIT
        else -> SmsRadioResult.FAILED
    }
}
