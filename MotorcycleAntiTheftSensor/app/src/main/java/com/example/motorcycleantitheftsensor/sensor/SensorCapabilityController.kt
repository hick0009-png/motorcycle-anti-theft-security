package com.example.motorcycleantitheftsensor.sensor

import android.hardware.SensorManager
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.SensorArmEligibility
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationIssue
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationIssueCode
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SensorConfigurationApplyResult(
    val status: Status,
    val affectedCapabilities: Set<SensorCapability>,
    val degradedSources: List<SensorSource> = emptyList(),
    val issues: List<SensorConfigurationIssue> = emptyList(),
) {
    enum class Status {
        APPLIED,
        APPLIED_DEGRADED,
        REJECTED,
    }
}

interface SensorCapabilityController {
    fun start(config: SensorFusionConfiguration, onObservation: (SensorObservation) -> Unit): SensorConfigurationApplyResult
    fun applyConfigurationDiff(newConfig: SensorFusionConfiguration): SensorConfigurationApplyResult
    fun stop()
    fun getEffectiveHealth(source: SensorSource): SensorHealthState
    fun getEffectiveConfiguration(): SensorFusionConfiguration
    fun isArmEligible(): Boolean
    fun currentGenerationId(): Long
    fun currentGenerationId(source: SensorSource): Long
}

class DefaultSensorCapabilityController(
    private val sensorManager: SensorManager?,
    private val catalog: SensorCatalog = AndroidSensorCatalog(sensorManager),
    private val calibrationManager: SensorCalibrationManager = SensorCalibrationManager(),
    private val normalizer: SensorObservationNormalizer = SensorObservationNormalizer(),
    private val policy: SensorConfigurationPolicy = SensorConfigurationPolicy(),
    private val handlerOwner: SensorHandlerOwner? = null,
    adapterFactory: ((SensorSource) -> SensorSourceAdapter)? = null,
    /**
     * The black box's view of the same samples, and nothing more than a view: it is handed
     * what the detection path was already handed, it cannot alter it, and it cannot stop it.
     * Registering a second listener instead would raise the sampling rate of the watch itself,
     * because Android gives every client the fastest rate any one of them requested.
     */
    private val sampleTap: ((RawSensorSample) -> Unit)? = null,
) : SensorCapabilityController {

    private val generationCounter = AtomicLong(1L)
    private val sourceGenerations = ConcurrentHashMap<SensorSource, Long>()
    @Volatile private var effectiveConfig: SensorFusionConfiguration = policy.forPreset(SensorPreset.BALANCED)
    @Volatile private var observationCallback: ((SensorObservation) -> Unit)? = null
    @Volatile private var isRunning = false

    private val adapters = ConcurrentHashMap<SensorSource, SensorSourceAdapter>()
    private val lifecycleLock = Any()

    init {
        SensorSource.entries.forEach { source ->
            val adapter: SensorSourceAdapter = if (adapterFactory != null) {
                adapterFactory(source)
            } else if (source == SensorSource.SIGNIFICANT_MOTION) {
                SignificantMotionAdapter(sensorManager)
            } else {
                ContinuousSensorAdapter(source, sensorManager, handlerOwner?.handler)
            }
            adapters[source] = adapter
        }
    }

    override fun currentGenerationId(): Long = generationCounter.get()

    override fun currentGenerationId(source: SensorSource): Long = sourceGenerations[source] ?: 0L

    override fun getEffectiveConfiguration(): SensorFusionConfiguration = effectiveConfig

    override fun start(config: SensorFusionConfiguration, onObservation: (SensorObservation) -> Unit): SensorConfigurationApplyResult {
        synchronized(lifecycleLock) {
            stop()
            this.observationCallback = onObservation
            effectiveConfig = config
            isRunning = true

            val enabledSources = mutableSetOf<SensorSource>()
            val degradedSources = mutableListOf<SensorSource>()
            val issues = mutableListOf<SensorConfigurationIssue>()

            SensorSource.entries.forEach { source ->
                val role = config.source(source).role
                if (role != SensorRole.OFF) {
                    if (!catalog.isAvailable(source)) {
                        if (role == SensorRole.PRIMARY) {
                            issues.add(SensorConfigurationIssue(source.capability, source, SensorConfigurationIssueCode.UNAVAILABLE))
                        } else {
                            degradedSources.add(source)
                        }
                    } else {
                        enabledSources.add(source)
                    }
                }
            }

            if (issues.isNotEmpty()) {
                stop()
                return SensorConfigurationApplyResult(
                    status = SensorConfigurationApplyResult.Status.REJECTED,
                    affectedCapabilities = SensorCapability.entries.toSet(),
                    issues = issues,
                )
            }

            enabledSources.forEach { source ->
                val genId = generationCounter.incrementAndGet()
                sourceGenerations[source] = genId
                calibrationManager.startCalibrationForSource(source, genId, safeElapsedRealtime())
            }

            enabledSources.forEach { source ->
                val adapter = adapters[source]
                val genId = sourceGenerations[source] ?: 0L
                if (adapter != null) {
                    val started = adapter.start(config.effectiveSamplingProfile(source)) { sample ->
                        val activeGen = sourceGenerations[sample.source] ?: 0L
                        if (isRunning && activeGen == genId) {
                            // Before the detection work and outside it: a black box that
                            // throws must cost its own row, never an alarm. This is the
                            // sensor thread, so whatever the tap does it must not block.
                            val tap = sampleTap
                            if (tap != null) {
                                try {
                                    tap(sample)
                                } catch (_: Throwable) {
                                    // Recording is not worth a detector.
                                }
                            }
                            calibrationManager.recordSample(genId, sample)
                            val readiness = calibrationManager.getReadiness(sample.source, genId, safeElapsedRealtime())
                            val obs = normalizer.normalize(
                                sample = sample,
                                readiness = readiness,
                                role = config.source(sample.source).role,
                                generationId = genId,
                            )
                            if (readiness is SensorReadiness.Ready) {
                                observationCallback?.invoke(obs)
                            }
                        }
                    }
                    if (!started) {
                        degradedSources.add(source)
                    }
                }
            }

            val status = if (degradedSources.isNotEmpty()) {
                SensorConfigurationApplyResult.Status.APPLIED_DEGRADED
            } else {
                SensorConfigurationApplyResult.Status.APPLIED
            }

            return SensorConfigurationApplyResult(
                status = status,
                affectedCapabilities = SensorCapability.entries.toSet(),
                degradedSources = degradedSources,
            )
        }
    }

    override fun applyConfigurationDiff(newConfig: SensorFusionConfiguration): SensorConfigurationApplyResult {
        synchronized(lifecycleLock) {
            if (!isRunning) {
                effectiveConfig = newConfig
                return SensorConfigurationApplyResult(
                    status = SensorConfigurationApplyResult.Status.APPLIED,
                    affectedCapabilities = emptySet(),
                )
            }

            val oldConfig = effectiveConfig
            val affectedCapabilities = mutableSetOf<SensorCapability>()
            for (cap in SensorCapability.entries) {
                if (oldConfig.capability(cap) != newConfig.capability(cap)) {
                    affectedCapabilities.add(cap)
                }
            }

            val samplingAffectedSources = SensorSource.entries.filterTo(mutableSetOf()) { source ->
                oldConfig.effectiveSamplingProfile(source) != newConfig.effectiveSamplingProfile(source)
            }

            if (affectedCapabilities.isEmpty() && samplingAffectedSources.isEmpty()) {
                effectiveConfig = newConfig
                return SensorConfigurationApplyResult(
                    status = SensorConfigurationApplyResult.Status.APPLIED,
                    affectedCapabilities = emptySet(),
                )
            }

            effectiveConfig = newConfig
            val affectedSources = SensorSource.entries.filter { source ->
                affectedCapabilities.contains(source.capability) ||
                    samplingAffectedSources.contains(source)
            }.toSet()

            affectedCapabilities.addAll(samplingAffectedSources.map { it.capability })
            val degradedSources = mutableListOf<SensorSource>()

            affectedSources.forEach { source ->
                val adapter = adapters[source]
                val role = newConfig.source(source).role
                if (role == SensorRole.OFF || !catalog.isAvailable(source)) {
                    adapter?.stop()
                    sourceGenerations.remove(source)
                    if (role != SensorRole.OFF && !catalog.isAvailable(source)) {
                        degradedSources.add(source)
                    }
                } else {
                    adapter?.stop()
                    val genId = generationCounter.incrementAndGet()
                    sourceGenerations[source] = genId
                    calibrationManager.startCalibrationForSource(source, genId, safeElapsedRealtime())

                    val started = adapter?.start(newConfig.effectiveSamplingProfile(source)) { sample ->
                        val activeGen = sourceGenerations[sample.source] ?: 0L
                        if (isRunning && activeGen == genId) {
                            calibrationManager.recordSample(genId, sample)
                            val readiness = calibrationManager.getReadiness(sample.source, genId, safeElapsedRealtime())
                            val obs = normalizer.normalize(
                                sample = sample,
                                readiness = readiness,
                                role = newConfig.source(sample.source).role,
                                generationId = genId,
                            )
                            if (readiness is SensorReadiness.Ready) {
                                observationCallback?.invoke(obs)
                            }
                        }
                    }
                    if (started != true) {
                        degradedSources.add(source)
                    }
                }
            }

            val status = if (degradedSources.isNotEmpty()) {
                SensorConfigurationApplyResult.Status.APPLIED_DEGRADED
            } else {
                SensorConfigurationApplyResult.Status.APPLIED
            }

            return SensorConfigurationApplyResult(
                status = status,
                affectedCapabilities = affectedCapabilities,
                degradedSources = degradedSources,
            )
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            isRunning = false
            sourceGenerations.clear()
            calibrationManager.invalidateGeneration()
            adapters.values.forEach { it.stop() }
            observationCallback = null
        }
    }

    override fun getEffectiveHealth(source: SensorSource): SensorHealthState {
        val role = effectiveConfig.source(source).role
        if (role == SensorRole.OFF) return SensorHealthState.HEALTHY
        if (!catalog.isAvailable(source)) return SensorHealthState.UNAVAILABLE

        val adapter = adapters[source]
        if (adapter == null || !adapter.isRunning) {
            return if (isRunning) SensorHealthState.FAILED else SensorHealthState.HEALTHY
        }

        val genId = sourceGenerations[source] ?: 0L
        return when (calibrationManager.getReadiness(source, genId, safeElapsedRealtime())) {
            is SensorReadiness.Calibrating -> SensorHealthState.AVAILABLE
            is SensorReadiness.Ready -> SensorHealthState.HEALTHY
            is SensorReadiness.Failed -> SensorHealthState.FAILED
        }
    }

    private fun safeElapsedRealtime(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    override fun isArmEligible(): Boolean {
        if (policy.armEligibility(effectiveConfig) != SensorArmEligibility.Eligible) {
            return false
        }
        val hasAvailablePrimary = SensorSource.entries.any { source ->
            effectiveConfig.source(source).role == SensorRole.PRIMARY && catalog.isAvailable(source)
        }
        return hasAvailablePrimary
    }

    private fun SensorFusionConfiguration.effectiveSamplingProfile(
        source: SensorSource,
    ) = source(source).samplingProfileOverride ?: samplingProfile
}
