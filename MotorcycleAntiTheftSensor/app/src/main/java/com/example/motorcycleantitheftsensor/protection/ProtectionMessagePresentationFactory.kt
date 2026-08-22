package com.example.motorcycleantitheftsensor.protection

object ProtectionMessagePresentationFactory {

    fun create(
        state: ProtectionState,
        config: SensorFusionConfiguration,
        healthMap: Map<SensorSource, SensorHealthState>,
        batteryPercent: Int? = null,
        telegramOk: Boolean = true,
        lastIncident: SecurityIncident? = null,
    ): ProtectionMessagePresentation {
        val headline = when (state) {
            ProtectionState.SETUP_REQUIRED -> "ต้องตั้งค่าระบบเริ่มต้นก่อนใช้งาน"
            ProtectionState.DISARMED_ONLINE -> "ระบบปิดการป้องกันอยู่"
            ProtectionState.ARMING -> "กำลังเปิดระบบและปรับเทียบเซ็นเซอร์..."
            ProtectionState.ARMED_HEALTHY -> "ระบบป้องกันทำงานสมบูรณ์ทุกเซ็นเซอร์"
            ProtectionState.ARMED_DEGRADED -> "ระบบป้องกันทำงานแบบจำกัด"
            ProtectionState.ALERT_ACTIVE -> "ตรวจพบเหตุการณ์ผิดปกติ!"
            ProtectionState.OFFLINE -> "ระบบอยู่ในสถานะออฟไลน์"
        }

        val outcome = PresentationTextCatalog.protectionStateLabel(state)

        val recommendedAction = when (state) {
            ProtectionState.ALERT_ACTIVE -> PresentationTextCatalog.NEUTRAL_INCIDENT_GUIDANCE
            ProtectionState.ARMED_DEGRADED -> "ตรวจสอบการตั้งค่าหรือสถานะเซ็นเซอร์ที่ถูกจำกัด"
            ProtectionState.OFFLINE -> "ตรวจสอบการเชื่อมต่อเครือข่ายของอุปกรณ์"
            else -> "ไม่ต้องดำเนินการใดๆ ระบบทำงานปกติ"
        }

        val operatingCapabilities = mutableListOf<String>()
        val degradedCapabilities = mutableListOf<String>()

        SensorCapability.entries.forEach { capability ->
            val capConfig = config.capability(capability)
            val hasEnabledSource = capConfig.sources.values.any { it.role != SensorRole.OFF }
            if (hasEnabledSource) {
                val hasDegradedSource = capConfig.sources.values.any { srcConfig ->
                    srcConfig.role != SensorRole.OFF && (healthMap[srcConfig.source] == SensorHealthState.FAILED || healthMap[srcConfig.source] == SensorHealthState.STALE)
                }
                if (hasDegradedSource) {
                    degradedCapabilities.add(PresentationTextCatalog.capabilityName(capability))
                } else {
                    operatingCapabilities.add(PresentationTextCatalog.capabilityName(capability))
                }
            }
        }

        val serviceSummary = if (state == ProtectionState.DISARMED_ONLINE || state == ProtectionState.SETUP_REQUIRED) "Service หยุดทำงาน" else "Foreground Service กำลังทำงาน"
        val telegramSummary = if (telegramOk) "Telegram เชื่อมต่อปกติ" else "Telegram ไม่พร้อมใช้งาน"
        val batterySummary = batteryPercent?.let { "แบตเตอรี่ $it%" } ?: "แบตเตอรี่ปกติ"
        val lastIncidentSummary = lastIncident?.let {
            "${PresentationTextCatalog.incidentTitle(it.type)} (${PresentationTextCatalog.formatTimestamp(it.openedAtMs)})"
        }

        return ProtectionMessagePresentation(
            headline = headline,
            protectionOutcome = outcome,
            recommendedAction = recommendedAction,
            operatingCapabilities = operatingCapabilities,
            degradedCapabilities = degradedCapabilities,
            serviceSummary = serviceSummary,
            telegramSummary = telegramSummary,
            batterySummary = batterySummary,
            lastIncidentSummary = lastIncidentSummary,
            isActionRequired = state == ProtectionState.ALERT_ACTIVE || state == ProtectionState.ARMED_DEGRADED || state == ProtectionState.OFFLINE,
        )
    }

    fun formatTelegramStatus(presentation: ProtectionMessagePresentation): String {
        val sb = StringBuilder()
        sb.append(presentation.headline).append("\n\n")
        sb.append("สถานะ: ").append(presentation.protectionOutcome).append("\n")
        sb.append("คำแนะนำ: ").append(presentation.recommendedAction).append("\n\n")

        if (presentation.operatingCapabilities.isNotEmpty()) {
            sb.append("เซ็นเซอร์ที่ทำงาน: ").append(presentation.operatingCapabilities.joinToString(", ")).append("\n")
        }
        if (presentation.degradedCapabilities.isNotEmpty()) {
            sb.append("เซ็นเซอร์ที่จำกัด: ").append(presentation.degradedCapabilities.joinToString(", ")).append("\n")
        }

        sb.append("การทำงาน: ").append(presentation.serviceSummary).append("\n")
        sb.append("บอท: ").append(presentation.telegramSummary).append("\n")
        sb.append("พลังงาน: ").append(presentation.batterySummary).append("\n")

        if (presentation.lastIncidentSummary != null) {
            sb.append("\nเหตุการณ์ล่าสุด: ").append(presentation.lastIncidentSummary)
        }

        return sb.toString()
    }
}
