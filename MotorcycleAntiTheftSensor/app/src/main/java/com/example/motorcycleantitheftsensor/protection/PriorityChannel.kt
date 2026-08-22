package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.channels.Channel

class PriorityChannel<T>(
    private val capacity: Int = 1000,
    private val priorityOf: (T) -> Int = { 0 },
) {
    private val queue = java.util.PriorityQueue<PrioritizedItem<T>>(
        capacity,
        compareByDescending<PrioritizedItem<T>> { it.priority }
            .thenBy { it.sequenceNumber }
    )
    // A wake-up is state, not one event per queued item. Coalescing keeps the
    // signalling path bounded and avoids draining stale wake-ups after bursts.
    private val signalChannel = Channel<Unit>(Channel.CONFLATED)

    private data class PrioritizedItem<T>(
        val item: T,
        val priority: Int,
        val sequenceNumber: Long,
    )

    private var sequenceCounter = 0L

    fun trySend(element: T): Boolean {
        synchronized(queue) {
            val priority = priorityOf(element)
            val seq = sequenceCounter++
            val newItem = PrioritizedItem(element, priority, seq)
            if (queue.size >= capacity) {
                // If queue is full, find lowest priority item in queue.
                // If multiple items tie for lowest priority, pick the newest one (largest sequence number)
                // to evict, preserving older items of equal priority (FIFO).
                val lowest = queue.minWithOrNull(
                    compareBy<PrioritizedItem<T>> { it.priority }
                        .thenByDescending { it.sequenceNumber }
                )
                if (lowest != null && priority > lowest.priority) {
                    queue.remove(lowest)
                    queue.offer(newItem)
                    signalChannel.trySend(Unit)
                    return true
                }
                return false
            } else {
                queue.offer(newItem)
                signalChannel.trySend(Unit)
                return true
            }
        }
    }

    suspend fun receive(): T {
        while (true) {
            val item = synchronized(queue) {
                if (queue.isNotEmpty()) queue.poll()?.item else null
            }
            if (item != null) return item
            signalChannel.receive()
        }
    }

    val size: Int
        get() = synchronized(queue) { queue.size }
}
