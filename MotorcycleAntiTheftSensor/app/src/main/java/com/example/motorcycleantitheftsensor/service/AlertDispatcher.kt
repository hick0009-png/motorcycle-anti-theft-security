package com.example.motorcycleantitheftsensor.service

data class AlertEvent(val type: String, val message: String, val timestampMs: Long)

fun interface AlertTransport {
    fun send(message: String): Boolean
}

enum class AlertDispatchResult {
    TelegramDelivered,
    SmsFallbackDelivered,
    NoFallbackConfigured,
    DeliveryFailed
}

class AlertDispatcher(
    private val telegram: AlertTransport,
    private val sms: AlertTransport,
    private val smsConfigured: Boolean
) {
    fun dispatch(alert: AlertEvent): AlertDispatchResult {
        val telegramSent = try {
            telegram.send(format(alert))
        } catch (_: Exception) {
            false
        }
        if (telegramSent) return AlertDispatchResult.TelegramDelivered
        if (!smsConfigured) return AlertDispatchResult.NoFallbackConfigured
        val smsSent = try {
            sms.send(format(alert))
        } catch (_: Exception) {
            false
        }
        return if (smsSent) {
            AlertDispatchResult.SmsFallbackDelivered
        } else {
            AlertDispatchResult.DeliveryFailed
        }
    }

    private fun format(alert: AlertEvent): String =
        "[${alert.type}] ${alert.message}\nTimestamp: ${alert.timestampMs}"
}
