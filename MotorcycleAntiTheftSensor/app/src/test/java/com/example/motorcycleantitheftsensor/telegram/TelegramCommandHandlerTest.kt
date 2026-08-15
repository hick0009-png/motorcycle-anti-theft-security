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
        gate.complete(Unit)
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

        handler.handle("tg-status", RemoteCommand.Status) { replies += it }
    }

    @Test
    fun cancellingAnIncompleteArmCleansUpToDisarmed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val replies = Channel<String>(Channel.UNLIMITED)
        val handler = handler(ArmingDelay { gate.await() })
        val arm = launch { handler.handle("tg-cancel", RemoteCommand.Arm) { replies.send(it) } }
        runCurrent()

        arm.cancelAndJoin()
        handler.handle("tg-status-after-cancel", RemoteCommand.Status) { replies.send(it) }
    }

    @Test
    fun disarmDelegatesToCoordinatorAndRepliesSuccess() = runTest {
        val replies = mutableListOf<String>()
        val handler = handler(
            armingDelay = ArmingDelay { },
            initialState = ProtectionState.ARMED_HEALTHY,
        )

        handler.handle("tg-disarm", RemoteCommand.Disarm) { replies += it }

        assertEquals("✅ ปลดการป้องกันสำเร็จ", replies.single())
    }

    @Test
    fun helpCommandRepliesWithParameterlessDisarmGuide() = runTest {
        val replies = mutableListOf<String>()
        val handler = handler(armingDelay = ArmingDelay { })

        handler.handle("tg-help", RemoteCommand.Help) { replies += it }

        val reply = replies.single()
        assertEquals("ℹ️ คำสั่ง: /status, /arm, /disarm, /sensitivity 1-10", reply)
        assertFalse(reply.contains("<รหัส>"))
        assertFalse(reply.contains("Authenticator", ignoreCase = true))
        assertFalse(reply.contains("totp", ignoreCase = true))
    }

    @Test
    fun unknownCommandRepliesWithHelpGuidance() = runTest {
        val replies = mutableListOf<String>()
        val handler = handler(armingDelay = ArmingDelay { })

        handler.handle("tg-unknown", RemoteCommand.Unknown) { replies += it }

        val reply = replies.single()
        assertEquals("ℹ️ ไม่พบคำสั่ง พิมพ์ /help เพื่อดูคำสั่งที่ใช้ได้", reply)
    }
}

private fun handler(
    armingDelay: ArmingDelay,
    commandTimeoutMs: Long = 20_000L,
    initialState: ProtectionState = ProtectionState.DISARMED_ONLINE,
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
            state = initialState,
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
