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

data class CapabilityPresentation(
    val title: String,
    val explanation: String,
    val roleLabel: String,
    val isPrimaryControl: Boolean,
    val isGenericSensitivityControl: Boolean,
)

data class FormattedMeasurement(
    val value: String,
    val interpretation: String,
)