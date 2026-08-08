package com.example.motorcycleantitheftsensor.protection

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class IncidentMessageFormatter(
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    fun format(incident: SecurityIncident): String = buildString {
        if (incident.source == IncidentSource.DEMO) append("DEMO — ")
        append(incident.severity.name)
        append(' ')
        append(incident.type.name)
        append('\n')
        append("Time: ")
        append(formatTime(incident.updatedAtMs))
        append('\n')

        incident.evidence
            .filterNot { evidence -> evidence.kind == SensorKind.LOCATION }
            .forEach { evidence ->
                append(formatEvidence(evidence))
                append('\n')
            }

        val location = incident.evidence.lastOrNull { evidence -> evidence.kind == SensorKind.LOCATION }
        append(formatLocation(location))
        append('\n')
        append("Protection: ")
        append(incident.protectionState.name)
        append('\n')
        append("Incident: ")
        append(incident.id)
    }

    private fun formatTime(timestampMs: Long): String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss z",
        Locale.US,
    ).apply {
        timeZone = this@IncidentMessageFormatter.timeZone
    }.format(Date(timestampMs))

    private fun formatEvidence(evidence: IncidentEvidence): String = when (evidence.kind) {
        SensorKind.VIBRATION -> "Vibration delta: %.2f m/s²".format(Locale.US, evidence.baselineDelta)
        SensorKind.LIGHT -> "Light change: %.1f lux".format(Locale.US, evidence.baselineDelta)
        SensorKind.POWER_THERMAL -> if (evidence.diagnostic == "charger_disconnected") {
            "Charger disconnected"
        } else {
            "Temperature: %.1f C".format(Locale.US, evidence.normalizedValue)
        }
        SensorKind.MICROPHONE -> "Relative audio amplitude: %.2f".format(Locale.US, evidence.normalizedValue)
        SensorKind.LOCATION -> error("Location is formatted separately")
    }

    private fun formatLocation(evidence: IncidentEvidence?): String {
        val diagnostic = evidence?.diagnostic
        return if (diagnostic != null && diagnostic.startsWith("fix ")) {
            "Location: $diagnostic"
        } else {
            "Location: unavailable (${diagnostic ?: "no recent fix"})"
        }
    }
}
