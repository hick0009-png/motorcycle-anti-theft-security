package com.example.motorcycleantitheftsensor.protection

enum class DesiredService {
    RUNNING,
    STOPPED_BY_OWNER,
}

enum class DesiredProtection {
    DISARMED,
    ARMED,
}

enum class RecoveryTrigger {
    PROCESS_RECREATION,
    ANDROID_BOOT,
    ANDROID_USER_UNLOCKED,
    PACKAGE_REPLACED,
    WATCHDOG,
    MANUAL_REOPEN,
}

enum class RecoveryPhase {
    RECOVERY_PENDING,
    RECALIBRATING,
    RECOVERED_HEALTHY,
    RECOVERED_DEGRADED,
    RECOVERY_BLOCKED,
    NOT_RECOVERED_OWNER_STOPPED,
    NOT_RECOVERED_DISARMED,
    NOT_RECOVERED_BOOT_DISABLED,
}

data class ProtectionContinuityIntent(
    val desiredService: DesiredService,
    val desiredProtection: DesiredProtection,
    val autoRecoveryAfterBoot: Boolean,
    val armedSessionId: String? = null,
)

data class RecoveryEligibility(
    val phase: RecoveryPhase,
    val restartDetectors: Boolean,
    val resultingDesiredProtection: DesiredProtection,
)

object ProtectionContinuityPolicy {
    fun allowsServiceStart(
        intent: ProtectionContinuityIntent,
        trigger: RecoveryTrigger,
        continuityValid: Boolean,
    ): Boolean {
        if (!continuityValid || intent.desiredService == DesiredService.STOPPED_BY_OWNER) {
            return false
        }
        return trigger !in BOOT_RECOVERY_TRIGGERS || intent.autoRecoveryAfterBoot
    }

    fun eligibility(
        intent: ProtectionContinuityIntent,
        trigger: RecoveryTrigger,
    ): RecoveryEligibility = when {
        intent.desiredService == DesiredService.STOPPED_BY_OWNER -> RecoveryEligibility(
            phase = RecoveryPhase.NOT_RECOVERED_OWNER_STOPPED,
            restartDetectors = false,
            resultingDesiredProtection = DesiredProtection.DISARMED,
        )

        intent.desiredProtection == DesiredProtection.DISARMED -> RecoveryEligibility(
            phase = RecoveryPhase.NOT_RECOVERED_DISARMED,
            restartDetectors = false,
            resultingDesiredProtection = DesiredProtection.DISARMED,
        )

        trigger in BOOT_RECOVERY_TRIGGERS && !intent.autoRecoveryAfterBoot -> RecoveryEligibility(
            phase = RecoveryPhase.NOT_RECOVERED_BOOT_DISABLED,
            restartDetectors = false,
            resultingDesiredProtection = DesiredProtection.DISARMED,
        )

        else -> RecoveryEligibility(
            phase = RecoveryPhase.RECOVERY_PENDING,
            restartDetectors = true,
            resultingDesiredProtection = DesiredProtection.ARMED,
        )
    }

    private val BOOT_RECOVERY_TRIGGERS = setOf(
        RecoveryTrigger.ANDROID_BOOT,
        RecoveryTrigger.ANDROID_USER_UNLOCKED,
    )
}
