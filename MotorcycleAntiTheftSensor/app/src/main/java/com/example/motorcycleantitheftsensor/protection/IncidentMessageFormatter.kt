package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationPresentation
import java.util.Locale

class IncidentMessageFormatter(
    private val getSnapshot: () -> ProtectionSnapshot? = { null }
) {
    fun formatTelegram(
        update: IncidentUpdate,
        presentation: LocationPresentation? = null,
    ): String {
        val incident = update.incidentOrNull() ?: return ""
        val code = when (update) {
            is IncidentUpdate.Opened -> GuidanceCode.INCIDENT_OPENED
            is IncidentUpdate.Updated -> GuidanceCode.INCIDENT_UPDATED
            is IncidentUpdate.Escalated -> GuidanceCode.INCIDENT_ESCALATED
            is IncidentUpdate.Closed -> GuidanceCode.INCIDENT_CLOSED
            IncidentUpdate.Ignored -> GuidanceCode.INCIDENT_OPENED
        }
        val template = UserGuidanceCatalog.content(code).telegramTh
            ?: when (update) {
                is IncidentUpdate.Escalated -> "🚨 เหตุยกระดับเป็นวิกฤต: {incidentType}"
                is IncidentUpdate.Closed -> "ℹ️ เหตุการณ์สิ้นสุดแล้ว"
                else -> "🚨 ตรวจพบ {incidentType}"
            }
        var message = template.replace("{incidentType}", incident.type.name)

        if (update !is IncidentUpdate.Closed && incident.lifecycle != IncidentLifecycle.CLOSED) {
            val details = mutableListOf<String>()

            if (update is IncidentUpdate.Escalated) {
                details.add("⚠️ ระดับความรุนแรง: วิกฤต (CRITICAL)")
            }

            if (incident.evidence.isNotEmpty()) {
                incident.evidence.forEach { ev ->
                    if (ev.kind == SensorKind.MICROPHONE && ev.audioThreat != null) {
                        val threat = ev.audioThreat
                        val confPercent = (threat.confidence * 100).toInt()
                        val coherentStr = if (threat.onsetCoherent) " [ตรงกับการสั่น]" else ""
                        details.add("• เสียง: ${threat.category.thaiLabel()} (ความมั่นใจ $confPercent%, +${"%.1f".format(Locale.US, threat.loudnessDeltaDb)} dB, ${threat.occurrenceCount} ครั้ง)$coherentStr")
                    } else {
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

    fun formatTelegram(
        incident: SecurityIncident,
        presentation: LocationPresentation? = null,
    ): String = formatTelegram(incident.toDefaultUpdate(), presentation)

    fun formatSms(update: IncidentUpdate): String {
        val incident = update.incidentOrNull() ?: return ""
        val code = when (update) {
            is IncidentUpdate.Opened -> GuidanceCode.INCIDENT_OPENED
            is IncidentUpdate.Updated -> GuidanceCode.INCIDENT_UPDATED
            is IncidentUpdate.Escalated -> GuidanceCode.INCIDENT_ESCALATED
            is IncidentUpdate.Closed -> GuidanceCode.INCIDENT_CLOSED
            IncidentUpdate.Ignored -> GuidanceCode.INCIDENT_OPENED
        }
        val template = UserGuidanceCatalog.content(code).telegramTh
            ?: when (update) {
                is IncidentUpdate.Escalated -> "🚨 เหตุยกระดับเป็นวิกฤต: {incidentType}"
                is IncidentUpdate.Closed -> "ℹ️ เหตุการณ์สิ้นสุดแล้ว"
                else -> "🚨 ตรวจพบ {incidentType}"
            }
        var message = template.replace("{incidentType}", incident.type.name)

        if (update !is IncidentUpdate.Closed && incident.lifecycle != IncidentLifecycle.CLOSED) {
            val details = mutableListOf<String>()

            if (update is IncidentUpdate.Escalated) {
                details.add("⚠️ ระดับความรุนแรง: วิกฤต (CRITICAL)")
            }

            if (incident.evidence.isNotEmpty()) {
                incident.evidence.filter { it.kind != SensorKind.LOCATION }.forEach { ev ->
                    if (ev.kind == SensorKind.MICROPHONE && ev.audioThreat != null) {
                        val threat = ev.audioThreat
                        val confPercent = (threat.confidence * 100).toInt()
                        details.add("• เสียง: ${threat.category.thaiLabel()} (ความมั่นใจ $confPercent%, +${"%.1f".format(Locale.US, threat.loudnessDeltaDb)} dB, ${threat.occurrenceCount} ครั้ง)")
                    } else {
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

    fun formatSms(incident: SecurityIncident): String = formatSms(incident.toDefaultUpdate())

    fun format(update: IncidentUpdate): String = formatTelegram(update, null)

    fun format(incident: SecurityIncident): String = formatTelegram(incident, null)

    fun formatProgress(incident: SecurityIncident): String {
        val latestEvidence = incident.evidence.maxByOrNull { it.wallClockMs }
        val latestDescription = latestEvidence?.let { evidence ->
            val source = evidence.diagnostic ?: evidence.kind.name.lowercase()
            "ตรวจพบล่าสุด: $source"
        } ?: "กำลังติดตามหลักฐานเพิ่มเติม"
        return "⚠️ เหตุการณ์ยังดำเนินอยู่\n" +
            "ประเภท: ${incident.type.name}\n" +
            "$latestDescription\n" +
            "หลักฐานที่ยืนยันแล้ว: ${incident.evidence.size} รายการ\n" +
            "ระบบจะปิดเหตุเมื่อไม่พบหลักฐานใหม่ต่อเนื่อง 30 วินาที"
    }

    fun formatContinuation(incident: SecurityIncident): String =
        "⚠️ เหตุการณ์ยังดำเนินอยู่\n" +
            "เหตุยังตรวจพบต่อเนื่องเกิน 1 นาที\n" +
            "ประเภท: ${incident.type.name}\n" +
            "หลักฐานที่ยืนยันแล้ว: ${incident.evidence.size} รายการ\n" +
            "ตรวจสอบรถและตำแหน่งล่าสุดทันที"

    private fun SecurityIncident.toDefaultUpdate(): IncidentUpdate = when (lifecycle) {
        IncidentLifecycle.CLOSED -> IncidentUpdate.Closed(this)
        else -> IncidentUpdate.Opened(this)
    }

    private fun IncidentUpdate.incidentOrNull(): SecurityIncident? = when (this) {
        IncidentUpdate.Ignored -> null
        is IncidentUpdate.Opened -> incident
        is IncidentUpdate.Updated -> incident
        is IncidentUpdate.Escalated -> incident
        is IncidentUpdate.Closed -> incident
    }
}

fun AudioThreatCategory.thaiLabel(): String = when (this) {
    AudioThreatCategory.IMPACT -> "เสียงกระแทก/ทุบรถ"
    AudioThreatCategory.BREAKING -> "เสียงกระจก/พลาสติกแตก"
    AudioThreatCategory.POWER_TOOL -> "เสียงเครื่องมือช่าง/หินเจียร์"
    AudioThreatCategory.METAL_TAMPER -> "เสียงงัดแงะโลหะ"
    AudioThreatCategory.ENGINE_START -> "เสียงสตาร์ทเครื่องยนต์"
    AudioThreatCategory.ENGINE_RUNNING -> "เสียงเครื่องยนต์ทำงาน"
}
