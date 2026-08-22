package com.example.motorcycleantitheftsensor.protection

object IncidentMessagePresentationFactory {

    fun create(
        incident: SecurityIncident,
        isDemo: Boolean = false,
    ): IncidentMessagePresentation {
        val title = PresentationTextCatalog.incidentTitle(incident.type)
        val severity = PresentationTextCatalog.severityLabel(incident.severity)
        val formattedTime = PresentationTextCatalog.formatTimestamp(incident.openedAtMs)
        val stateLabel = PresentationTextCatalog.protectionStateLabel(incident.protectionState)
        val evidenceList = incident.evidence.map { PresentationTextCatalog.formatEvidence(it) }

        val locationLabel = incident.location?.let { loc ->
            "ความแม่นยำประมาณ ${loc.accuracyMeters.toInt()} เมตร"
        }

        val deliverySummary = when (incident.deliveryState) {
            DeliveryState.SENT -> "ส่งข้อความแจ้งเตือนแล้ว"
            DeliveryState.FAILED -> "การส่งข้อความแจ้งเตือนล้มเหลว"
            DeliveryState.PENDING -> "กำลังส่งข้อความแจ้งเตือน"
            DeliveryState.NOT_ELIGIBLE -> "ไม่เข้าเกณฑ์ส่งแจ้งเตือน"
        }

        return IncidentMessagePresentation(
            id = incident.id,
            title = title,
            summary = "พบเหตุการณ์ $title ระดับ $severity",
            severityLabel = severity,
            isUrgent = incident.severity == IncidentSeverity.CRITICAL,
            formattedTime = formattedTime,
            evidenceItems = evidenceList,
            protectionStateLabel = stateLabel,
            recommendedAction = PresentationTextCatalog.NEUTRAL_INCIDENT_GUIDANCE,
            locationLabel = locationLabel,
            deliverySummary = deliverySummary,
            isDemo = isDemo,
        )
    }

    fun formatTelegramMessage(presentation: IncidentMessagePresentation): String {
        val sb = StringBuilder()
        sb.append(presentation.title).append("\n\n")
        sb.append("ระดับความเสี่ยง: ").append(presentation.severityLabel).append("\n")
        sb.append("สถานะระบบ: ").append(presentation.protectionStateLabel).append("\n")
        sb.append("แนะนำ: ").append(presentation.recommendedAction).append("\n\n")
        sb.append("เวลา: ").append(presentation.formattedTime).append("\n")

        if (presentation.evidenceItems.isNotEmpty()) {
            sb.append("หลักฐาน:\n")
            presentation.evidenceItems.forEach { item ->
                sb.append("• ").append(item.valueDescription).append("\n")
            }
            sb.append("\n")
        }

        if (presentation.locationLabel != null) {
            sb.append("ตำแหน่งล่าสุด: ").append(presentation.locationLabel).append("\n")
        }

        sb.append("เหตุการณ์: ").append(presentation.id)
        if (presentation.isDemo) {
            sb.append(" [DEMO]")
        }

        return sb.toString()
    }

    fun formatSmsMessage(presentation: IncidentMessagePresentation): String {
        // SMS Fallback MUST NOT include GPS location
        val demoPrefix = if (presentation.isDemo) "[DEMO] " else ""
        return "${demoPrefix}ALERT: ${presentation.title} | ความเสี่ยง:${presentation.severityLabel} | สถานะ:${presentation.protectionStateLabel} | ID:${presentation.id}"
    }
}
