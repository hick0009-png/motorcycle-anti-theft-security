package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ReadinessReport
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorServiceControllerTest {
    @Test
    fun disarmStopsDetectorsButKeepsForegroundAndPolling() = runTest {
        val runtime = RecordingProtectionRuntime(detectorsRunning = true)
        val environment = RecordingServiceEnvironment()
        val controller = SensorServiceController(
            coordinator = realCoordinator(runtime),
            environment = environment,
        )

        controller.handle(SensorServiceAction.Disarm, "service-1")

        assertFalse(runtime.detectorsRunning)
        assertTrue(environment.foregroundRunning)
        assertTrue(environment.telegramPolling)
        assertEquals(ProtectionState.DISARMED_ONLINE, controller.snapshot.value.state)
    }

    @Test
    fun explicitStopIsTheOnlyActionThatStopsRemoteControl() = runTest {
        val environment = RecordingServiceEnvironment(
            foregroundRunning = true,
            telegramPolling = true,
        )
        val controller = SensorServiceController(
            coordinator = realCoordinator(RecordingProtectionRuntime()),
            environment = environment,
        )

        controller.handle(SensorServiceAction.Stop, "service-2")

        assertFalse(environment.foregroundRunning)
        assertFalse(environment.telegramPolling)
        assertEquals(ProtectionState.OFFLINE, controller.snapshot.value.state)
        assertFalse(controller.snapshot.value.serviceRunning)
        assertFalse(controller.snapshot.value.telegramPolling)
    }

    @Test
    fun pollingIsReportedActiveOnlyWhenTheTransportActuallyStarts() = runTest {
        val environment = RecordingServiceEnvironment(pollingCanStart = false)
        val controller = SensorServiceController(
            coordinator = realCoordinator(RecordingProtectionRuntime()),
            environment = environment,
        )

        controller.handle(SensorServiceAction.Start, "service-3")

        assertFalse(environment.telegramPolling)
        assertFalse(controller.snapshot.value.telegramPolling)
    }
}

private fun realCoordinator(runtime: ProtectionRuntime): ProtectionCoordinator = ProtectionCoordinator(
    initialSnapshot = ProtectionSnapshot.offline(nowMs = 0L).copy(
        state = ProtectionState.DISARMED_ONLINE,
        serviceRunning = true,
        telegramPolling = true,
    ),
    runtime = runtime,
    armingDelay = ArmingDelay { },
    clock = ProtectionClock { 1_000L },
)

private class RecordingProtectionRuntime(
    var detectorsRunning: Boolean = false,
) : ProtectionRuntime {
    override fun readiness(): ReadinessReport = ReadinessReport(emptySet(), emptySet())

    override fun startDetectors(): DetectorStartResult {
        detectorsRunning = true
        return DetectorStartResult(started = true)
    }

    override fun stopDetectors() {
        detectorsRunning = false
    }

    override fun applySensitivity(level: Int) = Unit

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = emptyMap()
}

private class RecordingServiceEnvironment(
    var foregroundRunning: Boolean = false,
    var telegramPolling: Boolean = false,
    private val pollingCanStart: Boolean = true,
) : ServiceEnvironment {
    override fun ensureForeground() {
        foregroundRunning = true
    }

    override suspend fun stopForegroundAndSelf() {
        foregroundRunning = false
    }

    override fun ensureTelegramPolling(): Boolean {
        telegramPolling = pollingCanStart
        return telegramPolling
    }

    override fun stopTelegramPolling() {
        telegramPolling = false
    }

    override fun renderNotification(snapshot: ProtectionSnapshot) = Unit
}
