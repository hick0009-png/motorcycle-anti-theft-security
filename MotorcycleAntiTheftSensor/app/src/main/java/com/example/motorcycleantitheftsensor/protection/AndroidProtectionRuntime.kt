package com.example.motorcycleantitheftsensor.protection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.security.ProtectionPermissionPolicy
import com.example.motorcycleantitheftsensor.sensor.AudioPeakDetector
import com.example.motorcycleantitheftsensor.sensor.LightIntrusionDetector
import com.example.motorcycleantitheftsensor.sensor.LocationObservationProvider
import com.example.motorcycleantitheftsensor.sensor.PowerThermalMonitor
import com.example.motorcycleantitheftsensor.sensor.VibrationDetector

import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatCandidateBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.example.motorcycleantitheftsensor.sensor.DefaultSensorCapabilityController
import com.example.motorcycleantitheftsensor.sensor.SensorCapabilityController
import com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult
import com.example.motorcycleantitheftsensor.sensor.SensorHandlerOwner

/** Diagnostics prefix marking observations synthesized from Entry Guard verdicts. */
private val ENTRY_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.ENTRY_PREFIX

/** Diagnostics prefix marking observations synthesized from Power Guard arbiter verdicts. */
private val POWER_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.POWER_PREFIX

private val POWER_CHARGING_HEALTH_DIAGNOSTIC = ProtectionDiagnostics.POWER_CHARGING_HEALTH
private val POWER_WITNESS_DARK_DIAGNOSTIC = ProtectionDiagnostics.POWER_WITNESS_DARK
private val POWER_CONFIRMED_LOSS_DIAGNOSTIC = ProtectionDiagnostics.POWER_CONFIRMED_LOSS
private val POWER_RECOVERED_DIAGNOSTIC = ProtectionDiagnostics.POWER_RECOVERED

internal fun powerConfirmationDelayMs(deadlineMs: Long?, nowMs: Long): Long? =
    deadlineMs?.minus(nowMs)?.takeIf { it > 0L }

internal fun powerWitnessIsFreshForGeneration(
    witnessLux: Double?,
    witnessGeneration: Long?,
    currentGeneration: Long,
): Boolean = witnessLux != null && witnessGeneration == currentGeneration

internal data class CachedPowerWitness(
    val lux: Double,
    val generation: Long,
)

/** Re-emit cadence for the commissioning stream; must stay under `maxSampleGapMs`. */
internal const val POWER_COMMISSIONING_REPEAT_INTERVAL_MS = 1_000L

/**
 * `Sensor.TYPE_LIGHT` is an on-change source: a genuinely stable reading produces no
 * further callbacks, which is exactly the condition witness commissioning measures.
 * Without a steady re-emit the guided window's continuity budget lapses and the window
 * restarts forever.
 *
 * Re-emitting the cached reading is honest rather than synthetic: the on-change
 * contract states the value is unchanged since the last callback, so the cached lux is
 * the current lux. It is therefore returned verbatim — never smoothed, aged, or
 * interpolated — and only while the dedicated listener is registered inside the current
 * continuity, so a value can never cross a listener gap or precede the first real read.
 */
internal fun powerCommissioningRepeatSample(
    streamActive: Boolean,
    listenerRegistered: Boolean,
    cached: CachedPowerWitness?,
    nowElapsedMs: Long,
): PowerWitnessSample? {
    if (!streamActive || !listenerRegistered) return null
    val reading = cached ?: return null
    return PowerWitnessSample(lux = reading.lux, timestampMs = nowElapsedMs, fresh = true)
}

internal class PowerWitnessContinuityCache {
    @Volatile
    private var cached: CachedPowerWitness? = null

    fun record(lux: Double, generation: Long): CachedPowerWitness =
        CachedPowerWitness(lux = lux, generation = generation).also { cached = it }

    fun latest(): CachedPowerWitness? = cached

    fun endListenerContinuity() {
        cached = null
    }
}

/**
 * Keeps the most recent witness reading visible when the armed detector set stops but
 * the presentation-only Power Guard listener remains registered. The device light
 * sensor is on-change, so waiting for another callback would incorrectly clear it.
 */
internal fun powerLightHealthAfterDetectorStop(
    hasLightSensor: Boolean,
    powerStatusMonitoringActive: Boolean,
    lastWitnessLux: Double?,
): SensorHealth {
    val retainWitness = hasLightSensor && powerStatusMonitoringActive && lastWitnessLux != null
    return SensorHealth(
        state = when {
            retainWitness -> SensorHealthState.HEALTHY
            hasLightSensor -> SensorHealthState.AVAILABLE
            else -> SensorHealthState.UNAVAILABLE
        },
        lightDetail = LightHealthDetail(
            hardwareSupported = hasLightSensor,
            isRegistered = powerStatusMonitoringActive,
            lastLux = lastWitnessLux.takeIf { retainWitness },
        ),
    )
}

interface AndroidDetectorSet {
    val audioTelemetry: StateFlow<AudioTelemetry> get() = MutableStateFlow(AudioTelemetry.off())
    val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>> get() = MutableStateFlow(emptyMap())

    fun start(): DetectorStartResult

    fun start(armedSessionId: String): DetectorStartResult = start()

    /**
     * [usedSensorKinds] carries the kinds the armed profile actually detects with. The
     * microphone and location are not [SensorSource]s, so the fusion configuration
     * cannot switch them off; only this set can.
     */
    fun start(armedSessionId: String, usedSensorKinds: Set<SensorKind>): DetectorStartResult =
        start(armedSessionId)

    fun stop()

    fun applySensitivity(level: Int)

    fun freezeAudioAdaptation(nowElapsedMs: Long) {}

    fun currentSensorHealth(): Map<SensorKind, SensorHealth>

    fun currentLocationObservation(): SensorObservation? = null

    fun currentIncidentLocation(): IncidentLocation? = null

    fun applySensorConfiguration(config: SensorFusionConfiguration): SensorConfigurationApplyResult =
        SensorConfigurationApplyResult(
            status = SensorConfigurationApplyResult.Status.APPLIED,
            affectedCapabilities = emptySet(),
        )

    fun effectiveSensorConfiguration(): SensorFusionConfiguration =
        SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)

    fun currentGenerationId(): Long = 0L

    fun sourceHealth(source: SensorSource): SensorHealthState =
        currentSensorHealth()[if (source.capability == SensorCapability.LIGHT) SensorKind.LIGHT else SensorKind.VIBRATION]?.state
            ?: SensorHealthState.UNAVAILABLE

    /** Entry Guard armed-session hook; default no-op for non-Entry detector sets. */
    fun beginEntrySession(sessionId: String, model: EntryHingeModel, settings: EntryProfileSettings) {
    }

    /** Clears the armed-session Entry baseline and stops its orientation listener. */
    fun clearEntryBaseline() {
    }

    /** Registers the rotation source for the guided commissioning flow. */
    fun startEntryCommissioningStream() {
    }

    /** Stops the commissioning orientation stream. */
    fun stopEntryCommissioningStream() {
    }

    /** Live orientation samples while a commissioning stream is active. */
    fun entryOrientationSamples(): Flow<EntryOrientationSample> = emptyFlow()

    /** Power Guard armed-session hook; default no-op for non-POWER detector sets. */
    fun beginPowerSession(sessionId: String, model: PowerWitnessModel, settings: PowerProfileSettings) {
    }

    /** Clears the armed-session Power arbiter. */
    fun clearPowerSession() {
    }

    /** Starts presentation-only Power Guard status monitoring while the profile is visible. */
    fun startPowerStatusMonitoring() {
    }

    /** Stops presentation-only Power Guard status monitoring when the profile is hidden. */
    fun stopPowerStatusMonitoring() {
    }

    /**
     * Registers the ambient-light witness source for the guided commissioning flow.
     *
     * @return true when the source was acquired.
     */
    fun startPowerCommissioningStream(): Boolean = false

    /** Stops the commissioning witness stream. */
    fun stopPowerCommissioningStream() {
    }

    /** Live witness-light samples while a commissioning stream is active. */
    fun powerWitnessSamples(): Flow<PowerWitnessSample> = emptyFlow()
}

data class IncidentObservationBatch(
    val primary: SensorObservation,
    val supplementalEvidence: List<SensorObservation> = emptyList(),
    val location: IncidentLocation? = null,
)

class AndroidProtectionRuntime(
    private val readinessProvider: () -> ReadinessReport,
    detectorFactory: ((SensorObservation) -> Unit) -> AndroidDetectorSet,
    private val observationProcessor: SensorObservationProcessor,
    private val elapsedClock: ProtectionClock,
    private val stateProvider: () -> ProtectionState,
    private val sensorSampleRecorder: (SensorKind, Long, String?, Double) -> Unit,
    private val incidentConsumer: (IncidentObservationBatch) -> Unit,
) : ProtectionRuntime {
    private val detectors = detectorFactory(::handleObservation)
    @Volatile
    private var powerSessionActive = false

    override val audioTelemetry: StateFlow<AudioTelemetry>
        get() = detectors.audioTelemetry

    override val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>>
        get() = detectors.sensorHealth

    override fun readiness(): ReadinessReport = readinessProvider()

    override fun startDetectors(): DetectorStartResult {
        observationProcessor.resetSession()
        return detectors.start()
    }

    override fun startDetectors(armedSessionId: String): DetectorStartResult {
        observationProcessor.resetSession()
        return detectors.start(armedSessionId)
    }

    /**
     * Frozen-configuration entry point. The default body in [ProtectionRuntime] drops
     * the configuration, so the armed snapshot's promise that late Settings edits cannot
     * change running detectors was never kept, and a profile that switches sensors off
     * kept registering them. Apply the frozen configuration before starting anything.
     */
    override fun startDetectors(
        armedSessionId: String,
        configuration: SensorFusionConfiguration,
        usedSensorKinds: Set<SensorKind>,
    ): DetectorStartResult {
        observationProcessor.resetSession()
        detectors.applySensorConfiguration(configuration)
        return detectors.start(armedSessionId, usedSensorKinds)
    }

    override fun stopDetectors() {
        detectors.stop()
    }

    override fun applySensitivity(level: Int) {
        detectors.applySensitivity(level)
        observationProcessor.setVibrationSensitivity(level)
    }

    override fun applySensorConfiguration(config: SensorFusionConfiguration): SensorConfigurationApplyResult {
        val result = detectors.applySensorConfiguration(config)
        observationProcessor.setVibrationSensitivity(config.capability(SensorCapability.MOVEMENT).sensitivity)
        return result
    }

    override fun effectiveSensorConfiguration(): SensorFusionConfiguration =
        detectors.effectiveSensorConfiguration()

    override fun currentGenerationId(): Long = detectors.currentGenerationId()

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = detectors.currentSensorHealth()

    override fun sourceHealth(source: SensorSource): SensorHealthState = detectors.sourceHealth(source)

    override fun beginEntrySession(sessionId: String, model: EntryHingeModel, settings: EntryProfileSettings) {
        detectors.beginEntrySession(sessionId, model, settings)
    }

    override fun clearEntryBaseline() {
        detectors.clearEntryBaseline()
    }

    override fun startEntryCommissioningStream() {
        detectors.startEntryCommissioningStream()
    }

    override fun stopEntryCommissioningStream() {
        detectors.stopEntryCommissioningStream()
    }

    override fun entryOrientationSamples(): Flow<EntryOrientationSample> =
        detectors.entryOrientationSamples()

    override fun beginPowerSession(sessionId: String, model: PowerWitnessModel, settings: PowerProfileSettings) {
        powerSessionActive = true
        try {
            detectors.beginPowerSession(sessionId, model, settings)
        } catch (error: Throwable) {
            powerSessionActive = false
            throw error
        }
    }

    override fun clearPowerSession() {
        powerSessionActive = false
        detectors.clearPowerSession()
    }

    override fun startPowerStatusMonitoring() {
        detectors.startPowerStatusMonitoring()
    }

    override fun stopPowerStatusMonitoring() {
        detectors.stopPowerStatusMonitoring()
    }

    override fun startPowerCommissioningStream(): Boolean =
        detectors.startPowerCommissioningStream()

    override fun stopPowerCommissioningStream() {
        detectors.stopPowerCommissioningStream()
    }

    override fun powerWitnessSamples(): Flow<PowerWitnessSample> =
        detectors.powerWitnessSamples()

    private fun handleObservation(observation: SensorObservation) {
        val nowElapsedMs = elapsedClock.nowMs()
        if (
            powerSessionActive &&
            observation.kind == SensorKind.POWER_THERMAL &&
            observation.diagnostic == ProtectionDiagnostics.CHARGER_DISCONNECTED
        ) {
            // The physical cable callback remains health telemetry in POWER mode.
            // Only a stable verdict from PowerCompositeArbiter may own an incident.
            return
        }
        if (observation.diagnostic?.startsWith(ENTRY_DIAGNOSTIC_PREFIX) == true) {
            // Entry verdicts are fully evaluated by the armed-session policy upstream;
            // deliver them straight through the existing incident pipeline without
            // vibration debouncing. No second delivery owner is introduced.
            incidentConsumer(IncidentObservationBatch(primary = observation))
            return
        }
        if (observation.diagnostic?.startsWith(POWER_DIAGNOSTIC_PREFIX) == true) {
            // Power verdicts are fully evaluated by the armed-session arbiter upstream;
            // deliver them straight through the existing incident pipeline without
            // vibration debouncing. No second delivery owner is introduced.
            incidentConsumer(IncidentObservationBatch(primary = observation))
            return
        }
        if (!observationProcessor.isUsable(observation, nowElapsedMs)) return
        sensorSampleRecorder(
            observation.kind,
            observation.wallClockMs,
            observation.diagnostic,
            observation.normalizedValue,
        )
        if (observation.diagnostic == "battery_level_percent") return
        when (
            val decision = observationProcessor.accept(
                observation = observation,
                nowElapsedMs = nowElapsedMs,
                arming = stateProvider() == ProtectionState.ARMING,
            )
        ) {
            is ObservationDecision.Accepted -> {
                if (decision.observation.kind == SensorKind.VIBRATION ||
                    (decision.observation.kind == SensorKind.POWER_THERMAL && decision.observation.diagnostic?.contains("charger_disconnect") == true)
                ) {
                    detectors.freezeAudioAdaptation(decision.observation.eventElapsedMs)
                }
                val supplementalEvidence = if (decision.observation.kind == SensorKind.LOCATION) {
                    emptyList()
                } else {
                    runCatching(detectors::currentLocationObservation)
                        .getOrNull()
                        ?.takeIf { location -> observationProcessor.isUsable(location, elapsedClock.nowMs()) }
                        ?.let(::listOf)
                        .orEmpty()
                }
                val incidentLocation = runCatching(detectors::currentIncidentLocation)
                    .getOrNull()
                incidentConsumer(
                    IncidentObservationBatch(
                        primary = decision.observation,
                        supplementalEvidence = supplementalEvidence,
                        location = incidentLocation,
                    ),
                )
            }
            ObservationDecision.BaselineUpdated,
            ObservationDecision.Debounced,
            is ObservationDecision.Rejected,
            -> Unit
        }
    }
}

class AndroidRuntimeReadiness(
    context: Context,
    private val remoteControlReadiness: () -> RemoteControlReadiness,
) {
    private val applicationContext = context.applicationContext
    private val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val packageManager = applicationContext.packageManager

    fun report(): ReadinessReport {
        val blockers = mutableSetOf<String>()
        val degradations = mutableSetOf<String>()
        blockers += remoteControlReadiness().blockers()
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            blockers += "ACCELEROMETER unavailable"
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.FOREGROUND_SERVICE)
        ) {
            blockers += "FOREGROUND_SERVICE"
        }

        val missingPermissions = buildSet {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(ProtectionPermissionPolicy.RECORD_AUDIO)
            }
            if (!hasPermission(Manifest.permission.SEND_SMS)) {
                add(ProtectionPermissionPolicy.SEND_SMS)
            }
            if (
                Build.VERSION.SDK_INT >= 33 &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(ProtectionPermissionPolicy.POST_NOTIFICATIONS)
            }
        }
        val permissionReadiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = missingPermissions,
            sdkInt = Build.VERSION.SDK_INT,
        )
        blockers += permissionReadiness.blockers
        degradations += permissionReadiness.degradations

        if (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) == null) {
            degradations += "LIGHT unavailable"
        }
        degradations += MicrophoneReadiness(
            hardwareAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            permissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
        ).degradations()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)) {
            degradations += "LOCATION unavailable"
        } else if (!hasLocationPermission()) {
            degradations += "LOCATION permission unavailable"
        }
        return ReadinessReport(
            blockers = blockers,
            degradations = degradations,
        )
    }

    private fun hasPermission(permission: String): Boolean = applicationContext.checkSelfPermission(permission) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
}

class PlatformAndroidDetectorSet(
    context: Context,
    private val location: LocationObservationProvider,
    private val onObservation: (SensorObservation) -> Unit,
    audioCandidateBuffer: AudioThreatCandidateBuffer = AudioThreatCandidateBuffer(),
    onAudioCandidatesReset: () -> Unit = {},
    private val handlerOwner: SensorHandlerOwner = SensorHandlerOwner("SensorThread"),
    private val controller: SensorCapabilityController = DefaultSensorCapabilityController(
        sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
        handlerOwner = handlerOwner,
    ),
    resumedPowerSemantic: PowerCompositeArbiter.SemanticState? = null,
) : AndroidDetectorSet {
    private val applicationContext = context.applicationContext
    private val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val packageManager = applicationContext.packageManager
    private val healthLock = Any()
    private val lifecycleLock = Any()
    private val health = mutableMapOf<SensorKind, SensorHealth>()
    private val _sensorHealth = MutableStateFlow<Map<SensorKind, SensorHealth>>(emptyMap())
    override val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>> = _sensorHealth.asStateFlow()
    @Volatile
    private var sensitivityLevel: Int = 5

    private val vibration = VibrationDetector(applicationContext, ::record)
    private val light = LightIntrusionDetector(applicationContext, ::record)
    private val powerThermal = PowerThermalMonitor(
        context = applicationContext,
        onObservation = ::record,
        onStatusChanged = { status ->
            val connected = status.chargingState.chargingConnected
            val previous = lastChargingConnected
            lastChargingConnected = connected
            if (connected != previous && powerSession.isActive) {
                evaluatePowerArbiter(powerWitnessContinuity.latest(), SystemClock.elapsedRealtime())
            }
            publishHealth(
                SensorKind.POWER_THERMAL,
                SensorHealth(
                    state = if (status.sourceAvailable && status.isRegistered) SensorHealthState.HEALTHY else SensorHealthState.UNAVAILABLE,
                    lastSampleAtMs = status.observedAtWallClockMs,
                    powerThermalDetail = PowerThermalHealthDetail(
                        sourceAvailable = status.sourceAvailable,
                        isRegistered = status.isRegistered,
                        chargingState = status.chargingState,
                        batteryLevelPercent = status.batteryLevelPercent,
                        temperatureCelsius = status.temperatureCelsius,
                        lastUpdateWallClockMs = status.observedAtWallClockMs,
                    ),
                ),
            )
        },
    )
    private val audio = AudioPeakDetector(
        context = applicationContext,
        onObservation = ::record,
        onHealthFailure = { detail ->
            publishHealth(
                SensorKind.MICROPHONE,
                SensorHealth(
                    state = SensorHealthState.FAILED,
                    detail = detail,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = AudioRuntimeState.OFF,
                        isRegistered = false,
                        modelReady = false,
                        failureReason = detail,
                    ),
                ),
            )
        },
        candidateBuffer = audioCandidateBuffer,
        onCandidatesReset = onAudioCandidatesReset,
        onTelemetryChanged = { telemetry ->
            val micHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            val micPerm = hasAudioPermission()
            val state = when {
                !micHardware || !micPerm -> SensorHealthState.UNAVAILABLE
                telemetry.state == AudioRuntimeState.LISTENING -> SensorHealthState.HEALTHY
                telemetry.state == AudioRuntimeState.CALIBRATING || telemetry.state == AudioRuntimeState.STARTING -> SensorHealthState.AVAILABLE
                telemetry.state == AudioRuntimeState.OFF -> SensorHealthState.AVAILABLE
                else -> SensorHealthState.FAILED
            }
            val existingMic = synchronized(healthLock) { health[SensorKind.MICROPHONE]?.microphoneDetail }
            val lastElapsed = if (telemetry.lastSampleAtMs != null) {
                SystemClock.elapsedRealtime()
            } else {
                existingMic?.lastAudioSampleElapsedMs
            }
            val isRunning = running
            publishHealth(
                SensorKind.MICROPHONE,
                SensorHealth(
                    state = state,
                    lastSampleAtMs = telemetry.lastSampleAtMs,
                    detail = telemetry.detailCode,
                    microphoneDetail = MicrophoneHealthDetail(
                        audioState = telemetry.state,
                        isRegistered = isRunning && (telemetry.state == AudioRuntimeState.LISTENING || telemetry.state == AudioRuntimeState.CALIBRATING || telemetry.state == AudioRuntimeState.STARTING),
                        modelReady = telemetry.modelReady,
                        lastAudioSampleElapsedMs = lastElapsed,
                        lastAudioSampleAtMs = telemetry.lastSampleAtMs,
                        hardwareAvailable = micHardware,
                        permissionGranted = micPerm,
                        failureReason = telemetry.detailCode,
                    ),
                ),
            )
        },
    )
    @Volatile
    private var running = false

    /** Armed-session Entry Guard state; inactive unless an Entry session begins. */
    private val entrySession = EntryArmedSessionController()
    private var entryOrientationListener: android.hardware.SensorEventListener? = null
    private val entrySampleFlow = MutableSharedFlow<EntryOrientationSample>(extraBufferCapacity = 64)

    /** Armed-session Power Guard state; inactive unless a Power session begins. */
    private val powerSession = PowerArmedSessionController()
    private var pendingResumedPowerSemantic = resumedPowerSemantic
    private val powerSampleFlow = MutableSharedFlow<PowerWitnessSample>(extraBufferCapacity = 64)
    private var powerCommissioningStreamActive = false
    private var powerStatusMonitoringActive = false
    private var powerLightListener: android.hardware.SensorEventListener? = null
    private val powerConfirmationRunnable = Runnable {
        if (powerSession.isActive) {
            evaluatePowerArbiter(powerWitnessContinuity.latest(), SystemClock.elapsedRealtime())
        }
    }
    private val powerCommissioningRepeatRunnable = object : Runnable {
        override fun run() {
            if (!powerCommissioningStreamActive) return
            powerCommissioningRepeatSample(
                streamActive = powerCommissioningStreamActive,
                listenerRegistered = powerLightListener != null,
                cached = powerWitnessContinuity.latest(),
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )?.let(powerSampleFlow::tryEmit)
            handlerOwner.handler.postDelayed(this, POWER_COMMISSIONING_REPEAT_INTERVAL_MS)
        }
    }

    /** Latest typed charging observation feeding the armed-session arbiter. */
    @Volatile
    private var lastChargingConnected: Boolean? = null

    /** Latest witness evidence inside the current dedicated-listener continuity. */
    private val powerWitnessContinuity = PowerWitnessContinuityCache()

    init {
        val hasAcc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        health[SensorKind.VIBRATION] = SensorHealth(
            state = if (hasAcc) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
            vibrationDetail = VibrationHealthDetail(
                hardwareAvailable = hasAcc,
                isRegistered = false,
                failureReason = if (!hasAcc) "hardware unavailable" else null,
            ),
        )
        val hasLight = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        health[SensorKind.LIGHT] = SensorHealth(
            state = if (hasLight) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
            lightDetail = LightHealthDetail(
                hardwareSupported = hasLight,
                isRegistered = false,
                failureReason = if (!hasLight) "hardware unavailable" else null,
            ),
        )
        val initialPower = PowerThermalMonitor.queryInitialStatus(applicationContext)
        lastChargingConnected = initialPower.chargingState.chargingConnected
        health[SensorKind.POWER_THERMAL] = SensorHealth(
            state = if (initialPower.sourceAvailable) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
            powerThermalDetail = PowerThermalHealthDetail(
                sourceAvailable = initialPower.sourceAvailable,
                isRegistered = false,
                chargingState = initialPower.chargingState,
                batteryLevelPercent = initialPower.batteryLevelPercent,
                temperatureCelsius = initialPower.temperatureCelsius,
                lastUpdateWallClockMs = initialPower.observedAtWallClockMs,
            ),
        )
        val microphoneHardwareAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val microphonePermissionGranted = hasAudioPermission()
        health[SensorKind.MICROPHONE] = SensorHealth(
            state = if (microphoneHardwareAvailable && microphonePermissionGranted) {
                SensorHealthState.AVAILABLE
            } else {
                SensorHealthState.UNAVAILABLE
            },
            detail = when {
                !microphoneHardwareAvailable -> "hardware unavailable"
                !microphonePermissionGranted -> "permission unavailable"
                else -> null
            },
            microphoneDetail = MicrophoneHealthDetail(
                audioState = AudioRuntimeState.OFF,
                isRegistered = false,
                modelReady = false,
                hardwareAvailable = microphoneHardwareAvailable,
                permissionGranted = microphonePermissionGranted,
                failureReason = when {
                    !microphoneHardwareAvailable -> "hardware unavailable"
                    !microphonePermissionGranted -> "permission unavailable"
                    else -> null
                },
            ),
        )
        val hasLocationHw = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
        val hasLocationPerm = hasLocationPermission()
        health[SensorKind.LOCATION] = SensorHealth(
            state = if (hasLocationHw && hasLocationPerm) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
            locationDetail = LocationHealthDetail(
                trackingState = LocationTrackingState.STOPPED,
                isRegistered = false,
                hardwareAvailable = hasLocationHw,
                permissionGranted = hasLocationPerm,
                failureCode = when {
                    !hasLocationHw -> LocationFailureCode.HARDWARE_UNAVAILABLE
                    !hasLocationPerm -> LocationFailureCode.PERMISSION_DENIED
                    else -> null
                },
                failureReason = when {
                    !hasLocationHw -> "hardware unavailable"
                    !hasLocationPerm -> "permission unavailable"
                    else -> null
                },
            ),
        )
        _sensorHealth.value = health.toMap()
    }

    override val audioTelemetry: StateFlow<AudioTelemetry>
        get() = audio.telemetry

    override fun start(): DetectorStartResult = start(java.util.UUID.randomUUID().toString())

    override fun start(armedSessionId: String): DetectorStartResult =
        start(armedSessionId, SensorKind.entries.toSet())

    override fun start(
        armedSessionId: String,
        usedSensorKinds: Set<SensorKind>,
    ): DetectorStartResult {
        val initialLocationObs: SensorObservation?
        synchronized(lifecycleLock) {
            if (running) return DetectorStartResult(started = true)
            val hasAcc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            if (!hasAcc) {
                publishHealth(
                    SensorKind.VIBRATION,
                    SensorHealth(
                        state = SensorHealthState.UNAVAILABLE,
                        vibrationDetail = VibrationHealthDetail(
                            hardwareAvailable = false,
                            isRegistered = false,
                            failureReason = "ACCELEROMETER unavailable",
                        ),
                    ),
                )
                return DetectorStartResult(started = false, failureReason = "ACCELEROMETER unavailable")
            }
            try {
                val controllerResult = controller.start(controller.getEffectiveConfiguration()) { sampleObs ->
                    record(sampleObs)
                }
                if (controllerResult.status == SensorConfigurationApplyResult.Status.REJECTED) {
                    return DetectorStartResult(
                        started = false,
                        failureReason = "Sensor controller startup rejected",
                    )
                }
                running = true
                publishHealth(
                    SensorKind.VIBRATION,
                    SensorHealth(
                        state = controller.getEffectiveHealth(SensorSource.ACCELEROMETER),
                        vibrationDetail = VibrationHealthDetail(
                            hardwareAvailable = true,
                            isRegistered = true,
                        ),
                    ),
                )
                startOptional(SensorKind.LIGHT) {
                    val hasLight = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
                    if (hasLight) {
                        publishHealth(
                            SensorKind.LIGHT,
                            SensorHealth(
                                state = controller.getEffectiveHealth(SensorSource.AMBIENT_LIGHT),
                                lightDetail = LightHealthDetail(
                                    hardwareSupported = true,
                                    isRegistered = true,
                                ),
                            ),
                        )
                        true
                    } else false
                }
                startOptional(SensorKind.POWER_THERMAL, powerThermal::startMonitoring)
                if (SensorKind.MICROPHONE in usedSensorKinds) {
                    startOptional(SensorKind.MICROPHONE) {
                        packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
                            hasAudioPermission() &&
                            audio.startListening(armedSessionId)
                    }
                }
                initialLocationObs = if (SensorKind.LOCATION in usedSensorKinds) {
                    try {
                        location.currentObservation()
                    } catch (error: RuntimeException) {
                        markFailed(SensorKind.LOCATION, error)
                        null
                    }
                } else {
                    null
                }
            } catch (error: RuntimeException) {
                stop()
                return DetectorStartResult(
                    started = false,
                    failureReason = error.message ?: "Detector startup failed",
                )
            }
        }
        initialLocationObs?.let { obs ->
            try {
                record(obs)
            } catch (error: RuntimeException) {
                markFailed(SensorKind.LOCATION, error)
            }
        }
        return DetectorStartResult(started = true)
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            running = false
            handlerOwner.handler.removeCallbacks(powerConfirmationRunnable)
            handlerOwner.handler.removeCallbacks(powerCommissioningRepeatRunnable)
            controller.stop()
            vibration.stopListening()
            val hasAcc = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            publishHealth(
                SensorKind.VIBRATION,
                SensorHealth(
                    state = if (hasAcc) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
                    vibrationDetail = VibrationHealthDetail(
                        hardwareAvailable = hasAcc,
                        isRegistered = false,
                    ),
                ),
            )
            light.stopListening()
            val hasLight = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
            publishHealth(
                SensorKind.LIGHT,
                powerLightHealthAfterDetectorStop(
                    hasLightSensor = hasLight,
                    powerStatusMonitoringActive = powerStatusMonitoringActive,
                    lastWitnessLux = powerWitnessContinuity.latest()?.lux,
                ),
            )
            if (!powerStatusMonitoringActive) {
                powerThermal.stopMonitoring()
            }
            audio.stopListening()
            unregisterPowerLightSource()
        }
    }

    override fun applySensitivity(level: Int) {
        val clamped = level.coerceIn(1, 10)
        this.sensitivityLevel = clamped
        vibration.setSensitivity(clamped)
        val current = controller.getEffectiveConfiguration()
        val policy = SensorConfigurationPolicy()
        val updated = policy.withGroupSensitivity(
            config = policy.withGroupSensitivity(current, SensorCapability.MOVEMENT, clamped),
            capability = SensorCapability.LIGHT,
            sensitivity = clamped,
        )
        controller.applyConfigurationDiff(updated)
    }

    override fun applySensorConfiguration(config: SensorFusionConfiguration): SensorConfigurationApplyResult {
        val res = controller.applyConfigurationDiff(config)
        synchronized(healthLock) {
            health[SensorKind.VIBRATION] = SensorHealth(
                state = controller.getEffectiveHealth(SensorSource.ACCELEROMETER),
                vibrationDetail = VibrationHealthDetail(
                    hardwareAvailable = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                    isRegistered = running,
                ),
            )
            health[SensorKind.LIGHT] = SensorHealth(
                state = controller.getEffectiveHealth(SensorSource.AMBIENT_LIGHT),
                lightDetail = LightHealthDetail(
                    hardwareSupported = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null,
                    isRegistered = running,
                ),
            )
            _sensorHealth.value = health.toMap()
        }
        return res
    }

    override fun effectiveSensorConfiguration(): SensorFusionConfiguration =
        controller.getEffectiveConfiguration()

    override fun currentGenerationId(): Long = controller.currentGenerationId()

    override fun sourceHealth(source: SensorSource): SensorHealthState = controller.getEffectiveHealth(source)

    override fun freezeAudioAdaptation(nowElapsedMs: Long) {
        audio.freezeAdaptation(nowElapsedMs)
    }

    override fun beginEntrySession(sessionId: String, model: EntryHingeModel, settings: EntryProfileSettings) {
        entrySession.begin(controller.currentGenerationId(), model, settings)
        registerEntryOrientationSource()
    }

    override fun startEntryCommissioningStream() {
        registerEntryOrientationSource()
    }

    override fun stopEntryCommissioningStream() {
        if (!entrySession.isActive) {
            unregisterEntryOrientationSource()
        }
    }

    override fun entryOrientationSamples(): Flow<EntryOrientationSample> = entrySampleFlow

    override fun clearEntryBaseline() {
        entrySession.end()
        unregisterEntryOrientationSource()
    }

    override fun beginPowerSession(sessionId: String, model: PowerWitnessModel, settings: PowerProfileSettings) {
        val resumedSemantic = synchronized(lifecycleLock) {
            pendingResumedPowerSemantic.also { pendingResumedPowerSemantic = null }
        }
        powerSession.begin(controller.currentGenerationId(), model, settings, resumedSemantic)
        registerPowerLightSource()
    }

    override fun clearPowerSession() {
        synchronized(lifecycleLock) {
            pendingResumedPowerSemantic = null
        }
        powerSession.end()
        clearArmedWitnessThresholds()
        handlerOwner.handler.removeCallbacks(powerConfirmationRunnable)
        unregisterPowerLightSource()
    }

    override fun startPowerStatusMonitoring() {
        synchronized(lifecycleLock) {
            powerStatusMonitoringActive = true
            powerThermal.startMonitoring()
            registerPowerLightSource()
        }
    }

    override fun stopPowerStatusMonitoring() {
        synchronized(lifecycleLock) {
            powerStatusMonitoringActive = false
            if (!running) {
                powerThermal.stopMonitoring()
            }
            unregisterPowerLightSource()
        }
    }

    /**
     * Commissioning witness stream: registers a dedicated ambient-light listener so the
     * guided lamp off/on flow receives live lux via [powerWitnessSamples] even while the
     * detector set is not yet started for an armed session.
     */
    override fun startPowerCommissioningStream(): Boolean {
        powerCommissioningStreamActive = true
        val acquired = registerPowerLightSource()
        if (!acquired) {
            powerCommissioningStreamActive = false
            return false
        }
        // The guided window needs uninterrupted evidence, but an on-change light
        // sensor stays silent exactly while the owner holds the lamp steady.
        handlerOwner.handler.removeCallbacks(powerCommissioningRepeatRunnable)
        handlerOwner.handler.postDelayed(
            powerCommissioningRepeatRunnable,
            POWER_COMMISSIONING_REPEAT_INTERVAL_MS,
        )
        return true
    }

    override fun stopPowerCommissioningStream() {
        powerCommissioningStreamActive = false
        handlerOwner.handler.removeCallbacks(powerCommissioningRepeatRunnable)
        unregisterPowerLightSource()
    }

    override fun powerWitnessSamples(): Flow<PowerWitnessSample> = powerSampleFlow

    /**
     * Dedicated ambient-light listener feeding the commissioning flow and the armed-session
     * Power arbiter. Registered while a Power commissioning stream or an armed Power session
     * is active; every reading is emitted as a [PowerWitnessSample] and evaluated by
     * [PowerArmedSessionController], whose verdicts publish through the normal incident
     * pipeline as typed `power_*` diagnostics.
     */
    private fun registerPowerLightSource(): Boolean {
        if (powerLightListener != null) return true
        powerWitnessContinuity.endListenerContinuity()
        val manager = sensorManager ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return false
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.values.isEmpty()) return
                val lux = event.values[0].toDouble()
                if (!lux.isFinite()) return
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val witness = powerWitnessContinuity.record(
                    lux = lux,
                    generation = controller.currentGenerationId(),
                )
                val nowWallClockMs = System.currentTimeMillis()
                publishHealth(
                    SensorKind.LIGHT,
                    SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = nowWallClockMs,
                        lightDetail = LightHealthDetail(
                            hardwareSupported = true,
                            isRegistered = true,
                            lastLux = lux,
                            lastSampleWallClockMs = nowWallClockMs,
                            lastSampleElapsedMs = nowElapsedMs,
                        ),
                    ),
                )
                powerSampleFlow.tryEmit(
                    PowerWitnessSample(lux = lux, timestampMs = nowElapsedMs, fresh = true),
                )
                evaluatePowerArbiter(witness, nowElapsedMs)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        powerLightListener = listener
        return try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, handlerOwner.handler)
        } catch (_: RuntimeException) {
            powerLightListener = null
            powerWitnessContinuity.endListenerContinuity()
            false
        }
    }

    private fun unregisterPowerLightSource() {
        if (powerCommissioningStreamActive || powerStatusMonitoringActive || powerSession.isActive) return
        val listener = powerLightListener
        if (listener != null) {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: RuntimeException) {
                // Listener already detached; nothing to recover.
            }
        }
        powerLightListener = null
        powerWitnessContinuity.endListenerContinuity()
        // No listener means no honest reading to repeat.
        handlerOwner.handler.removeCallbacks(powerCommissioningRepeatRunnable)
    }

    /**
     * Feeds one composite charging/witness sample into the armed-session arbiter and
     * records every produced verdict as a typed `power_*` observation so the existing
     * incident pipeline owns delivery.
     */
    private fun evaluatePowerArbiter(witness: CachedPowerWitness?, timestampMs: Long) {
        if (!powerSession.isActive) return
        val currentGeneration = controller.currentGenerationId()
        val sample = PowerSignalSample(
            chargingConnected = lastChargingConnected,
            witnessLux = witness?.lux,
            fresh = powerWitnessIsFreshForGeneration(
                witnessLux = witness?.lux,
                witnessGeneration = witness?.generation,
                currentGeneration = currentGeneration,
            ),
            timestampMs = timestampMs,
        )
        val verdicts = powerSession.onSample(sample, currentGeneration)
        publishArmedWitnessThresholds()
        verdicts.forEach { verdict ->
            powerVerdictObservation(verdict, sample)?.let(::record)
        }
        schedulePowerConfirmation()
    }

    /** Mirrors the exact armed-session witness thresholds into the health snapshot for the UI. */
    private fun publishArmedWitnessThresholds() {
        val activeModel = powerSession.activeWitnessModel() ?: return
        val current = synchronized(healthLock) { health[SensorKind.LIGHT] } ?: return
        val detail = current.lightDetail ?: return
        if (
            detail.armedWitnessDarkThresholdLux == activeModel.witnessDarkThresholdLux &&
            detail.armedWitnessLitThresholdLux == activeModel.witnessLitThresholdLux
        ) {
            return
        }
        publishHealth(
            SensorKind.LIGHT,
            current.copy(
                lightDetail = detail.copy(
                    armedWitnessDarkThresholdLux = activeModel.witnessDarkThresholdLux,
                    armedWitnessLitThresholdLux = activeModel.witnessLitThresholdLux,
                ),
            ),
        )
    }

    /** Restores display-only thresholds to the saved calibration after the armed session ends. */
    private fun clearArmedWitnessThresholds() {
        val current = synchronized(healthLock) { health[SensorKind.LIGHT] } ?: return
        val detail = current.lightDetail ?: return
        if (
            detail.armedWitnessDarkThresholdLux == null &&
            detail.armedWitnessLitThresholdLux == null
        ) {
            return
        }
        publishHealth(
            SensorKind.LIGHT,
            current.copy(
                lightDetail = detail.copy(
                    armedWitnessDarkThresholdLux = null,
                    armedWitnessLitThresholdLux = null,
                ),
            ),
        )
    }

    /**
     * Drives the arbiter at its exact debounce deadline when an on-change light sensor
     * remains stable and therefore emits no second callback.
     */
    private fun schedulePowerConfirmation() {
        handlerOwner.handler.removeCallbacks(powerConfirmationRunnable)
        val delayMs = powerConfirmationDelayMs(
            deadlineMs = powerSession.nextConfirmationAtMs(),
            nowMs = SystemClock.elapsedRealtime(),
        ) ?: return
        handlerOwner.handler.postDelayed(powerConfirmationRunnable, delayMs)
    }

    private fun powerVerdictObservation(
        verdict: PowerArbiterVerdict,
        sample: PowerSignalSample,
    ): SensorObservation? {
        val diagnostic = when (verdict) {
            is PowerArbiterVerdict.ChargingHealthAlert -> POWER_CHARGING_HEALTH_DIAGNOSTIC
            is PowerArbiterVerdict.WitnessHealthAlert -> POWER_WITNESS_DARK_DIAGNOSTIC
            is PowerArbiterVerdict.ConfirmedLossOpened,
            is PowerArbiterVerdict.LossStillConfirmed,
            -> POWER_CONFIRMED_LOSS_DIAGNOSTIC
            is PowerArbiterVerdict.RecoveredClosed -> POWER_RECOVERED_DIAGNOSTIC
            is PowerArbiterVerdict.ConditionChanged -> when (verdict.to) {
                PowerCompositeArbiter.SemanticState.CHARGING_LOST -> POWER_CHARGING_HEALTH_DIAGNOSTIC
                PowerCompositeArbiter.SemanticState.WITNESS_LOST -> POWER_WITNESS_DARK_DIAGNOSTIC
                PowerCompositeArbiter.SemanticState.DUAL_LOST -> POWER_CONFIRMED_LOSS_DIAGNOSTIC
                PowerCompositeArbiter.SemanticState.HEALTHY_DUAL -> POWER_RECOVERED_DIAGNOSTIC
            }
            // Partial recovery updates the dashboard without owner-visible copy; the
            // episode stays open and the next stable-state verdict carries the message.
            is PowerArbiterVerdict.PartialRecovery -> return null
        }
        return SensorObservation(
            kind = SensorKind.LIGHT,
            source = SensorSource.AMBIENT_LIGHT,
            capability = SensorCapability.LIGHT,
            role = SensorRole.PRIMARY,
            unit = SensorUnit.LUX_RATIO,
            eventElapsedMs = sample.timestampMs,
            wallClockMs = System.currentTimeMillis(),
            normalizedValue = sample.witnessLux ?: 0.0,
            baselineDelta = 0.0,
            valid = true,
            diagnostic = diagnostic,
        )
    }

    /**
     * Dedicated game/rotation-vector listener feeding the armed-session Entry policy.
     * Registered only while an Entry session is active; samples are evaluated by
     * [EntryArmedSessionController] and emitted as typed diagnostic observations.
     */
    private fun registerEntryOrientationSource() {
        val manager = sensorManager ?: return
        if (entryOrientationListener != null) return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.values.size < 4) return
                val sample = EntryOrientationSample(
                    timestampMs = SystemClock.elapsedRealtime(),
                    quaternion = EntryQuaternion(
                        w = event.values[3].toDouble(),
                        x = event.values[0].toDouble(),
                        y = event.values[1].toDouble(),
                        z = event.values[2].toDouble(),
                    ),
                    fresh = true,
                )
                val verdicts = entrySession.onSample(sample, controller.currentGenerationId())
                entrySampleFlow.tryEmit(sample)
                verdicts.forEach { verdict -> record(entryVerdictObservation(verdict, sample)) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        entryOrientationListener = listener
        try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handlerOwner.handler)
        } catch (_: RuntimeException) {
            entryOrientationListener = null
        }
    }

    private fun unregisterEntryOrientationSource() {
        val listener = entryOrientationListener ?: return
        try {
            sensorManager?.unregisterListener(listener)
        } catch (_: RuntimeException) {
            // Listener already detached; nothing to recover.
        }
        entryOrientationListener = null
    }

    private fun entryVerdictObservation(
        verdict: EntryDetectionVerdict,
        sample: EntryOrientationSample,
    ): SensorObservation {
        val (diagnostic, value) = when (verdict) {
            is EntryDetectionVerdict.DoorOpened -> ProtectionDiagnostics.ENTRY_DOOR_OPEN to verdict.angleDeg
            is EntryDetectionVerdict.DoorStillOpen -> ProtectionDiagnostics.ENTRY_DOOR_STILL_OPEN to verdict.angleDeg
            is EntryDetectionVerdict.DoorClosedConfirmed -> ProtectionDiagnostics.ENTRY_DOOR_CLOSED to 0.0
            EntryDetectionVerdict.SourceUnavailable -> ProtectionDiagnostics.ENTRY_SOURCE_UNAVAILABLE to 0.0
            EntryDetectionVerdict.SourceRecovered -> ProtectionDiagnostics.ENTRY_SOURCE_RECOVERED to 0.0
            EntryDetectionVerdict.MountMoved -> ProtectionDiagnostics.ENTRY_MOUNT_MOVED to 0.0
        }
        return SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.GAME_ROTATION_VECTOR,
            capability = SensorCapability.MOVEMENT,
            role = SensorRole.PRIMARY,
            unit = SensorUnit.DEGREES,
            eventElapsedMs = sample.timestampMs,
            wallClockMs = System.currentTimeMillis(),
            normalizedValue = value,
            baselineDelta = value,
            valid = true,
            diagnostic = diagnostic,
        )
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> {
        val current = synchronized(healthLock) { health.toMutableMap() }
        current[SensorKind.LOCATION] = location.trackingHealth.value.toSensorHealth()
        return current
    }

    override fun currentLocationObservation(): SensorObservation = location.currentObservation().also(::updateHealth)

    override fun currentIncidentLocation(): IncidentLocation? {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val fix = location.currentUsableFix(nowElapsedMs) ?: return null
        return IncidentLocation(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            capturedAtWallClockMs = fix.wallClockMs,
        )
    }

    private fun record(observation: SensorObservation) {
        updateHealth(observation)
        onObservation(observation)
    }

    private fun publishHealth(kind: SensorKind, newHealth: SensorHealth) {
        val currentMap = synchronized(healthLock) {
            health[kind] = newHealth
            val map = health.toMutableMap()
            map[SensorKind.LOCATION] = location.trackingHealth.value.toSensorHealth()
            map
        }
        _sensorHealth.value = currentMap
    }

    private fun updateHealth(observation: SensorObservation) {
        val ageMs = SystemClock.elapsedRealtime() - observation.eventElapsedMs
        val baseState = when {
            !observation.valid -> SensorHealthState.FAILED
            ageMs < 0L || ageMs > SENSOR_SAMPLE_FRESHNESS_MS -> SensorHealthState.STALE
            else -> SensorHealthState.HEALTHY
        }
        val isRunning = running
        val telem = if (observation.kind == SensorKind.MICROPHONE) audio.telemetry.value else null
        val locHealth = if (observation.kind == SensorKind.LOCATION) location.trackingHealth.value.toSensorHealth() else null
        val currentMap = synchronized(healthLock) {
            val existing = health[observation.kind]
            val updated = when (observation.kind) {
                SensorKind.VIBRATION -> SensorHealth(
                    state = baseState,
                    lastSampleAtMs = observation.wallClockMs,
                    detail = observation.diagnostic,
                    vibrationDetail = VibrationHealthDetail(
                        hardwareAvailable = existing?.vibrationDetail?.hardwareAvailable ?: true,
                        isRegistered = isRunning,
                        lastSampleWallClockMs = observation.wallClockMs,
                        lastSampleElapsedMs = observation.eventElapsedMs,
                        failureReason = if (!observation.valid) observation.diagnostic else null,
                    ),
                )
                SensorKind.LIGHT -> SensorHealth(
                    state = baseState,
                    lastSampleAtMs = observation.wallClockMs,
                    detail = observation.diagnostic,
                    lightDetail = LightHealthDetail(
                        hardwareSupported = existing?.lightDetail?.hardwareSupported ?: true,
                        isRegistered = isRunning,
                        lastLux = observation.normalizedValue,
                        lastSampleWallClockMs = observation.wallClockMs,
                        lastSampleElapsedMs = observation.eventElapsedMs,
                        failureReason = if (!observation.valid) observation.diagnostic else null,
                    ),
                )
                SensorKind.MICROPHONE -> {
                    SensorHealth(
                        state = baseState,
                        lastSampleAtMs = observation.wallClockMs,
                        detail = observation.diagnostic,
                        microphoneDetail = MicrophoneHealthDetail(
                            audioState = telem?.state ?: AudioRuntimeState.OFF,
                            isRegistered = isRunning && (telem?.state == AudioRuntimeState.LISTENING || telem?.state == AudioRuntimeState.CALIBRATING || telem?.state == AudioRuntimeState.STARTING),
                            modelReady = telem?.modelReady ?: false,
                            lastAudioSampleElapsedMs = observation.eventElapsedMs,
                            lastAudioSampleAtMs = observation.wallClockMs,
                            hardwareAvailable = existing?.microphoneDetail?.hardwareAvailable ?: true,
                            permissionGranted = existing?.microphoneDetail?.permissionGranted ?: true,
                            failureReason = if (!observation.valid) observation.diagnostic else null,
                        ),
                    )
                }
                SensorKind.LOCATION -> {
                    val lh = locHealth ?: location.trackingHealth.value.toSensorHealth()
                    lh.copy(
                        detail = observation.diagnostic ?: lh.detail,
                    )
                }
                SensorKind.POWER_THERMAL -> SensorHealth(
                    state = SensorHealthState.HEALTHY,
                    lastSampleAtMs = observation.wallClockMs,
                    detail = observation.diagnostic,
                    powerThermalDetail = existing?.powerThermalDetail?.copy(
                        lastUpdateWallClockMs = observation.wallClockMs,
                    ) ?: PowerThermalHealthDetail(
                        chargingState = ChargingState.UNKNOWN,
                        lastUpdateWallClockMs = observation.wallClockMs,
                    ),
                )
            }
            health[observation.kind] = updated
            val map = health.toMutableMap()
            map[SensorKind.LOCATION] = location.trackingHealth.value.toSensorHealth()
            map
        }
        _sensorHealth.value = currentMap
    }

    private fun startOptional(
        kind: SensorKind,
        starter: () -> Boolean,
    ) {
        try {
            if (!starter()) {
                val existing: SensorHealth? = synchronized(healthLock) { health[kind] }
                val detailMsg = "listener unavailable"
                val updated = existing?.copy(
                    state = SensorHealthState.UNAVAILABLE,
                    detail = detailMsg,
                    vibrationDetail = existing.vibrationDetail?.copy(isRegistered = false, failureReason = detailMsg),
                    lightDetail = existing.lightDetail?.copy(isRegistered = false, failureReason = detailMsg),
                    powerThermalDetail = existing.powerThermalDetail?.copy(isRegistered = false),
                    microphoneDetail = existing.microphoneDetail?.copy(isRegistered = false, failureReason = detailMsg),
                    locationDetail = existing.locationDetail?.copy(isRegistered = false, failureReason = detailMsg),
                ) ?: SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                    detail = detailMsg,
                )
                publishHealth(kind, updated)
            }
        } catch (error: RuntimeException) {
            markFailed(kind, error)
        }
    }

    private fun markFailed(
        kind: SensorKind,
        error: RuntimeException,
    ) {
        val existing: SensorHealth? = synchronized(healthLock) { health[kind] }
        val errorMsg = error.message ?: "startup failed"
        val updated = existing?.copy(
            state = SensorHealthState.FAILED,
            detail = errorMsg,
            vibrationDetail = existing.vibrationDetail?.copy(isRegistered = false, failureReason = errorMsg),
            lightDetail = existing.lightDetail?.copy(isRegistered = false, failureReason = errorMsg),
            powerThermalDetail = existing.powerThermalDetail?.copy(isRegistered = false),
            microphoneDetail = existing.microphoneDetail?.copy(isRegistered = false, failureReason = errorMsg),
            locationDetail = existing.locationDetail?.copy(isRegistered = false, failureReason = errorMsg),
        ) ?: SensorHealth(
            state = SensorHealthState.FAILED,
            detail = errorMsg,
        )
        publishHealth(kind, updated)
    }

    private fun hasAudioPermission(): Boolean = applicationContext.checkSelfPermission(
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean = applicationContext.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED || applicationContext.checkSelfPermission(
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SENSOR_SAMPLE_FRESHNESS_MS = 5_000L
    }
}
