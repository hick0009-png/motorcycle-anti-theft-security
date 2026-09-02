package com.example.motorcycleantitheftsensor.protection

/**
 * Immutable presentation values for the profile-aware Thai UX layer (Task 1 of the
 * profile-aware Thai UX plan). Domain enums stay authoritative; these types carry only
 * owner-facing wording so Compose screens never transform enum names for display.
 */
data class ProfilePresentation(
    val name: String,
    val promise: String,
)

/**
 * Owner-facing copy for sensors a profile pins OFF. All fields are null when the
 * profile locks nothing, so a caller cannot render an empty lock banner by accident.
 */
data class SensorLockPresentation(
    val notice: String? = null,
    val reason: String? = null,
    val presetNotice: String? = null,
)

data class CapabilityPresentation(
    val title: String,
    val explanation: String,
    val roleLabel: String,
    val isPrimaryControl: Boolean,
    val isGenericSensitivityControl: Boolean,
)

/**
 * What one hardware source contributes to one protection use, and what changing its
 * role would really do.
 *
 * Every line here has to hold against [IncidentEngine] and [SensorCapabilityController],
 * not against what the sensor sounds like it should do. The engine only lets a PRIMARY
 * observation open an incident, a light reading never opens one on its own whatever its
 * role, and a PRIMARY the device does not have gets the whole configuration rejected
 * rather than merely degraded — those are the facts this copy exists to state.
 *
 * [caveatTh] is set only where the role on this screen does not govern the detection the
 * owner would assume it governs.
 */
data class SensorContribution(
    val source: SensorSource,
    val recommendedRole: SensorRole,
    val detectsTh: String,
    val asPrimaryTh: String,
    val asSupportingTh: String,
    val ifOffTh: String,
    val costTh: String,
    val caveatTh: String? = null,
) {
    /** True for a source its profile does not detect with at all. */
    val unusedByProfile: Boolean get() = recommendedRole == SensorRole.OFF
}

data class FormattedMeasurement(
    val value: String,
    val interpretation: String,
)