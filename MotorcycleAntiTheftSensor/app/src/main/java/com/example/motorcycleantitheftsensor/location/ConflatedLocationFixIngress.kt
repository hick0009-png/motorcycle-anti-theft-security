package com.example.motorcycleantitheftsensor.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

interface LocationFixIngress {
    fun offer(fix: TrackedLocationFix): Boolean
    fun cancelPending()
    suspend fun awaitClosed()
}

class ConflatedLocationFixIngress(
    scope: CoroutineScope,
    private val consume: suspend (TrackedLocationFix) -> Unit,
) : LocationFixIngress {
    private val channel = Channel<TrackedLocationFix>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var lastOfferedElapsedMs: Long = -1L
    private val lock = Any()
    @Volatile
    private var closed = false

    private val consumerJob: Job = scope.launch {
        for (fix in channel) {
            consume(fix)
        }
    }

    override fun offer(fix: TrackedLocationFix): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (fix.elapsedRealtimeMs <= lastOfferedElapsedMs) return false
            lastOfferedElapsedMs = fix.elapsedRealtimeMs
            return channel.trySend(fix).isSuccess
        }
    }

    override fun cancelPending() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel.close()
        }
        consumerJob.cancel()
    }

    override suspend fun awaitClosed() {
        cancelPending()
        consumerJob.join()
    }
}
