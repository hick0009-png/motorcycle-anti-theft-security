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

interface AndroidDetectorSet {
    fun start(): DetectorStartResult

    fun stop()

    fun applySensitivity(level: Int)

    fun currentSensorHealth(): Map<SensorKind, SensorHealth>

    fun currentLocationObservation(): SensorObservation? = null
}

data class IncidentObservationBatch(
    val primary: SensorObservation,
    val supplementalEvidence: List<SensorObservation> = emptyList(),
)

class AndroidProtectionRuntime(
    private val readinessProvider: () -> ReadinessReport,
    detectorFactory: ((SensorObservation) -> Unit) -> AndroidDetectorSet,
    private val observationProcessor: SensorObservationProcessor,
    private val elapsedClock: ProtectionClock,
    private val stateProvider: () -> ProtectionState,
    private val sensorSampleRecorder: (SensorKind, Long, String?) -> Unit,
    private val incidentConsumer: (IncidentObservationBatch) -> Unit,
) : ProtectionRuntime {
    private val detectors = detectorFactory(::handleObservation)

    override fun readiness(): ReadinessReport = readinessProvider()

    override fun startDetectors(): DetectorStartResult {
        observationProcessor.resetSession()
        return detectors.start()
    }

    override fun stopDetectors() {
        detectors.stop()
    }

    override fun applySensitivity(level: Int) {
        detectors.applySensitivity(level)
        observationProcessor.setVibrationSensitivity(level)
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = detectors.currentSensorHealth()

    private fun handleObservation(observation: SensorObservation) {
        val nowElapsedMs = elapsedClock.nowMs()
        if (!observationProcessor.isUsable(observation, nowElapsedMs)) return
        sensorSampleRecorder(
            observation.kind,
            observation.wallClockMs,
            observation.diagnostic,
        )
        when (
            val decision = observationProcessor.accept(
                observation = observation,
                nowElapsedMs = nowElapsedMs,
                arming = stateProvider() == ProtectionState.ARMING,
            )
        ) {
            is ObservationDecision.Accepted -> {
                val supplementalEvidence = if (decision.observation.kind == SensorKind.LOCATION) {
                    emptyList()
                } else {
                    runCatching(detectors::currentLocationObservation)
                        .getOrNull()
                        ?.takeIf { location -> observationProcessor.isUsable(location, elapsedClock.nowMs()) }
                        ?.let(::listOf)
                        .orEmpty()
                }
                incidentConsumer(
                    IncidentObservationBatch(
                        primary = decision.observation,
                        supplementalEvidence = supplementalEvidence,
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

class AndroidRuntimeReadiness(context: Context) {
    private val applicationContext = context.applicationContext
    private val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val packageManager = applicationContext.packageManager

    fun report(): ReadinessReport {
        val blockers = mutableSetOf<String>()
        val degradations = mutableSetOf<String>()
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
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            degradations += "MICROPHONE unavailable"
        } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            degradations += "MICROPHONE requires user-visible start"
        }
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
    private val onObservation: (SensorObservation) -> Unit,
) : AndroidDetectorSet {
    private val applicationContext = context.applicationContext
    private val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val packageManager = applicationContext.packageManager
    private val health = mutableMapOf<SensorKind, SensorHealth>()
    private val vibration = VibrationDetector(applicationContext, ::record)
    private val light = LightIntrusionDetector(applicationContext, ::record)
    private val powerThermal = PowerThermalMonitor(applicationContext, ::record)
    private val audio = AudioPeakDetector(applicationContext, ::record)
    private val location = LocationObservationProvider(applicationContext)
    private var running = false

    init {
        health[SensorKind.VIBRATION] = availability(
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
        )
        health[SensorKind.LIGHT] = availability(
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null,
        )
        health[SensorKind.POWER_THERMAL] = availability(true)
        health[SensorKind.MICROPHONE] = SensorHealth(
            state = SensorHealthState.UNAVAILABLE,
            detail = when {
                !packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> "hardware unavailable"
                !hasAudioPermission() -> "permission unavailable"
                else -> "requires user-visible start"
            },
        )
        health[SensorKind.LOCATION] = availability(
            packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) && hasLocationPermission(),
        )
    }

    @Synchronized
    override fun start(): DetectorStartResult {
        if (running) return DetectorStartResult(started = true)
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            return DetectorStartResult(started = false, failureReason = "ACCELEROMETER unavailable")
        }
        return try {
            if (!vibration.startListening()) {
                return DetectorStartResult(
                    started = false,
                    failureReason = "ACCELEROMETER listener registration failed",
                )
            }
            startOptional(SensorKind.LIGHT) {
                sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null && light.startListening()
            }
            startOptional(SensorKind.POWER_THERMAL, powerThermal::startMonitoring)
            try {
                record(location.currentObservation())
            } catch (error: RuntimeException) {
                markFailed(SensorKind.LOCATION, error)
            }
            running = true
            DetectorStartResult(started = true)
        } catch (error: RuntimeException) {
            stop()
            DetectorStartResult(
                started = false,
                failureReason = error.message ?: "Detector startup failed",
            )
        }
    }

    @Synchronized
    override fun stop() {
        vibration.stopListening()
        light.stopListening()
        powerThermal.stopMonitoring()
        audio.stopListening()
        running = false
    }

    override fun applySensitivity(level: Int) {
        vibration.setSensitivity(level)
    }

    @Synchronized
    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = health.toMap()

    @Synchronized
    override fun currentLocationObservation(): SensorObservation = location.currentObservation().also(::updateHealth)

    @Synchronized
    private fun record(observation: SensorObservation) {
        updateHealth(observation)
        onObservation(observation)
    }

    private fun updateHealth(observation: SensorObservation) {
        val unavailableLocation = observation.kind == SensorKind.LOCATION &&
            observation.diagnostic?.startsWith("fix ") != true
        val ageMs = SystemClock.elapsedRealtime() - observation.eventElapsedMs
        health[observation.kind] = SensorHealth(
            state = when {
                !observation.valid -> SensorHealthState.FAILED
                ageMs < 0L || ageMs > SENSOR_SAMPLE_FRESHNESS_MS -> SensorHealthState.STALE
                unavailableLocation -> SensorHealthState.UNAVAILABLE
                else -> SensorHealthState.HEALTHY
            },
            lastSampleAtMs = observation.wallClockMs,
            detail = observation.diagnostic,
        )
    }

    private fun startOptional(
        kind: SensorKind,
        starter: () -> Boolean,
    ) {
        try {
            if (!starter()) {
                health[kind] = SensorHealth(
                    state = SensorHealthState.UNAVAILABLE,
                    detail = "listener unavailable",
                )
            }
        } catch (error: RuntimeException) {
            markFailed(kind, error)
        }
    }

    private fun markFailed(
        kind: SensorKind,
        error: RuntimeException,
    ) {
        health[kind] = SensorHealth(
            state = SensorHealthState.FAILED,
            detail = error.message ?: "startup failed",
        )
    }

    private fun availability(available: Boolean): SensorHealth = SensorHealth(
        state = if (available) SensorHealthState.AVAILABLE else SensorHealthState.UNAVAILABLE,
    )

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
