package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.service.SensorServiceAction.Arm
import com.example.motorcycleantitheftsensor.service.SensorServiceAction.Ignore
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorServiceActionTest {
    @Test
    fun armAndDisarmMapOnlyFromServiceConstants() {
        assertEquals(Arm, SensorServiceAction.from(SensorService.ACTION_ARM))
        assertEquals(Ignore, SensorServiceAction.from("third.party.DISARM"))
    }
}
