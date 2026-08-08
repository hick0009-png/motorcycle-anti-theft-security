package com.example.motorcycleantitheftsensor.protection

data class RecoveryPlan(
    val initialState: ProtectionState,
    val restartDetectors: Boolean,
    val previousIncidentLifecycle: IncidentLifecycle? = null,
)

object ProtectionRecoveryPolicy {
    fun plan(persistedState: ProtectionState): RecoveryPlan = when (persistedState) {
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
