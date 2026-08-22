package com.example.motorcycleantitheftsensor.protection

data class IncidentEvidencePresentation(
    val label: String,
    val valueDescription: String,
    val confidence: Double? = null,
)

data class IncidentMessagePresentation(
    val id: String,
    val title: String,
    val summary: String,
    val severityLabel: String,
    val isUrgent: Boolean,
    val formattedTime: String,
    val evidenceItems: List<IncidentEvidencePresentation>,
    val protectionStateLabel: String,
    val recommendedAction: String,
    val locationLabel: String? = null,
    val deliverySummary: String,
    val isDemo: Boolean = false,
)

data class ProtectionMessagePresentation(
    val headline: String,
    val protectionOutcome: String,
    val recommendedAction: String,
    val operatingCapabilities: List<String>,
    val degradedCapabilities: List<String>,
    val serviceSummary: String,
    val telegramSummary: String,
    val batterySummary: String,
    val lastIncidentSummary: String? = null,
    val isActionRequired: Boolean = false,
)
