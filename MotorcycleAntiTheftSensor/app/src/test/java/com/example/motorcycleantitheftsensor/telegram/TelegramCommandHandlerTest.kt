package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.ArmingDelay
import com.example.motorcycleantitheftsensor.protection.DetectorStartResult
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionRuntime
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ReadinessReport
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramCommandHandlerTest {
    @Test
    fun armRepliesReceivedBeforeFinalAppliedResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val replies = Channel<String>(Channel.UNLIMITED)
        val handler = handler(armingDelay = ArmingDelay { gate.await() })

        val job = launch { handler.handle("tg-1", RemoteCommand.Arm) { replies.send(it) } }
        runCurrent()
        assertEquals("ARM RECEIVED — checking readiness", replies.receive())
        gate.complete(Unit)
        assertTrue(replies.receive().contains("APPLIED — ARMED_HEALTHY"))
        job.join()
    }

    @Test
    fun commandTimeoutSaysOutcomeUnknownInsteadOfSuccess() = runTest {
        val replies = mutableListOf<String>()
        val handler = handler(
            armingDelay = ArmingDelay { awaitCancellation() },
            commandTimeoutMs = 1L,
        )

        handler.handle("tg-2", RemoteCommand.Arm) { replies += it }

        assertTrue(replies.last().contains("UNKNOWN"))
        assertFalse(replies.last().contains("ARMED!"))

        handler.handle("tg-status", RemoteCommand.Status) { replies += it }
        assertTrue(replies.last().contains("Protection: DISARMED_ONLINE"))
    }

    @Test
    fun cancellingAnIncompleteArmCleansUpToDisarmed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val replies = Channel<String>(Channel.UNLIMITED)
        val handler = handler(ArmingDelay { gate.await() })
        val arm = launch { handler.handle("tg-cancel", RemoteCommand.Arm) { replies.send(it) } }
        runCurrent()
        assertEquals("ARM RECEIVED — checking readiness", replies.receive())

        arm.cancelAndJoin()
        handler.handle("tg-status-after-cancel", RemoteCommand.Status) { replies.send(it) }

        assertTrue(replies.receive().contains("Protection: DISARMED_ONLINE"))
    }
}

private fun handler(
    armingDelay: ArmingDelay,
    commandTimeoutMs: Long = 20_000L,
): TelegramCommandHandler {
    val runtime = object : ProtectionRuntime {
        override fun readiness(): ReadinessReport = ReadinessReport(emptySet(), emptySet())

        override fun startDetectors(): DetectorStartResult = DetectorStartResult(true)

        override fun stopDetectors() = Unit

        override fun applySensitivity(level: Int) = Unit

        override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = mapOf(
            SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY, 900L),
        )
    }
    val coordinator = ProtectionCoordinator(
        initialSnapshot = ProtectionSnapshot.offline(0L).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = 900L,
        ),
        runtime = runtime,
        armingDelay = armingDelay,
        clock = ProtectionClock { 1_000L },
    )
    return TelegramCommandHandler(
        coordinator = coordinator,
        statusFormatter = ProtectionStatusFormatter(),
        commandTimeoutMs = commandTimeoutMs,
    )
}
