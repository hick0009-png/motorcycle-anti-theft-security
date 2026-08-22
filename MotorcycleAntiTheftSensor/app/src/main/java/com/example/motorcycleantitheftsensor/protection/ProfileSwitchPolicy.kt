package com.example.motorcycleantitheftsensor.protection

/**
 * Pure state machine for a confirmed profile change. Every persisted phase converges to
 * the same truthful final state: disarmed with the target profile selected, the armed
 * snapshot cleared, and no transaction left behind. The old profile is never rearmed.
 */
class ProfileSwitchPolicy {

    data class Recovery(
        val selectedProfile: ProtectionProfile,
        val switchTransaction: ProfileSwitchTransaction?,
        val armedProfileSnapshotCleared: Boolean,
        val protectionState: ProtectionState,
    )

    fun resume(transaction: ProfileSwitchTransaction): Recovery = Recovery(
        selectedProfile = transaction.targetProfile,
        switchTransaction = null,
        armedProfileSnapshotCleared = true,
        protectionState = ProtectionState.DISARMED_ONLINE,
    )

    /** Returns the next durable phase, or null when the transaction is complete. */
    fun nextPhase(phase: ProfileSwitchPhase): ProfileSwitchPhase? = when (phase) {
        ProfileSwitchPhase.STOP_REQUESTED -> ProfileSwitchPhase.OLD_RUNTIME_QUIESCED
        ProfileSwitchPhase.OLD_RUNTIME_QUIESCED -> ProfileSwitchPhase.SNAPSHOT_CLEARED
        ProfileSwitchPhase.SNAPSHOT_CLEARED -> ProfileSwitchPhase.NEW_PROFILE_SELECTED
        ProfileSwitchPhase.NEW_PROFILE_SELECTED -> null
    }
}
