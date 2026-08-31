package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.LocationTrackingHealth

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

enum class ChargingState {
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING,
    UNKNOWN,
}

/**
 * Single source of truth for charging connectivity: true while the phone draws charger
 * power, false when unplugged, null when the state is unknown. Used by the armed-session
 * Power arbiter and the Power summary rows.
 */
val ChargingState.chargingConnected: Boolean?
    get() = when (this) {
        ChargingState.CHARGING, ChargingState.FULL -> true
        ChargingState.DISCHARGING, ChargingState.NOT_CHARGING -> false
        ChargingState.UNKNOWN -> null
    }

enum class LocationTrackingState {
    STOPPED,
    WAITING_FOR_FIX,
    TRACKING,
    STALE,
    UNAVAILABLE,
    FAILED,
}

enum class IncidentSeverity {
    WARNING,
    CRITICAL,
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

enum class PersistenceSource {
    SNAPSHOT,
    INCIDENT_HISTORY,
    MOVEMENT_TRACKING,
}

enum class LocationFailureCode {
    HARDWARE_UNAVAILABLE,
    PERMISSION_DENIED,
    NO_PROVIDERS_AVAILABLE,
    REGISTRATION_FAILED,
}

enum class FreshnessState { MISSING, FRESH, STALE, CLOCK_ANOMALY }

data class LocationHealthDetail(
    val trackingState: LocationTrackingState = LocationTrackingState.STOPPED,
    val isRegistered: Boolean = false,
    val hardwareAvailable: Boolean = true,
    val permissionGranted: Boolean = true,
    val lastFixWallClockMs: Long? = null,
    val lastFixElapsedMs: Long? = null,
    val accuracyMeters: Float? = null,
    val failureCode: LocationFailureCode? = null,
    val failureReason: String? = null,
    val trackingMode: String? = null,
)

data class MicrophoneHealthDetail(
    val audioState: AudioRuntimeState = AudioRuntimeState.OFF,
    val isRegistered: Boolean = false,
    val modelReady: Boolean = false,
    val lastAudioSampleElapsedMs: Long? = null,
    val lastAudioSampleAtMs: Long? = null,
    val hardwareAvailable: Boolean = true,
    val permissionGranted: Boolean = true,
    val failureReason: String? = null,
)

data class PowerThermalHealthDetail(
    val sourceAvailable: Boolean = true,
    val isRegistered: Boolean = false,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    val batteryLevelPercent: Int? = null,
    val temperatureCelsius: Float? = null,
    val lastUpdateWallClockMs: Long? = null,
)

data class VibrationHealthDetail(
    val hardwareAvailable: Boolean = true,
    val isRegistered: Boolean = false,
    val lastSampleWallClockMs: Long? = null,
    val lastSampleElapsedMs: Long? = null,
    val failureReason: String? = null,
)

data class LightHealthDetail(
    val hardwareSupported: Boolean = true,
    val isRegistered: Boolean = false,
    val lastLux: Double? = null,
    val lastSampleWallClockMs: Long? = null,
    val lastSampleElapsedMs: Long? = null,
    /** Armed-session thresholds after the current Arm reference has been applied. */
    val armedWitnessDarkThresholdLux: Double? = null,
    val armedWitnessLitThresholdLux: Double? = null,
    val failureReason: String? = null,
)

data class SensorReadingSummary(
    val value: Double?,
    val unit: String?,
    val label: String,
)

data class SensorHealth(
    val state: SensorHealthState,
    val lastSampleAtMs: Long? = null,
    val detail: String? = null,
    val latestReading: SensorReadingSummary? = null,
    val locationDetail: LocationHealthDetail? = null,
    val microphoneDetail: MicrophoneHealthDetail? = null,
    val powerThermalDetail: PowerThermalHealthDetail? = null,
    val vibrationDetail: VibrationHealthDetail? = null,
    val lightDetail: LightHealthDetail? = null,
)

data class IncidentSummary(
    val id: String,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
    val type: IncidentType? = null,
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
    val protectionActivatedAtMs: Long? = null,
    val sensitivityLevel: Int = 5,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    val lastServiceHeartbeatAtMs: Long? = null,
    val revision: Long = 0L,
    val sensorFusionConfiguration: SensorFusionConfiguration? = null,
    val sensorGenerationId: Long = 0L,
    val armedProfileSnapshot: ArmedProfileSnapshot? = null,
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
            protectionActivatedAtMs = null,
            sensitivityLevel = 5,
            chargingState = ChargingState.UNKNOWN,
            lastServiceHeartbeatAtMs = null,
            revision = 0L,
            sensorFusionConfiguration = null,
            sensorGenerationId = 0L,
        )
    }
}

fun LocationTrackingHealth.toSensorHealth(): SensorHealth = SensorHealth(
    state = when (trackingState) {
        LocationTrackingState.STOPPED,
        LocationTrackingState.WAITING_FOR_FIX -> SensorHealthState.AVAILABLE
        LocationTrackingState.TRACKING -> SensorHealthState.HEALTHY
        LocationTrackingState.STALE -> SensorHealthState.STALE
        LocationTrackingState.UNAVAILABLE -> SensorHealthState.UNAVAILABLE
        LocationTrackingState.FAILED -> SensorHealthState.FAILED
    },
    lastSampleAtMs = lastFixWallClockMs,
    locationDetail = LocationHealthDetail(
        trackingState = trackingState,
        isRegistered = isRegistered,
        hardwareAvailable = hardwareAvailable,
        permissionGranted = permissionGranted,
        lastFixWallClockMs = lastFixWallClockMs,
        lastFixElapsedMs = lastFixElapsedMs,
        accuracyMeters = accuracyMeters,
        failureCode = failureCode,
        failureReason = failureReason,
    ),
)
