package com.example.motorcycleantitheftsensor.telegram

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramPollingSessionBoundaryTest {
    @Test
    fun shutdownWaitsForOldWorkerAndCancelsQueuedCommandBeforeReturning() = runTest {
        val workerStarted = CountDownLatch(1)
        val allowOldWorkerToFinish = CountDownLatch(1)
        val staleCursorCommitFinished = AtomicBoolean(false)
        val worker = thread(name = "paused-old-polling-worker") {
            workerStarted.countDown()
            check(allowOldWorkerToFinish.await(2, TimeUnit.SECONDS))
            staleCursorCommitFinished.set(true)
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))

        val commandJob = SupervisorJob()
        val commandJobStopped = CountDownLatch(1)
        commandJob.invokeOnCompletion { commandJobStopped.countDown() }
        val allowQueuedDispatch = CompletableDeferred<Unit>()
        val staleCommandDispatched = AtomicBoolean(false)
        CoroutineScope(commandJob + Dispatchers.Default).async {
            allowQueuedDispatch.await()
            staleCommandDispatched.set(true)
        }

        val shutdown = async(Dispatchers.Default) {
            awaitTelegramPollingSessionShutdown(worker, commandJob)
        }

        try {
            assertTrue(commandJobStopped.await(2, TimeUnit.SECONDS))
            assertFalse(shutdown.isCompleted)
            assertFalse(staleCursorCommitFinished.get())

            allowOldWorkerToFinish.countDown()
            shutdown.await()
            allowQueuedDispatch.complete(Unit)

            assertTrue(staleCursorCommitFinished.get())
            assertTrue(commandJob.isCancelled)
            assertFalse(staleCommandDispatched.get())
        } finally {
            allowOldWorkerToFinish.countDown()
            worker.join(2_000L)
            commandJob.cancel()
        }
    }
}
