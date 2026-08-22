package com.example.motorcycleantitheftsensor.protection

class ProtectionStateTelegramNotifier {
    fun messagesFor(
        previous: ProtectionState?,
        current: ProtectionState,
        degradationReasons: Set<String> = emptySet(),
    ): List<String> {
        if (previous == null || previous == current) return emptyList()

        val alertMessage: String? = when {
            // User initiated Arm (DISARMED or SETUP_REQUIRED -> ARMING)
            (previous == ProtectionState.DISARMED_ONLINE || previous == ProtectionState.SETUP_REQUIRED) && current == ProtectionState.ARMING -> {
                UserGuidanceCatalog.content(GuidanceCode.ARMING).telegramTh
            }
            // Arming calibration finished (ARMING -> ARMED)
            previous == ProtectionState.ARMING && current == ProtectionState.ARMED_HEALTHY -> {
                UserGuidanceCatalog.content(GuidanceCode.ARMED_HEALTHY).telegramTh
            }
            previous == ProtectionState.ARMING && current == ProtectionState.ARMED_DEGRADED -> {
                // Filter out transient warm-up reasons (GPS acquiring initial satellite fix, audio classifier noise floor calibration)
                val hardReasons = degradationReasons.filter { reason ->
                    !reason.contains("LOCATION not healthy", ignoreCase = true) &&
                        !reason.contains("MICROPHONE not healthy", ignoreCase = true)
                }
                if (hardReasons.isEmpty()) {
                    UserGuidanceCatalog.content(GuidanceCode.ARMED_HEALTHY).telegramTh
                } else {
                    val thaiReasons = hardReasons.map { formatReasonThai(it) }.joinToString(", ")
                    val base = (UserGuidanceCatalog.content(GuidanceCode.ARMED_DEGRADED).telegramTh ?: "⚠️ การป้องกันทำงานแบบจำกัด:").trim()
                    if (thaiReasons.isNotBlank()) {
                        "$base $thaiReasons"
                    } else {
                        base
                    }
                }
            }
            // User initiated Disarm (from any active armed, arming, or alert state)
            (previous == ProtectionState.ARMED_HEALTHY ||
                previous == ProtectionState.ARMED_DEGRADED ||
                previous == ProtectionState.ARMING ||
                previous == ProtectionState.ALERT_ACTIVE) && current == ProtectionState.DISARMED_ONLINE -> {
                if (previous == ProtectionState.ARMING && degradationReasons.isNotEmpty()) {
                    "⚠️ การเปิดระบบล้มเหลว: ${degradationReasons.joinToString()}"
                } else {
                    UserGuidanceCatalog.content(GuidanceCode.DISARMED).telegramTh
                }
            }
            // All other transitions (including ARMED_HEALTHY <-> ARMED_DEGRADED health flaps) are silent
            else -> null
        }

        return alertMessage?.let { listOf(it) } ?: emptyList()
    }

    private fun formatReasonThai(reason: String): String = when {
        reason.contains("VIBRATION", ignoreCase = true) -> "เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"
        reason.contains("LIGHT", ignoreCase = true) -> "เซนเซอร์วัดแสงไม่พร้อมใช้งาน"
        reason.contains("MICROPHONE", ignoreCase = true) -> "ไมโครโฟนไม่พร้อมใช้งาน"
        reason.contains("LOCATION", ignoreCase = true) || reason.contains("GPS", ignoreCase = true) -> "ระบบระบุตำแหน่ง GPS ไม่พร้อมใช้งาน"
        reason.contains("TELEGRAM", ignoreCase = true) -> "การเชื่อมต่อ Telegram มีปัญหา"
        reason.contains("persistence", ignoreCase = true) -> "การบันทึกข้อมูลมีปัญหา"
        else -> reason
    }
}
