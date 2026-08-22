package com.example.motorcycleantitheftsensor.protection

enum class SensorCapability {
    MOVEMENT,
    ROTATION,
    MAGNETIC,
    LIGHT,
    PROXIMITY,
}

enum class SensorRole {
    OFF,
    SUPPORTING,
    PRIMARY,
}

enum class SensorPreset {
    BATTERY_SAVER,
    BALANCED,
    MAXIMUM_PROTECTION,
}

enum class SensorPresetDisplay {
    BATTERY_SAVER,
    BALANCED,
    MAXIMUM_PROTECTION,
    CUSTOM,
}

enum class SensorSamplingProfile {
    BATTERY_SAVER,
    BALANCED,
    RESPONSIVE,
}

enum class SensorSourceFamily {
    MOTION,
    ORIENTATION,
    MAGNETIC_ORIENTATION,
    LIGHT,
    PROXIMITY,
}

enum class SensorUnit {
    METERS_PER_SECOND_SQUARED,
    RADIANS_PER_SECOND,
    DEGREES,
    MICROTESLA,
    LUX_RATIO,
    NORMALIZED_STATE,
    TRIGGER,
}

data class SensorDetectionParameters(
    val threshold: Double?,
    val unit: SensorUnit,
    val absoluteSafetyLimit: Double?,
    val debounceMs: Long,
    val confirmationDurationMs: Long,
    val requestedSamplingPeriodUs: Int?,
)

enum class SensorSource(
    val capability: SensorCapability,
    val family: SensorSourceFamily,
) {
    SIGNIFICANT_MOTION(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    ACCELEROMETER(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    LINEAR_ACCELERATION(SensorCapability.MOVEMENT, SensorSourceFamily.MOTION),
    GYROSCOPE(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    ROTATION_VECTOR(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    GAME_ROTATION_VECTOR(SensorCapability.ROTATION, SensorSourceFamily.ORIENTATION),
    MAGNETIC_FIELD(SensorCapability.MAGNETIC, SensorSourceFamily.MAGNETIC_ORIENTATION),
    GEOMAGNETIC_ROTATION_VECTOR(SensorCapability.MAGNETIC, SensorSourceFamily.MAGNETIC_ORIENTATION),
    AMBIENT_LIGHT(SensorCapability.LIGHT, SensorSourceFamily.LIGHT),
    PROXIMITY(SensorCapability.PROXIMITY, SensorSourceFamily.PROXIMITY),
}

data class SensorSourceConfiguration(
    val source: SensorSource,
    val role: SensorRole,
    val thresholdOverride: Double? = null,
    val debounceOverrideMs: Long? = null,
    val samplingProfileOverride: SensorSamplingProfile? = null,
)

data class SensorCapabilityConfiguration(
    val capability: SensorCapability,
    val sensitivity: Int,
    val correlationWindowMs: Long = 3_000L,
    val confirmationDurationMs: Long = 1_500L,
    val sources: Map<SensorSource, SensorSourceConfiguration>,
) {
    fun source(source: SensorSource): SensorSourceConfiguration {
        return sources[source] ?: SensorSourceConfiguration(source, SensorRole.OFF)
    }
}

data class SensorFusionConfiguration(
    val schemaVersion: Int = 1,
    val basePreset: SensorPreset,
    val samplingProfile: SensorSamplingProfile,
    val capabilities: Map<SensorCapability, SensorCapabilityConfiguration>,
    val updatedAtMs: Long,
) {
    fun capability(capability: SensorCapability): SensorCapabilityConfiguration {
        return capabilities[capability] ?: SensorCapabilityConfiguration(
            capability = capability,
            sensitivity = 5,
            sources = emptyMap()
        )
    }

    fun source(source: SensorSource): SensorSourceConfiguration {
        return capability(source.capability).source(source)
    }
}

sealed interface SensorConfigurationValidation {
    data object Valid : SensorConfigurationValidation
    data class Invalid(val issues: List<SensorConfigurationIssue>) : SensorConfigurationValidation
}

data class SensorConfigurationIssue(
    val capability: SensorCapability?,
    val source: SensorSource?,
    val code: SensorConfigurationIssueCode,
)

enum class SensorConfigurationIssueCode {
    INVALID_VALUE,
    UNAVAILABLE,
    REGISTRATION_FAILED,
    CALIBRATION_FAILED,
    STALE,
    NO_READY_PRIMARY,
}

enum class SensorDiagnosticCode {
    BELOW_THRESHOLD,
    INVALID_VECTOR,
    UNRELIABLE_ACCURACY,
    ABSOLUTE_LIMIT,
    STALE_GENERATION,
}

sealed interface SensorArmEligibility {
    data object Eligible : SensorArmEligibility
    data object NoConfiguredPrimary : SensorArmEligibility
    data class MissingHardwarePrimary(val missingPrimarySources: List<SensorSource>) : SensorArmEligibility
}
