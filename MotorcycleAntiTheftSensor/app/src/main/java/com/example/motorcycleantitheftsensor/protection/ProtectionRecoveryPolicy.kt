package com.example.motorcycleantitheftsensor.protection

data class RecoveryPlan(
    val initialState: ProtectionState,
    val restartDetectors: Boolean,
    val previousIncidentLifecycle: IncidentLifecycle? = null,
    val phase: RecoveryPhase? = null,
)

object ProtectionRecoveryPolicy {
    fun plan(persistedState: ProtectionState): RecoveryPlan = legacyPlan(persistedState)

    fun plan(
        persistedState: ProtectionState,
        intent: ProtectionContinuityIntent,
        trigger: RecoveryTrigger,
        continuityValid: Boolean,
    ): RecoveryPlan {
        if (!continuityValid) {
            return RecoveryPlan(
                initialState = ProtectionState.SETUP_REQUIRED,
                restartDetectors = false,
                phase = RecoveryPhase.RECOVERY_BLOCKED,
            )
        }

        val eligibility = ProtectionContinuityPolicy.eligibility(intent, trigger)
        if (!eligibility.restartDetectors) {
            return RecoveryPlan(
                initialState = ProtectionState.DISARMED_ONLINE,
                restartDetectors = false,
                phase = eligibility.phase,
            )
        }

        return legacyPlan(persistedState).copy(phase = eligibility.phase)
    }

    private fun legacyPlan(persistedState: ProtectionState): RecoveryPlan = when (persistedState) {
        ProtectionState.DISARMED_ONLINE,
        ProtectionState.SETUP_REQUIRED,
        ProtectionState.OFFLINE,
        -> RecoveryPlan(
            initialState = ProtectionState.DISARMED_ONLINE,
            restartDetectors = false,
        )

        ProtectionState.ALERT_ACTIVE -> RecoveryPlan(
            initialState = ProtectionState.ARMING,
            restartDetectors = true,
            previousIncidentLifecycle = IncidentLifecycle.INTERRUPTED,
        )

        ProtectionState.ARMING,
        ProtectionState.ARMED_HEALTHY,
        ProtectionState.ARMED_DEGRADED,
        -> RecoveryPlan(
            initialState = ProtectionState.ARMING,
            restartDetectors = true,
        )
    }
}
