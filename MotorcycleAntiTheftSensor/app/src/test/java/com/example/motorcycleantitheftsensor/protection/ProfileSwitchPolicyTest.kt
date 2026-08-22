package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSwitchPolicyTest {

    private val policy = ProfileSwitchPolicy()

    private fun transactionAt(phase: ProfileSwitchPhase) = ProfileSwitchTransaction(
        transactionId = "txn-1",
        oldArmedSessionId = "session-old",
        targetProfile = ProtectionProfile.ENTRY,
        phase = phase,
    )

    @Test
    fun crashAtEveryPhaseConvergesToDisarmedSelectedTarget() {
        ProfileSwitchPhase.entries.forEach { phase ->
            val recovered = policy.resume(transactionAt(phase))

            assertEquals(ProtectionProfile.ENTRY, recovered.selectedProfile)
            assertNull(recovered.switchTransaction)
            assertTrue(recovered.armedProfileSnapshotCleared)
            assertEquals(ProtectionState.DISARMED_ONLINE, recovered.protectionState)
        }
    }

    @Test
    fun repeatedResumeOfTheSameTransactionIsIdempotent() {
        val transaction = transactionAt(ProfileSwitchPhase.STOP_REQUESTED)

        val first = policy.resume(transaction)
        val second = policy.resume(transaction)

        assertEquals(first, second)
    }

    @Test
    fun phasesAdvanceInDurableOrder() {
        assertEquals(
            ProfileSwitchPhase.OLD_RUNTIME_QUIESCED,
            policy.nextPhase(ProfileSwitchPhase.STOP_REQUESTED),
        )
        assertEquals(
            ProfileSwitchPhase.SNAPSHOT_CLEARED,
            policy.nextPhase(ProfileSwitchPhase.OLD_RUNTIME_QUIESCED),
        )
        assertEquals(
            ProfileSwitchPhase.NEW_PROFILE_SELECTED,
            policy.nextPhase(ProfileSwitchPhase.SNAPSHOT_CLEARED),
        )
        assertNull(policy.nextPhase(ProfileSwitchPhase.NEW_PROFILE_SELECTED))
    }
}
