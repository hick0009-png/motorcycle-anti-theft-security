package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.GuidanceContent
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorKind
import kotlin.math.ceil

enum class ProtectionDestination { PROTECTION, EVENTS, SETTINGS }

data class ProtectionSettingsSummary(
    val tokenConfigured: Boolean,
    val pairedOwnerCount: Int,
    val pairingCode: String?,
    val sensitivity: Int,
    val smsFallbackConfigured: Boolean,
    val missingPermissions: Set<String>,
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
        com.example.motorcycleantitheftsensor.protection.IncidentLifecycle.INTERRUPTED -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_ESCALATED).bodyTh.replace("{incidentType}", type.name)
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
