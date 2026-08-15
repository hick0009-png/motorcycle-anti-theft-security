package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationPresentation
import java.util.Locale

class IncidentMessageFormatter(
    private val getSnapshot: () -> ProtectionSnapshot? = { null }
) {
    fun formatTelegram(
        incident: SecurityIncident,
        presentation: LocationPresentation? = null,
    ): String {
        val code = when (incident.lifecycle) {
            IncidentLifecycle.OPEN -> GuidanceCode.INCIDENT_OPENED
            IncidentLifecycle.INTERRUPTED -> GuidanceCode.INCIDENT_ESCALATED
            IncidentLifecycle.CLOSED -> GuidanceCode.INCIDENT_CLOSED
        }
        val template = UserGuidanceCatalog.content(code).telegramTh ?: ""
        var message = template.replace("{incidentType}", incident.type.name)

        if (incident.lifecycle != IncidentLifecycle.CLOSED) {
            val details = mutableListOf<String>()

            if (incident.evidence.isNotEmpty()) {
                incident.evidence.forEach { ev ->
                    val kindName = when (ev.kind) {
                        SensorKind.LIGHT -> "แสงสว่างลอดเข้าใต้เบาะ"
                        SensorKind.VIBRATION -> "รถถูกขยับหรือมุมเอียงเปลี่ยนไป"
                        SensorKind.POWER_THERMAL -> "ระบบไฟ/ความร้อน"
                        SensorKind.MICROPHONE -> "เสียง"
                        SensorKind.LOCATION -> "พิกัด"
                    }
                    val diag = ev.diagnostic ?: "N/A"
                    val verb = when (ev.kind) {
                        SensorKind.LIGHT -> "ตรวจพบความสว่างเปลี่ยนไป"
                        SensorKind.VIBRATION -> "ตรวจพบการเอียง/สั่น"
                        else -> "ตรวจพบค่าเปลี่ยนไป"
                    }
                    details.add("• $kindName ($diag): $verb Δ ${"%.2f".format(Locale.US, ev.baselineDelta)}")
                }
            }

            val snapshot = getSnapshot()
            if (snapshot?.batteryLevelPercent != null) {
                val tempStr = snapshot.batteryTemperatureCelsius?.let { " (%.1f°C)".format(Locale.US, it) } ?: ""
                details.add("🔋 แบตเตอรี่: ${snapshot.batteryLevelPercent}%$tempStr")
            }

            if (presentation != null) {
                if (!presentation.labelTh.isNullOrBlank()) {
                    details.add("📍 ตำแหน่ง: ${presentation.labelTh}")
                }
                details.add("🗺️ แผนที่: ${presentation.mapsUrl} (ความแม่นยำ ~${presentation.accuracyMeters}m)")
            }

            if (details.isNotEmpty()) {
                message += "\nรายละเอียด:\n" + details.joinToString("\n")
            }
        }
        return message
    }

    fun formatSms(incident: SecurityIncident): String {
        val code = when (incident.lifecycle) {
            IncidentLifecycle.OPEN -> GuidanceCode.INCIDENT_OPENED
            IncidentLifecycle.INTERRUPTED -> GuidanceCode.INCIDENT_ESCALATED
            IncidentLifecycle.CLOSED -> GuidanceCode.INCIDENT_CLOSED
        }
        val template = UserGuidanceCatalog.content(code).telegramTh ?: ""
        var message = template.replace("{incidentType}", incident.type.name)

        if (incident.lifecycle != IncidentLifecycle.CLOSED) {
            val details = mutableListOf<String>()

            if (incident.evidence.isNotEmpty()) {
                incident.evidence.filter { it.kind != SensorKind.LOCATION }.forEach { ev ->
                    val kindName = when (ev.kind) {
                        SensorKind.LIGHT -> "แสงสว่างลอดเข้าใต้เบาะ"
                        SensorKind.VIBRATION -> "รถถูกขยับหรือมุมเอียงเปลี่ยนไป"
                        SensorKind.POWER_THERMAL -> "ระบบไฟ/ความร้อน"
                        SensorKind.MICROPHONE -> "เสียง"
                        else -> ev.kind.name
                    }
                    val diag = ev.diagnostic ?: "N/A"
                    val verb = when (ev.kind) {
                        SensorKind.LIGHT -> "ตรวจพบความสว่างเปลี่ยนไป"
                        SensorKind.VIBRATION -> "ตรวจพบการเอียง/สั่น"
                        else -> "ตรวจพบค่าเปลี่ยนไป"
                    }
                    details.add("• $kindName ($diag): $verb Δ ${"%.2f".format(Locale.US, ev.baselineDelta)}")
                }
            }

            val snapshot = getSnapshot()
            if (snapshot?.batteryLevelPercent != null) {
                val tempStr = snapshot.batteryTemperatureCelsius?.let { " (%.1f°C)".format(Locale.US, it) } ?: ""
                details.add("🔋 แบตเตอรี่: ${snapshot.batteryLevelPercent}%$tempStr")
            }

            if (details.isNotEmpty()) {
                message += "\nรายละเอียด:\n" + details.joinToString("\n")
            }
        }
        return message
    }

    fun format(incident: SecurityIncident): String = formatTelegram(incident, null)
}
