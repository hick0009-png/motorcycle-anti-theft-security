package com.example.motorcycleantitheftsensor.protection

class SensorConfigurationPolicy {

    companion object {
        const val MIN_DEBOUNCE_MS = 250L
        const val MAX_DEBOUNCE_MS = 10_000L
        const val MIN_CORRELATION_MS = 500L
        const val MAX_CORRELATION_MS = 30_000L
        const val MIN_CONFIRMATION_MS = 250L
        const val MAX_CONFIRMATION_MS = 10_000L
        const val DEFAULT_CORRELATION_MS = 3_000L
        const val DEFAULT_CONFIRMATION_MS = 1_500L
        const val MAX_CALIBRATION_SAMPLES_PER_SOURCE = 512

        val DEFAULT_ROLES_BALANCED: Map<SensorSource, SensorRole> = mapOf(
            SensorSource.SIGNIFICANT_MOTION to SensorRole.SUPPORTING,
            SensorSource.ACCELEROMETER to SensorRole.PRIMARY,
            SensorSource.LINEAR_ACCELERATION to SensorRole.SUPPORTING,
            SensorSource.GYROSCOPE to SensorRole.SUPPORTING,
            SensorSource.ROTATION_VECTOR to SensorRole.SUPPORTING,
            SensorSource.GAME_ROTATION_VECTOR to SensorRole.SUPPORTING,
            SensorSource.MAGNETIC_FIELD to SensorRole.SUPPORTING,
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR to SensorRole.SUPPORTING,
            SensorSource.AMBIENT_LIGHT to SensorRole.PRIMARY,
            SensorSource.PROXIMITY to SensorRole.SUPPORTING,
        )

        val DEFAULT_ROLES_BATTERY_SAVER: Map<SensorSource, SensorRole> = mapOf(
            SensorSource.SIGNIFICANT_MOTION to SensorRole.SUPPORTING,
            SensorSource.ACCELEROMETER to SensorRole.PRIMARY,
            SensorSource.LINEAR_ACCELERATION to SensorRole.OFF,
            SensorSource.GYROSCOPE to SensorRole.OFF,
            SensorSource.ROTATION_VECTOR to SensorRole.OFF,
            SensorSource.GAME_ROTATION_VECTOR to SensorRole.OFF,
            SensorSource.MAGNETIC_FIELD to SensorRole.OFF,
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR to SensorRole.SUPPORTING,
            SensorSource.AMBIENT_LIGHT to SensorRole.PRIMARY,
            SensorSource.PROXIMITY to SensorRole.OFF,
        )

        val DEFAULT_ROLES_MAXIMUM_PROTECTION: Map<SensorSource, SensorRole> = mapOf(
            SensorSource.SIGNIFICANT_MOTION to SensorRole.PRIMARY,
            SensorSource.ACCELEROMETER to SensorRole.PRIMARY,
            SensorSource.LINEAR_ACCELERATION to SensorRole.PRIMARY,
            SensorSource.GYROSCOPE to SensorRole.PRIMARY,
            SensorSource.ROTATION_VECTOR to SensorRole.PRIMARY,
            SensorSource.GAME_ROTATION_VECTOR to SensorRole.PRIMARY,
            SensorSource.MAGNETIC_FIELD to SensorRole.PRIMARY,
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR to SensorRole.PRIMARY,
            SensorSource.AMBIENT_LIGHT to SensorRole.PRIMARY,
            SensorSource.PROXIMITY to SensorRole.PRIMARY,
        )
    }

    fun forPreset(preset: SensorPreset, nowMs: Long = System.currentTimeMillis()): SensorFusionConfiguration {
        val (roleMap, samplingProfile, defaultSensitivity) = when (preset) {
            SensorPreset.BATTERY_SAVER -> Triple(DEFAULT_ROLES_BATTERY_SAVER, SensorSamplingProfile.BATTERY_SAVER, 5)
            SensorPreset.BALANCED -> Triple(DEFAULT_ROLES_BALANCED, SensorSamplingProfile.BALANCED, 5)
            SensorPreset.MAXIMUM_PROTECTION -> Triple(DEFAULT_ROLES_MAXIMUM_PROTECTION, SensorSamplingProfile.RESPONSIVE, 8)
        }

        val capabilitiesMap = SensorCapability.entries.associateWith { capability ->
            val sources = SensorSource.entries
                .filter { it.capability == capability }
                .associateWith { source ->
                    SensorSourceConfiguration(
                        source = source,
                        role = roleMap[source] ?: SensorRole.OFF,
                    )
                }
            SensorCapabilityConfiguration(
                capability = capability,
                sensitivity = defaultSensitivity,
                correlationWindowMs = DEFAULT_CORRELATION_MS,
                confirmationDurationMs = DEFAULT_CONFIRMATION_MS,
                sources = sources,
            )
        }

        return SensorFusionConfiguration(
            schemaVersion = 1,
            basePreset = preset,
            samplingProfile = samplingProfile,
            capabilities = capabilitiesMap,
            updatedAtMs = nowMs,
        )
    }

    fun displayPreset(config: SensorFusionConfiguration): SensorPresetDisplay {
        val baseConfig = forPreset(config.basePreset, config.updatedAtMs)
        if (config.samplingProfile != baseConfig.samplingProfile) return SensorPresetDisplay.CUSTOM

        for (capability in SensorCapability.entries) {
            val curCap = config.capability(capability)
            val baseCap = baseConfig.capability(capability)
            if (curCap.sensitivity != baseCap.sensitivity) return SensorPresetDisplay.CUSTOM
            if (curCap.correlationWindowMs != baseCap.correlationWindowMs) return SensorPresetDisplay.CUSTOM
            if (curCap.confirmationDurationMs != baseCap.confirmationDurationMs) return SensorPresetDisplay.CUSTOM

            for (source in SensorSource.entries.filter { it.capability == capability }) {
                val curSrc = curCap.source(source)
                val baseSrc = baseCap.source(source)
                if (curSrc.role != baseSrc.role) return SensorPresetDisplay.CUSTOM
                if (curSrc.thresholdOverride != baseSrc.thresholdOverride) return SensorPresetDisplay.CUSTOM
                if (curSrc.debounceOverrideMs != baseSrc.debounceOverrideMs) return SensorPresetDisplay.CUSTOM
                if (curSrc.samplingProfileOverride != baseSrc.samplingProfileOverride) return SensorPresetDisplay.CUSTOM
            }
        }
        return when (config.basePreset) {
            SensorPreset.BATTERY_SAVER -> SensorPresetDisplay.BATTERY_SAVER
            SensorPreset.BALANCED -> SensorPresetDisplay.BALANCED
            SensorPreset.MAXIMUM_PROTECTION -> SensorPresetDisplay.MAXIMUM_PROTECTION
        }
    }

    fun resetCapability(
        config: SensorFusionConfiguration,
        capability: SensorCapability,
        nowMs: Long = System.currentTimeMillis()
    ): SensorFusionConfiguration {
        val baseConfig = forPreset(config.basePreset, nowMs)
        val updatedCapabilities = config.capabilities.toMutableMap()
        updatedCapabilities[capability] = baseConfig.capability(capability)
        return config.copy(
            capabilities = updatedCapabilities,
            updatedAtMs = nowMs,
        )
    }

    fun withSourceRole(
        config: SensorFusionConfiguration,
        source: SensorSource,
        role: SensorRole,
        nowMs: Long = System.currentTimeMillis()
    ): SensorFusionConfiguration {
        val capability = source.capability
        val currentCap = config.capability(capability)
        val currentSrc = currentCap.source(source)
        val updatedSources = currentCap.sources.toMutableMap()
        updatedSources[source] = currentSrc.copy(role = role)

        val updatedCapabilities = config.capabilities.toMutableMap()
        updatedCapabilities[capability] = currentCap.copy(sources = updatedSources)
        return config.copy(
            capabilities = updatedCapabilities,
            updatedAtMs = nowMs,
        )
    }

    fun withGroupSensitivity(
        config: SensorFusionConfiguration,
        capability: SensorCapability,
        sensitivity: Int,
        nowMs: Long = System.currentTimeMillis()
    ): SensorFusionConfiguration {
        val boundedSensitivity = sensitivity.coerceIn(1, 10)
        val currentCap = config.capability(capability)
        val updatedCapabilities = config.capabilities.toMutableMap()
        updatedCapabilities[capability] = currentCap.copy(sensitivity = boundedSensitivity)
        return config.copy(
            capabilities = updatedCapabilities,
            updatedAtMs = nowMs,
        )
    }

    fun validateForSave(config: SensorFusionConfiguration): SensorConfigurationValidation {
        val issues = mutableListOf<SensorConfigurationIssue>()
        for (cap in config.capabilities.values) {
            if (cap.sensitivity !in 1..10) {
                issues.add(SensorConfigurationIssue(cap.capability, null, SensorConfigurationIssueCode.INVALID_VALUE))
            }
            if (cap.correlationWindowMs !in MIN_CORRELATION_MS..MAX_CORRELATION_MS) {
                issues.add(SensorConfigurationIssue(cap.capability, null, SensorConfigurationIssueCode.INVALID_VALUE))
            }
            if (cap.confirmationDurationMs !in MIN_CONFIRMATION_MS..MAX_CONFIRMATION_MS) {
                issues.add(SensorConfigurationIssue(cap.capability, null, SensorConfigurationIssueCode.INVALID_VALUE))
            }
            for (src in cap.sources.values) {
                if (src.thresholdOverride != null && (src.thresholdOverride <= 0.0 || src.thresholdOverride.isNaN() || src.thresholdOverride.isInfinite())) {
                    issues.add(SensorConfigurationIssue(cap.capability, src.source, SensorConfigurationIssueCode.INVALID_VALUE))
                }
                if (src.debounceOverrideMs != null && src.debounceOverrideMs !in MIN_DEBOUNCE_MS..MAX_DEBOUNCE_MS) {
                    issues.add(SensorConfigurationIssue(cap.capability, src.source, SensorConfigurationIssueCode.INVALID_VALUE))
                }
            }
        }
        return if (issues.isEmpty()) SensorConfigurationValidation.Valid else SensorConfigurationValidation.Invalid(issues)
    }

    fun armEligibility(config: SensorFusionConfiguration): SensorArmEligibility {
        val hasConfiguredPrimary = config.capabilities.values.any { cap ->
            cap.sources.values.any { src -> src.role == SensorRole.PRIMARY }
        }
        return if (hasConfiguredPrimary) {
            SensorArmEligibility.Eligible
        } else {
            SensorArmEligibility.NoConfiguredPrimary
        }
    }

    fun validateForApply(config: SensorFusionConfiguration): SensorConfigurationValidation {
        val saveValidation = validateForSave(config)
        if (saveValidation is SensorConfigurationValidation.Invalid) {
            return saveValidation
        }
        val hasPrimary = config.capabilities.values.any { cap ->
            cap.sources.values.any { src -> src.role == SensorRole.PRIMARY }
        }
        if (!hasPrimary) {
            return SensorConfigurationValidation.Invalid(
                listOf(SensorConfigurationIssue(null, null, SensorConfigurationIssueCode.NO_READY_PRIMARY))
            )
        }
        return SensorConfigurationValidation.Valid
    }

    fun parameters(
        source: SensorSource,
        sensitivity: Int,
        overrides: SensorSourceConfiguration? = null,
        confirmationDurationMs: Long = DEFAULT_CONFIRMATION_MS,
    ): SensorDetectionParameters {
        val clampedSensitivity = sensitivity.coerceIn(1, 10)
        val factor = (10 - clampedSensitivity) / 9.0 // 1 -> 1.0 (least sensitive), 10 -> 0.0 (most sensitive)

        val (baseThreshold, unit, safetyLimit) = when (source) {
            SensorSource.ACCELEROMETER -> {
                val t = 0.4 + (4.0 - 0.4) * factor
                Triple(t, SensorUnit.METERS_PER_SECOND_SQUARED, 20.0)
            }
            SensorSource.LINEAR_ACCELERATION -> {
                val t = 0.3 + (3.0 - 0.3) * factor
                Triple(t, SensorUnit.METERS_PER_SECOND_SQUARED, 15.0)
            }
            SensorSource.SIGNIFICANT_MOTION -> {
                Triple(null, SensorUnit.TRIGGER, null)
            }
            SensorSource.GYROSCOPE -> {
                val t = 0.25 + (2.5 - 0.25) * factor
                Triple(t, SensorUnit.RADIANS_PER_SECOND, 10.0)
            }
            SensorSource.ROTATION_VECTOR, SensorSource.GAME_ROTATION_VECTOR -> {
                val t = 6.0 + (35.0 - 6.0) * factor
                Triple(t, SensorUnit.DEGREES, 120.0)
            }
            SensorSource.MAGNETIC_FIELD -> {
                val t = 6.0 + (30.0 - 6.0) * factor
                Triple(t, SensorUnit.MICROTESLA, 200.0)
            }
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> {
                val t = 8.0 + (45.0 - 8.0) * factor
                Triple(t, SensorUnit.DEGREES, 120.0)
            }
            SensorSource.AMBIENT_LIGHT -> {
                val t = 1.25 + (4.0 - 1.25) * factor
                Triple(t, SensorUnit.LUX_RATIO, 20.0)
            }
            SensorSource.PROXIMITY -> {
                val t = 0.2 + (0.8 - 0.2) * factor
                Triple(t, SensorUnit.NORMALIZED_STATE, 1.0)
            }
        }

        val effectiveThreshold = overrides?.thresholdOverride ?: baseThreshold
        val debounceMs = (overrides?.debounceOverrideMs ?: (500L + (1000L * factor).toLong())).coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS)
        val confirmationMs = confirmationDurationMs

        val samplingPeriodUs = when (overrides?.samplingProfileOverride) {
            SensorSamplingProfile.BATTERY_SAVER -> if (source == SensorSource.SIGNIFICANT_MOTION) null else 500_000
            SensorSamplingProfile.BALANCED -> if (source == SensorSource.SIGNIFICANT_MOTION) null else 200_000
            SensorSamplingProfile.RESPONSIVE -> if (source == SensorSource.SIGNIFICANT_MOTION) null else 100_000
            null -> when (source) {
                SensorSource.SIGNIFICANT_MOTION -> null
                SensorSource.AMBIENT_LIGHT, SensorSource.PROXIMITY -> 100_000 // 10 Hz
                else -> 200_000 // 5 Hz normal
            }
        }

        return SensorDetectionParameters(
            threshold = effectiveThreshold,
            unit = unit,
            absoluteSafetyLimit = safetyLimit,
            debounceMs = debounceMs,
            confirmationDurationMs = confirmationMs,
            requestedSamplingPeriodUs = samplingPeriodUs,
        )
    }
}
