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
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionProfilePolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.SecurityIncident
import com.example.motorcycleantitheftsensor.protection.PowerWitnessCommissioningPolicy
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
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

/**
 * What this device can do with one sensor source, projected from the hardware
 * descriptor by [SensorAvailabilityPolicy]. [vendor] and [powerMa] are shown only as
 * supporting detail; the badge is the decision.
 */
data class SensorAvailabilityUiModel(
    val source: SensorSource,
    val availability: SensorAvailability,
    val vendor: String? = null,
    val powerMa: Float? = null,
)

/** Counts for the "มี N · จำกัด N · ไม่มี N" summary line. */
data class SensorInventorySummary(
    val available: Int = 0,
    val limited: Int = 0,
    val missing: Int = 0,
) {
    val known: Boolean get() = available + limited + missing > 0
}

fun Map<SensorSource, SensorAvailabilityUiModel>.inventorySummary(): SensorInventorySummary =
    SensorInventorySummary(
        available = values.count { it.availability == SensorAvailability.AVAILABLE },
        limited = values.count { it.availability == SensorAvailability.LIMITED },
        missing = values.count { it.availability == SensorAvailability.MISSING },
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
    val entryAngleDegrees: Int? = null,
    val entryRequiresControlledRearm: Boolean = false,
    val commissioning: EntryCommissioningUiState? = null,
    val powerCommissioning: PowerCommissioningUiState? = null,
    /** True while the owner-confirmed Power witness placement remains valid for the next Arm. */
    val powerWitnessPlacementConfirmed: Boolean = false,
    /** Two independent POWER signal rows; null for non-POWER profiles. */
    val powerSummary: PowerSummaryRows? = null,
)

/**
 * What the advanced sensor screen may edit under the selected profile.
 *
 * Derived once from [ProtectionProfilePolicy] so Compose never decides for itself which
 * sensors a profile uses ("readiness is never inferred in Compose"). A profile that
 * locks nothing yields the default instance, which reads as fully editable.
 */
data class SensorEditabilityUiModel(
    val profile: ProtectionProfile? = null,
    val lockedSources: Set<SensorSource> = emptySet(),
    val lockedCapabilities: Set<SensorCapability> = emptySet(),
    val presetSelectable: Boolean = true,
    val notice: String? = null,
    val rowReason: String? = null,
    val presetNotice: String? = null,
) {
    val anyLocked: Boolean get() = lockedSources.isNotEmpty()

    fun isLocked(source: SensorSource): Boolean = source in lockedSources

    fun isLocked(capability: SensorCapability): Boolean = capability in lockedCapabilities

    /** Sources the selected profile still detects with, in declaration order. */
    val editableSources: List<SensorSource>
        get() = SensorSource.entries.filterNot(::isLocked)

    val lockedSourceList: List<SensorSource>
        get() = SensorSource.entries.filter(::isLocked)

    companion object {
        fun from(profile: ProtectionProfile?): SensorEditabilityUiModel {
            if (profile == null) return SensorEditabilityUiModel()
            val presentation = PresentationTextCatalog.sensorLock(profile)
            return SensorEditabilityUiModel(
                profile = profile,
                lockedSources = ProtectionProfilePolicy.lockedSources(profile),
                lockedCapabilities = ProtectionProfilePolicy.lockedCapabilities(profile),
                presetSelectable = ProtectionProfilePolicy.presetSelectable(profile),
                notice = presentation.notice,
                rowReason = presentation.reason,
                presetNotice = presentation.presetNotice,
            )
        }
    }
}

/** Guided two-cycle commissioning progress for the เข็มทิศประตู flow (spec section 9). */
enum class EntryCommissioningPhase {
    STILL_CHECK,
    CYCLE_ONE,
    CYCLE_TWO,
    COMMISSIONED,
    FAILED,
}

data class EntryCommissioningUiState(
    val phase: EntryCommissioningPhase,
    val liveAngleDeg: Double = 0.0,
    val selectedAngleDeg: Int = 15,
    val failureReason: String? = null,
)

/** Guided lamp off/on witness commissioning progress for Power Guard (spec 4.3). */
enum class PowerCommissioningPhase {
    DARK_WINDOW,
    LIT_WINDOW,
    COMMISSIONED,
    FAILED,
}

data class PowerCommissioningUiState(
    val phase: PowerCommissioningPhase,
    val liveLux: Double? = null,
    val failureReason: String? = null,
)

/** Stable identifiers for why a Power Guard calibration ended without a witness model. */
object PowerCommissioningFailure {
    const val NO_LIGHT_SENSOR = "no-light-sensor"
    const val NO_LIGHT_SAMPLES = "no-light-samples"
    const val SAVE_FAILED = "save-failed"
}

/**
 * Thai explanation for a failed calibration. The copy follows the reason: telling an
 * owner whose phone has no light sensor to inspect the lamp hood sends them to repair
 * the wrong thing.
 */
internal fun powerCommissioningFailureText(reason: String?): String = when (reason) {
    PowerCommissioningFailure.NO_LIGHT_SENSOR ->
        "เครื่องนี้ไม่มีเซนเซอร์แสง — โหมดไฟเลี้ยงต้องใช้ไฟยืนยัน จึงใช้งานไม่ได้"
    PowerCommissioningFailure.NO_LIGHT_SAMPLES ->
        "ไม่ได้รับค่าแสงจากเซนเซอร์ — ลองรีสตาร์ทเครื่องแล้วปรับเทียบใหม่"
    PowerCommissioningFailure.SAVE_FAILED ->
        "บันทึกค่าปรับเทียบไม่สำเร็จ ลองใหม่อีกครั้ง"
    PowerWitnessCommissioningPolicy.REJECTION_NOT_SEPARATED ->
        "ช่วงแสงไม่แยกกันพอ — ตรวจสอบฝาครอบแล้วเริ่มใหม่"
    else -> "ปรับเทียบไม่สำเร็จ กรุณาลองใหม่"
}

/**
 * Thai sensor-row text while protection is off. A sensor the phone does not have will
 * never start reading, so promising that it will is a lie the owner cannot check.
 */
internal fun idleSensorRowText(kind: SensorKind, health: SensorHealth?): String = when {
    health == null -> IDLE_SENSOR_WAITING_TEXT
    kind == SensorKind.LIGHT && health.lightDetail?.hardwareSupported == false ->
        "ไม่พบเซนเซอร์แสงบนเครื่องนี้"
    kind == SensorKind.VIBRATION && health.vibrationDetail?.hardwareAvailable == false ->
        "ไม่พบเซนเซอร์ความเคลื่อนไหวบนเครื่องนี้"
    else -> IDLE_SENSOR_WAITING_TEXT
}

private const val IDLE_SENSOR_WAITING_TEXT = "จะเริ่มอ่านค่าหลังเปิดการป้องกัน"

/**
 * Independent POWER signal rows (spec sections 3.6/5): neither row alone may claim an
 * outage; each carries its own state so the owner reads them separately.
 */
enum class ChargingRowState { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class WitnessRowState { AVAILABLE, DETECTED, DARK, AMBIGUOUS, UNAVAILABLE }

data class PowerSummaryRows(
    val charging: ChargingRowState = ChargingRowState.UNKNOWN,
    val witness: WitnessRowState = WitnessRowState.UNAVAILABLE,
    val lastUpdatedAtMs: Long? = null,
    val confirmedFault: Boolean = false,
    val lastLux: Double? = null,
    val requiresWitnessPlacementRevalidation: Boolean = false,
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
    /**
     * Hardware inventory of this device, read once from the sensor catalog. Empty only
     * before the catalog is available; the screen must not read empty as "all missing".
     */
    val sensorAvailability: Map<SensorSource, SensorAvailabilityUiModel> = emptyMap(),
) {
    /**
     * Derived from [profile], never stored: the view model assembles this state in two
     * steps and copies the selected profile in after the fact, so a stored field would
     * silently keep reporting "nothing is locked".
     */
    val sensorEditability: SensorEditabilityUiModel
        get() = SensorEditabilityUiModel.from(profile.selectedProfile)

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
            sensorAvailability: Map<SensorSource, SensorAvailabilityUiModel> = emptyMap(),
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
            sensorAvailability = sensorAvailability,
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
        // Level 1: Alert active — highest priority
        state == com.example.motorcycleantitheftsensor.protection.ProtectionState.ALERT_ACTIVE ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.ALERT_ACTIVE)
        // Level 2: Service offline
        !serviceRunning || state == com.example.motorcycleantitheftsensor.protection.ProtectionState.OFFLINE ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.OFFLINE)
        // Level 3: Setup required
        state == com.example.motorcycleantitheftsensor.protection.ProtectionState.SETUP_REQUIRED ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.SETUP_REQUIRED)
        // Level 4: Telegram unreachable
        !telegramReachable && telegramPolling ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.TELEGRAM_UNREACHABLE)
        // Level 5: Permission blockers
        permissionBlockers.isNotEmpty() ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
                com.example.motorcycleantitheftsensor.protection.GuidanceCode.NOTIFICATION_PERMISSION_MISSING
            )
        // Level 6: Degraded operation
        degradationReasons.isNotEmpty() ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.ARMED_DEGRADED)
        // Level 7: Delivery failed
        lastDeliveryState == com.example.motorcycleantitheftsensor.protection.DeliveryState.FAILED ->
            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.TELEGRAM_DELIVERY_FAILED)
        // Healthy or disarmed — no persistent card
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
        com.example.motorcycleantitheftsensor.protection.IncidentLifecycle.CLOSED -> com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
            com.example.motorcycleantitheftsensor.protection.GuidanceCode.INCIDENT_CLOSED,
            com.example.motorcycleantitheftsensor.protection.GuidanceDetail.IncidentTypeValue(type),
        ).bodyTh
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
    if (health == null) return "สถานะไมโครโฟนไม่ทราบ"
    return when (health.state) {
        SensorHealthState.AVAILABLE, SensorHealthState.HEALTHY -> "ตรวจพบไมโครโฟน"
        SensorHealthState.UNAVAILABLE -> "ไมโครโฟนไม่พร้อมใช้งาน"
        SensorHealthState.STALE -> "ข้อมูลไมโครโฟนไม่ใหม่"
        SensorHealthState.FAILED -> "ไมโครโฟนทำงานผิดพลาด"
    }
}

/**
 * Typed Thai presentation labels for the Protection screen diagnostics layer
 * (profile-aware Thai UX, Task 4). Every mapping is an exhaustive `when` over the
 * domain enum; display code never calls `.name`, `.lowercase()`, or string replacement.
 */
internal fun sensorKindLabel(kind: SensorKind): String = when (kind) {
    SensorKind.VIBRATION -> "การสั่นสะเทือน"
    SensorKind.LIGHT -> "แสงบริเวณจุดติดตั้ง"
    SensorKind.POWER_THERMAL -> "ไฟและอุณหภูมิ"
    SensorKind.MICROPHONE -> "ไมโครโฟน"
    SensorKind.LOCATION -> "ตำแหน่ง"
}

internal fun sensorHealthStateLabel(state: SensorHealthState): String = when (state) {
    SensorHealthState.HEALTHY -> "ทำงานปกติ"
    SensorHealthState.AVAILABLE -> "พร้อมใช้งาน"
    SensorHealthState.UNAVAILABLE -> "ไม่พร้อมใช้งาน"
    SensorHealthState.STALE -> "ข้อมูลล่าช้า"
    SensorHealthState.FAILED -> "ขัดข้อง"
}

internal fun audioRuntimeStateLabel(state: AudioRuntimeState): String = when (state) {
    AudioRuntimeState.OFF -> "ปิดอยู่"
    AudioRuntimeState.STARTING -> "กำลังเริ่มต้น"
    AudioRuntimeState.CALIBRATING -> "กำลังปรับเทียบ"
    AudioRuntimeState.LISTENING -> "กำลังฟังเสียง"
    AudioRuntimeState.CLASSIFYING -> "กำลังวิเคราะห์เสียง"
    AudioRuntimeState.DEGRADED -> "ทำงานแบบจำกัด"
    AudioRuntimeState.FAILED -> "ขัดข้อง"
}

internal fun audioGateStateLabel(state: AudioGateState): String = when (state) {
    AudioGateState.DISABLED -> "ปิดใช้งาน"
    AudioGateState.QUIET -> "เสียงเงียบ"
    AudioGateState.OPEN -> "พบเสียงผิดปกติ"
}

internal fun audioThreatCategoryLabel(category: com.example.motorcycleantitheftsensor.protection.AudioThreatCategory): String =
    when (category) {
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.IMPACT -> "แรงกระแทก"
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.BREAKING -> "เสียงแตกหัก"
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.POWER_TOOL -> "เครื่องมือไฟฟ้า"
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.METAL_TAMPER -> "เสียงงัดโลหะ"
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.ENGINE_START -> "เครื่องยนต์สตาร์ท"
        com.example.motorcycleantitheftsensor.protection.AudioThreatCategory.ENGINE_RUNNING -> "เครื่องยนต์กำลังทำงาน"
    }

internal fun incidentLifecycleLabel(lifecycle: IncidentLifecycle): String =
    PresentationTextCatalog.incidentLifecycleLabel(lifecycle)

internal fun deliveryStateLabel(state: DeliveryState): String =
    PresentationTextCatalog.deliveryStateLabel(state)
