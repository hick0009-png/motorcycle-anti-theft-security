package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionStateTelegramNotifierTest {
    @Test
    fun doesNotSendCompletionMessages() {
        val notifier = ProtectionStateTelegramNotifier()
        assertEquals(emptyList<String>(), notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_HEALTHY))
        assertEquals(emptyList<String>(), notifier.messagesFor(ProtectionState.ARMING, ProtectionState.ARMED_DEGRADED))
    }
}
