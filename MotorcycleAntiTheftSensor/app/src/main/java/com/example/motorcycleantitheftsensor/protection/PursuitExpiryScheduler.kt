package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface PursuitExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): PursuitExpiryHandle
}

fun interface PursuitExpiryHandle {
    fun cancel()
}

class CoroutinePursuitExpiryScheduler(
    private val scope: CoroutineScope
) : PursuitExpiryScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): PursuitExpiryHandle {
        val job: Job = scope.launch {
            delay(delayMs)
            action()
        }
        return PursuitExpiryHandle { job.cancel() }
    }
}
