package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.AUDIO_CORRELATION_WINDOW_MS
import com.example.motorcycleantitheftsensor.protection.AudioGateState
import com.example.motorcycleantitheftsensor.protection.AudioRuntimeState
import com.example.motorcycleantitheftsensor.protection.AudioTelemetry
import com.example.motorcycleantitheftsensor.protection.AudioThreatMetadata
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.GuidanceContent
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlin.math.ceil

enum class ProtectionDestination { PROTECTION, EVENTS, SETTINGS }

enum class SettingsOperation {
    CHANGE_SENSITIVITY,
    REFRESH_PERMISSIONS,
    REPLACE_BOT_TOKEN,
    SAVE_SMS_FALLBACK,
    RETRY_SETTINGS,
    RESET_PAIRING,
    UPDATE_SENSOR_CONFIG,
}

data class AudioUiTelemetry(
    val state: AudioRuntimeState = AudioRuntimeState.OFF,
    val detailCode: String? = null,
    val modelReady: Boolean = false,
    val lastSampleAgeSeconds: Long? = null,
    val approximateLevelDbfs: Double? = null,
    val baselineMedianDbfs: Double? = null,
    val baselineP95Dbfs: Double? = null,
    val gateState: AudioGateState = AudioGateState.DISABLED,
    val lastInferenceMs: Long? = null,
    val averageInferenceMs: Long? = null,
    val droppedFrames: Long = 0L,
    val restartCount: Int = 0,
    val currentCandidate: AudioThreatMetadata? = null,
    val candidateExpiresInSeconds: Int? = null,
)

data class ProtectionSettingsSummary(
    val tokenConfigured: Boolean,
    val pairedOwnerCount: Int,
    val pairingCode: String?,
    val sensitivity: Int,
    val smsFallbackConfigured: Boolean,
    val missingPermissions: Set<String>,
    val sensorConfiguration: com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration? = null,
    val sensorDisplayPreset: com.example.motorcycleantitheftsensor.protection.SensorPresetDisplay? = null,
)

data class SensorGroupUiModel(
    val capability: com.example.motorcycleantitheftsensor.protection.SensorCapability,
    val nameTh: String,
    val sensitivity: Int,
    val roleSummaryTh: String,
    val isDegraded: Boolean,
    val calibrationProgress: Float? = null,
)

data class SensorSourceUiModel(
    val source: com.example.motorcycleantitheftsensor.protection.SensorSource,
    val nameTh: String,
    val role: com.example.motorcycleantitheftsensor.protection.SensorRole,
    val isAvailable: Boolean,
    val thresholdOverride: Double? = null,
    val debounceOverrideMs: Long? = null,
)

data class ProtectionEventRow(
    val id: String,
    val type: IncidentType,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val evidenceSummary: String,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class ProtectionUiMessage(val id: Long, val content: GuidanceContent)

/**
 * Profile read model projected only from coordinator/repository data.
 * Readiness is never inferred in Compose; [setupState] comes from the domain.
 */
data class ProtectionProfileUiState(
    val selectedProfile: ProtectionProfile? = null,
    val armedProfile: ProtectionProfile? = null,
    val setupState: ProfileSetupState? = null,
    val customized: Boolean = false,
    val showPicker: Boolean = false,
    val pendingSwitchTarget: ProtectionProfile? = null,
)

data class ProtectionStatusUiState(
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
    val lastIncident: IncidentRowSummary?,
    val lastDeliveryState: DeliveryState?,
    val persistentGuidance: GuidanceContent? = null,
)

data class IncidentRowSummary(
    val id: String,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class SettingsOperationResult(val applied: Boolean, val message: String)

interface ProtectionSettingsGateway {
    suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary
    fun saveSensitivity(level: Int)
    suspend fun replaceBotToken(token: String): SettingsOperationResult
    suspend fun resetPairing(): SettingsOperationResult
    fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult
    fun saveSensorConfiguration(config: com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration): SettingsOperationResult =
        SettingsOperationResult(applied = true, message = "Sensor configuration updated")
}

data class ProtectionUiState(
    val destination: ProtectionDestination,
    val protection: ProtectionStatusUiState,
    val events: List<ProtectionEventRow>,
    val settings: ProtectionSettingsSummary,
    val armingSecondsRemaining: Int?,
    val eventsLoading: Boolean,
    val eventsError: String?,
    val operationInFlight: Boolean,
    val message: ProtectionUiMessage?,
    val settingsLoading: Boolean = false,
    val settingsLoaded: Boolean = true,
    val settingsError: String? = null,
    val protectionOperationInFlight: Boolean = false,
    val settingsOperationInFlight: Boolean = false,
    val eventsOperationInFlight: Boolean = false,
    val activeSettingsOperation: SettingsOperation? = null,
    val audio: AudioUiTelemetry = AudioUiTelemetry(),
    val profile: ProtectionProfileUiState = ProtectionProfileUiState(),
) {
    companion object {
        fun from(
            snapshot: ProtectionSnapshot,
            incidents: List<SecurityIncident>,
            settings: ProtectionSettingsSummary,
            nowMs: Long,
            destination: ProtectionDestination = ProtectionDestination.PROTECTION,
            eventsLoading: Boolean = false,
            eventsError: String? = null,
            operationInFlight: Boolean = false,
            message: ProtectionUiMessage? = null,
            settingsLoading: Boolean = false,
            settingsLoaded: Boolean = true,
            settingsError: String? = null,
            protectionOperationInFlight: Boolean = false,
            settingsOperationInFlight: Boolean = false,
            eventsOperationInFlight: Boolean = false,
            activeSettingsOperation: SettingsOperation? = null,
            audio: AudioUiTelemetry = AudioUiTelemetry(),
            profile: ProtectionProfileUiState = ProtectionProfileUiState(),
        ): ProtectionUiState = ProtectionUiState(
            destination = destination,
            protection = snapshot.toStatusUiState(),
            events = incidents
                .asSequence()
                .sortedByDescending { it.updatedAtMs }
                .map { it.toEventRow() }
                .toList(),
            settings = settings,
            armingSecondsRemaining = snapshot.armingSecondsRemaining(nowMs),
            eventsLoading = eventsLoading,
            eventsError = eventsError,
            operationInFlight = operationInFlight,
            message = message,
            settingsLoading = settingsLoading,
            settingsLoaded = settingsLoaded,
            settingsError = settingsError,
            protectionOperationInFlight = protectionOperationInFlight,
            settingsOperationInFlight = settingsOperationInFlight,
            eventsOperationInFlight = eventsOperationInFlight,
            activeSettingsOperation = activeSettingsOperation,
            audio = audio,
            profile = profile,
        )
    }
}

private fun ProtectionSnapshot.toStatusUiState(): ProtectionStatusUiState = ProtectionStatusUiState(
    state = state,
    lastTransitionAtMs = lastTransitionAtMs,
    serviceRunning = serviceRunning,
    telegramPolling = telegramPolling,
    telegramReachable = telegramReachable,
    lastTelegramContactAtMs = lastTelegramContactAtMs,
    permissionBlockers = permissionBlockers,
    sensorHealth = sensorHealth,
    degradationReasons = degradationReasons,
    batteryLevelPercent = batteryLevelPercent,
    batteryTemperatureCelsius = batteryTemperatureCelsius,
    lastIncident = lastIncident?.let {
        IncidentRowSummary(
            id = it.id,
            severity = it.severity,
            lifecycle = it.lifecycle,
            updatedAtMs = it.updatedAtMs,
            deliveryState = it.deliveryState,
        )
    },
    lastDeliveryState = lastDeliveryState,
    persistentGuidance = when {
        state == com.example.motorcycleantitheftsensor.protection.ProtectionState.SETUP_REQUIRED ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETUP_REQUIRED)
        state == com.example.motorcycleantitheftsensor.protection.ProtectionState.OFFLINE ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE)
        permissionBlockers.isNotEmpty() ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
                com.example.motorcycleantitheftsensor.protection.GuidanceCode.NOTIFICATION_PERMISSION_MISSING
            )
        else -> null
    },
)

private fun SecurityIncident.toEventRow(): ProtectionEventRow = ProtectionEventRow(
    id = id,
    type = type,
    severity = severity,
    lifecycle = lifecycle,
    evidenceSummary = when (lifecycle) {
        com.example.motorcycleantitheftsensor.protection.IncidentLifecycle.OPEN -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_OPENED).bodyTh
        com.example.motorcycleantitheftsensor.protection.IncidentLifecycle.INTERRUPTED -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
            com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_ESCALATED,
            com.example.motorcycleantitheftsensor.protection.GuidanceDetail.IncidentTypeValue(type),
        ).bodyTh
        com.example.motorcycleantitheftsensor.protection.IncidentLifecycle.CLOSED -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_CLOSED).bodyTh
        else -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_UPDATED).bodyTh
    },
    updatedAtMs = updatedAtMs,
    deliveryState = deliveryState,
)

private fun ProtectionSnapshot.armingSecondsRemaining(nowMs: Long): Int? {
    if (state != ProtectionState.ARMING) return null
    return ceil((lastTransitionAtMs + ARMING_DURATION_MS - nowMs).toDouble() / MILLIS_PER_SECOND)
        .toInt()
        .coerceIn(0, 10)
}

private const val ARMING_DURATION_MS = 10_000L
private const val MILLIS_PER_SECOND = 1_000.0

internal fun AudioTelemetry.toAudioUiTelemetry(
    elapsedNowMs: Long,
): AudioUiTelemetry {
    val sampleAgeMs = lastSampleAtMs?.let { elapsedNowMs - it }
    val sampleAgeSeconds = sampleAgeMs
        ?.takeIf { it >= 0L }
        ?.div(1_000L)

    val candidateAgeMs = currentCandidate?.let { elapsedNowMs - it.lastDetectedElapsedMs }
    val candidateIsFresh = candidateAgeMs != null &&
        candidateAgeMs in 0L..AUDIO_CORRELATION_WINDOW_MS
    val expiresInSeconds = candidateAgeMs
        ?.takeIf { candidateIsFresh }
        ?.let { age -> ceil((AUDIO_CORRELATION_WINDOW_MS - age) / 1_000.0).toInt() }

    return AudioUiTelemetry(
        state = state,
        detailCode = detailCode?.substringBefore(':')?.trim()?.take(64),
        modelReady = modelReady,
        lastSampleAgeSeconds = sampleAgeSeconds,
        approximateLevelDbfs = approximateLevelDbfs?.takeIf(Double::isFinite),
        baselineMedianDbfs = baselineMedianDbfs?.takeIf(Double::isFinite),
        baselineP95Dbfs = baselineP95Dbfs?.takeIf(Double::isFinite),
        gateState = gateState,
        lastInferenceMs = lastInferenceMs,
        averageInferenceMs = averageInferenceMs,
        droppedFrames = droppedFrames,
        restartCount = restartCount,
        currentCandidate = currentCandidate?.takeIf { candidateIsFresh },
        candidateExpiresInSeconds = expiresInSeconds,
    )
}

internal fun microphoneHealthText(health: SensorHealth?): String {
    if (health == null) return "Microphone status unknown"
    return when (health.state) {
        SensorHealthState.AVAILABLE, SensorHealthState.HEALTHY -> "Microphone detected"
        SensorHealthState.UNAVAILABLE -> "Microphone unavailable"
        SensorHealthState.STALE -> "Microphone data stale"
        SensorHealthState.FAILED -> "Microphone failed"
    }
}
