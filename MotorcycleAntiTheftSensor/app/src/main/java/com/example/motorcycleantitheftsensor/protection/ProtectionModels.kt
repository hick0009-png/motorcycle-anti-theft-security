package com.example.motorcycleantitheftsensor.protection

enum class ProtectionState {
    SETUP_REQUIRED,
    DISARMED_ONLINE,
    ARMING,
    ARMED_HEALTHY,
    ARMED_DEGRADED,
    ALERT_ACTIVE,
    OFFLINE,
}

enum class SensorKind {
    VIBRATION,
    LIGHT,
    POWER_THERMAL,
    MICROPHONE,
    LOCATION,
}

enum class SensorHealthState {
    UNAVAILABLE,
    AVAILABLE,
    HEALTHY,
    STALE,
    FAILED,
}

enum class IncidentSeverity {
    WARNING,
    CRITICAL,
}

enum class IncidentSource {
    REAL,
    DEMO,
}

enum class IncidentLifecycle {
    OPEN,
    CLOSED,
    INTERRUPTED,
}

enum class DeliveryState {
    PENDING,
    SENT,
    FAILED,
    NOT_ELIGIBLE,
}

enum class CommandOrigin {
    LOCAL,
    TELEGRAM,
    RECOVERY,
}

enum class CommandOutcome {
    RECEIVED,
    APPLIED,
    REJECTED,
    UNKNOWN,
}

data class SensorHealth(
    val state: SensorHealthState,
    val lastSampleAtMs: Long? = null,
    val detail: String? = null,
)

data class IncidentSummary(
    val id: String,
    val source: IncidentSource,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class ProtectionCommandResult(
    val commandId: String,
    val outcome: CommandOutcome,
    val resultingState: ProtectionState,
    val reason: String,
)

data class ProtectionSnapshot(
    val state: ProtectionState,
    val lastTransitionAtMs: Long,
    val serviceRunning: Boolean,
    val telegramPolling: Boolean,
    val telegramReachable: Boolean,
    val lastTelegramContactAtMs: Long?,
    val permissionBlockers: Set<String>,
    val sensorHealth: Map<SensorKind, SensorHealth>,
    val degradationReasons: Set<String>,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Float?,
    val lastIncident: IncidentSummary?,
    val lastDeliveryState: DeliveryState?,
    val demoModeEnabled: Boolean,
    val revision: Long = 0L,
) {
    companion object {
        fun offline(nowMs: Long): ProtectionSnapshot = ProtectionSnapshot(
            state = ProtectionState.OFFLINE,
            lastTransitionAtMs = nowMs,
            serviceRunning = false,
            telegramPolling = false,
            telegramReachable = false,
            lastTelegramContactAtMs = null,
            permissionBlockers = emptySet(),
            sensorHealth = emptyMap(),
            degradationReasons = emptySet(),
            batteryLevelPercent = null,
            batteryTemperatureCelsius = null,
            lastIncident = null,
            lastDeliveryState = null,
            demoModeEnabled = false,
        )
    }
}
