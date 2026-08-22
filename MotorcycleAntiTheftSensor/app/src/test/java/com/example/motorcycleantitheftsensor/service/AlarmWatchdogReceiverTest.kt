package com.example.motorcycleantitheftsensor.service

import android.app.AlarmManager
import android.content.Context
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AlarmWatchdogReceiverTest {

    @Test
    fun scheduleWatchdogExecutesWithoutCrashingWhenExactAlarmAllowed() {
        val mockContext = mock(Context::class.java)
        val mockAlarmManager = mock(AlarmManager::class.java)
        whenever(mockContext.getSystemService(AlarmManager::class.java)).thenReturn(mockAlarmManager)
        whenever(mockContext.packageName).thenReturn("com.example.motorcycleantitheftsensor")
        whenever(mockAlarmManager.canScheduleExactAlarms()).thenReturn(true)

        AlarmWatchdogReceiver.scheduleWatchdog(mockContext)
    }

    @Test
    fun cancelWatchdogExecutesWithoutCrashing() {
        val mockContext = mock(Context::class.java)
        val mockAlarmManager = mock(AlarmManager::class.java)
        whenever(mockContext.getSystemService(AlarmManager::class.java)).thenReturn(mockAlarmManager)
        whenever(mockContext.packageName).thenReturn("com.example.motorcycleantitheftsensor")

        AlarmWatchdogReceiver.cancelWatchdog(mockContext)
    }
}
