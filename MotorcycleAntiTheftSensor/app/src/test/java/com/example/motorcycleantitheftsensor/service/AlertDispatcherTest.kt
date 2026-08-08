package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDispatcherTest {
    private val alert = AlertEvent("VIBRATION", "test", 1L)

    @Test
    fun successfulTelegramDeliveryDoesNotSendSms() {
        var smsCalls = 0
        val dispatcher = AlertDispatcher(
            telegram = AlertTransport { true },
            sms = AlertTransport { smsCalls++; true },
            smsConfigured = true
        )

        assertEquals(AlertDispatchResult.TelegramDelivered, dispatcher.dispatch(alert))
        assertEquals(0, smsCalls)
    }

    @Test
    fun failedTelegramDeliveryUsesConfiguredSmsFallback() {
        var smsCalls = 0
        val dispatcher = AlertDispatcher(
            telegram = AlertTransport { false },
            sms = AlertTransport { smsCalls++; true },
            smsConfigured = true
        )

        assertEquals(AlertDispatchResult.SmsFallbackDelivered, dispatcher.dispatch(alert))
        assertEquals(1, smsCalls)
    }
}
