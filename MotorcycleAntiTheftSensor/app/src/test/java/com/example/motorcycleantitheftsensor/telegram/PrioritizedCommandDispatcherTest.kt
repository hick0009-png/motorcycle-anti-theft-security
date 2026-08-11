package com.example.motorcycleantitheftsensor.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrioritizedCommandDispatcherTest {
    @Test
    fun disarmExecutesWithoutWaitingForBlockedArmReply() = runTest {
        val armStarted = CompletableDeferred<Unit>()
        val blockedReply = CompletableDeferred<Unit>()
        val disarmApplied = CompletableDeferred<Unit>()
        val dispatcher = PrioritizedCommandDispatcher<RemoteCommand>(
            scope = this,
            isArm = { it == RemoteCommand.Arm },
            isDisarm = { it is RemoteCommand.Disarm },
            execute = { command ->
                when (command) {
                    RemoteCommand.Arm -> {
                        armStarted.complete(Unit)
                        withContext(NonCancellable) { blockedReply.await() }
                    }
                    is RemoteCommand.Disarm -> disarmApplied.complete(Unit)
                    else -> Unit
                }
            },
        )

        dispatcher.submit(RemoteCommand.Arm)
        armStarted.await()
        dispatcher.submit(RemoteCommand.Disarm("123456"))
        runCurrent()

        assertTrue(disarmApplied.isCompleted)
        blockedReply.complete(Unit)
    }
}
