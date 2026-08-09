package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentSource
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
    val authenticatorConfigured: Boolean,
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

data class ProtectionUiMessage(val id: Long, val text: String, val isError: Boolean)

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
)

data class IncidentRowSummary(
    val id: String,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val updatedAtMs: Long,
    val deliveryState: DeliveryState,
)

data class SettingsOperationResult(val applied: Boolean, val message: String)

data class AuthenticatorSetupDetails(val secret: String, val uri: String)

interface ProtectionSettingsGateway {
    suspend fun read(missingPermissions: Set<String>): ProtectionSettingsSummary
    fun saveSensitivity(level: Int)
    suspend fun replaceBotToken(token: String): SettingsOperationResult
    suspend fun resetPairing(): SettingsOperationResult
    fun saveSmsFallback(destination: String, aesKey: String): SettingsOperationResult
    suspend fun beginAuthenticatorSetup(): AuthenticatorSetupDetails?
    fun cancelAuthenticatorSetup()
    suspend fun verifyAuthenticator(code: String): Boolean
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
        ): ProtectionUiState = ProtectionUiState(
            destination = destination,
            protection = snapshot.toStatusUiState(),
            events = incidents
                .asSequence()
                .filter { it.source == IncidentSource.REAL }
                .sortedByDescending { it.updatedAtMs }
                .map { it.toEventRow() }
                .toList(),
            settings = settings,
            armingSecondsRemaining = snapshot.armingSecondsRemaining(nowMs),
            eventsLoading = eventsLoading,
            eventsError = eventsError,
            operationInFlight = operationInFlight,
            message = message,
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
)

private fun SecurityIncident.toEventRow(): ProtectionEventRow = ProtectionEventRow(
    id = id,
    type = type,
    severity = severity,
    lifecycle = lifecycle,
    evidenceSummary = evidence.firstOrNull()?.let { item ->
        listOfNotNull(item.kind.name, item.diagnostic?.takeIf(String::isNotBlank))
            .joinToString(": ")
    } ?: "No sensor evidence",
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
