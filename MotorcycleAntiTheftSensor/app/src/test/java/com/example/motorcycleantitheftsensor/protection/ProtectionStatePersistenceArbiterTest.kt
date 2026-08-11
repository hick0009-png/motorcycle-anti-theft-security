package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionStatePersistenceArbiterTest {
    @Test
    fun olderArmedWriteCannotOverwriteNewerDisarmedRevision() = runTest {
        val enteredOldWrite = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val committed = mutableListOf<ProtectionState>()
        val arbiter = ProtectionStatePersistenceArbiter(
            writeCompatibilityArmed = { },
            writeSnapshot = { snapshot, _ ->
                if (snapshot.revision == 4L) {
                    enteredOldWrite.complete(Unit)
                    releaseOldWrite.await()
                }
                committed += snapshot.state
            },
        )
        val armed = ProtectionSnapshot.offline(1L).copy(
            revision = 4L,
            state = ProtectionState.ARMED_HEALTHY,
        )
        val disarmed = armed.copy(revision = 5L, state = ProtectionState.DISARMED_ONLINE)

        val old = async { arbiter.persist(ProtectionPersistenceRequest(armed, 10L)) }
        enteredOldWrite.await()
        val newest = async { arbiter.persist(ProtectionPersistenceRequest(disarmed, 11L)) }
        releaseOldWrite.complete(Unit)

        assertEquals(ProtectionPersistenceOutcome.COMMITTED, old.await())
        assertEquals(ProtectionPersistenceOutcome.COMMITTED, newest.await())
        assertEquals(ProtectionState.DISARMED_ONLINE, committed.last())
        assertEquals(
            ProtectionPersistenceOutcome.SUPERSEDED,
            arbiter.persist(ProtectionPersistenceRequest(armed, 12L)),
        )
        assertEquals(ProtectionState.DISARMED_ONLINE, committed.last())
    }
}
