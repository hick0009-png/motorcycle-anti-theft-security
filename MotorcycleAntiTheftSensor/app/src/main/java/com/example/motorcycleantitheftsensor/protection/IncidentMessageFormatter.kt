package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationPresentation
import java.util.Locale
import kotlin.math.roundToInt

class IncidentMessageFormatter(
    private val getSnapshot: () -> ProtectionSnapshot? = { null }
) {
    fun formatTelegram(
        update: IncidentUpdate,
        presentation: LocationPresentation? = null,
    ): String {
        val incident = update.incidentOrNull() ?: return ""
        if (incident.type == IncidentType.ENTRY_DOOR) {
            return entryMessage(update, incident)
        }
        if (incident.type == IncidentType.POWER) {
            return powerMessage(update, incident)
        }
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
        if (incident.type == IncidentType.ENTRY_DOOR) {
            return entryMessage(update, incident)
        }
        if (incident.type == IncidentType.POWER) {
            return powerMessage(update, incident)
        }
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

    /**
     * Typed Entry Guard rendering (spec section 8): the latest entry diagnostic owns
     * the copy; mount-moved outranks door events; independently confirmed impact
     * evidence upgrades an open-door message; an owner stop while door evidence is
     * interrupted never claims a confirmed close.
     */
    private fun entryMessage(update: IncidentUpdate, incident: SecurityIncident): String {
        if (
            update is IncidentUpdate.Closed &&
            incident.closeReason?.contains(ENTRY_EVIDENCE_INTERRUPTED_MARKER) == true
        ) {
            return "หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย"
        }
        val latest = incident.evidence.lastOrNull {
            it.diagnostic?.startsWith(ENTRY_DIAGNOSTIC_PREFIX) == true
        }
        return when (latest?.diagnostic) {
            ENTRY_MOUNT_MOVED -> "โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่"
            ENTRY_SOURCE_UNAVAILABLE, ENTRY_SOURCE_RECOVERED ->
                "ข้อมูลมุมประตูขาดหาย กำลังรอเซนเซอร์กลับมาทำงาน"
            ENTRY_DOOR_CLOSED -> "ประตูปิดและนิ่งแล้ว"
            ENTRY_DOOR_OPEN, ENTRY_DOOR_STILL_OPEN ->
                if (hasConfirmedImpact(incident)) {
                    "ตรวจพบแรงกระแทกที่ประตู"
                } else {
                    "ประตูเปิด ${latest.normalizedValue.roundToInt()}° จากตำแหน่งปิด"
                }
            else ->
                if (update is IncidentUpdate.Closed) {
                    "ประตูปิดและนิ่งแล้ว"
                } else {
                    "🚨 ตรวจพบ ${incident.type.name}"
                }
        }
    }

    /** Supporting vibration/audio evidence independently confirms a door impact. */
    private fun hasConfirmedImpact(incident: SecurityIncident): Boolean =
        incident.evidence.any {
            it.diagnostic?.startsWith(ENTRY_DIAGNOSTIC_PREFIX) != true &&
                (it.kind == SensorKind.VIBRATION || it.kind == SensorKind.MICROPHONE)
        }

    /**
     * Typed Power Guard rendering (parent spec sections 4.3/5): the latest power
     * diagnostic owns the copy. One-signal conditions are health alerts that are
     * never called a power outage; only the dual-signal state claims a confirmed
     * loss; recovery copy states the monitored point is stable again.
     */
    private fun powerMessage(update: IncidentUpdate, incident: SecurityIncident): String {
        val latest = incident.evidence.lastOrNull {
            it.diagnostic?.startsWith(POWER_DIAGNOSTIC_PREFIX) == true
        }
        return when (latest?.diagnostic) {
            POWER_CHARGING_HEALTH ->
                "การชาร์จโทรศัพท์หยุด ตรวจสอบสายชาร์จ ที่ชาร์จ หรือพอร์ตชาร์จของโทรศัพท์"
            POWER_WITNESS_DARK ->
                "ไฟยืนยันไม่พบ ตรวจสอบหลอดไฟยืนยัน การวางตำแหน่ง และเส้นทางจ่ายไฟ"
            POWER_CONFIRMED_LOSS ->
                "ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง"
            POWER_RECOVERED ->
                "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"
            else ->
                if (update is IncidentUpdate.Closed) {
                    "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"
                } else {
                    "🚨 ตรวจพบ ${incident.type.name}"
                }
        }
    }

    private companion object {
        val ENTRY_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.ENTRY_PREFIX
        val ENTRY_DOOR_OPEN = ProtectionDiagnostics.ENTRY_DOOR_OPEN
        val ENTRY_DOOR_STILL_OPEN = ProtectionDiagnostics.ENTRY_DOOR_STILL_OPEN
        val ENTRY_DOOR_CLOSED = ProtectionDiagnostics.ENTRY_DOOR_CLOSED
        val ENTRY_SOURCE_UNAVAILABLE = ProtectionDiagnostics.ENTRY_SOURCE_UNAVAILABLE
        val ENTRY_SOURCE_RECOVERED = ProtectionDiagnostics.ENTRY_SOURCE_RECOVERED
        val ENTRY_MOUNT_MOVED = ProtectionDiagnostics.ENTRY_MOUNT_MOVED
        const val ENTRY_EVIDENCE_INTERRUPTED_MARKER = "interrupted"

        val POWER_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.POWER_PREFIX
        val POWER_CHARGING_HEALTH = ProtectionDiagnostics.POWER_CHARGING_HEALTH
        val POWER_WITNESS_DARK = ProtectionDiagnostics.POWER_WITNESS_DARK
        val POWER_CONFIRMED_LOSS = ProtectionDiagnostics.POWER_CONFIRMED_LOSS
        val POWER_RECOVERED = ProtectionDiagnostics.POWER_RECOVERED
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
