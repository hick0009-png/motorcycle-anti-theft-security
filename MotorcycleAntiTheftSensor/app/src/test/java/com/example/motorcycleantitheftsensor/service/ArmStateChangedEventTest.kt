package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ArmStateChangedEventTest {
    @Test
    fun actionIsScopedToThisApplicationPackage() {
        assertEquals(
            "com.example.motorcycleantitheftsensor.ARM_STATE_CHANGED",
            ArmStateChangedEvent.ACTION
        )
    }
}
