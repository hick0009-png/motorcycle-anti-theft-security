package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProtectionPersistenceRequest(
    val snapshot: ProtectionSnapshot,
    val lastServiceHeartbeatAtMs: Long?,
)

enum class ProtectionPersistenceOutcome {
    COMMITTED,
    SUPERSEDED,
}

class ProtectionStatePersistenceArbiter(
    private val writeCompatibilityArmed: suspend (Boolean) -> Unit,
    private val writeSnapshot: suspend (ProtectionSnapshot, Long?) -> Unit,
) {
    private val mutex = Mutex()
    private var highestCommittedRevision = Long.MIN_VALUE

    suspend fun persist(request: ProtectionPersistenceRequest): ProtectionPersistenceOutcome =
        mutex.withLock {
            if (request.snapshot.revision < highestCommittedRevision) {
                return@withLock ProtectionPersistenceOutcome.SUPERSEDED
            }

            writeSnapshot(request.snapshot, request.lastServiceHeartbeatAtMs)
            writeCompatibilityArmed(request.snapshot.state in PERSISTED_ARMED_STATES)
            highestCommittedRevision = request.snapshot.revision
            ProtectionPersistenceOutcome.COMMITTED
        }

    private companion object {
        val PERSISTED_ARMED_STATES = setOf(
            ProtectionState.ARMING,
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
    }
}
