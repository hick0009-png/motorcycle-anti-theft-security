package com.example.motorcycleantitheftsensor.protection

/**
 * The alerts sent when the protection state changes, told in terms of the mode that is
 * actually watching.
 *
 * `/status` used to answer with one template for every mode and so told a Power Guard
 * owner to fix a GPS the mode switches off. These messages had the same fault in a
 * quieter form: "✅ การป้องกันทำงานปกติ" says nothing about *what* is now being watched,
 * and the owner who has three modes set up has no way to tell from the alert whether the
 * one they meant is the one that armed. Every mode-dependent word here is read from
 * [PresentationTextCatalog], never restated, which is what keeps this class and the
 * status report from drifting apart again.
 */
class ProtectionStateTelegramNotifier {
    fun messagesFor(
        previous: ProtectionState?,
        current: ProtectionState,
        degradationReasons: Set<String> = emptySet(),
        context: ProtectionModeContext? = null,
    ): List<String> {
        if (previous == null || previous == current) return emptyList()

        val alertMessage: String? = when {
            // User initiated Arm (DISARMED or SETUP_REQUIRED -> ARMING)
            (previous == ProtectionState.DISARMED_ONLINE || previous == ProtectionState.SETUP_REQUIRED) && current == ProtectionState.ARMING -> {
                UserGuidanceCatalog.content(GuidanceCode.ARMING).telegramTh.withModeLine(context)
            }
            // Arming calibration finished (ARMING -> ARMED)
            previous == ProtectionState.ARMING && current == ProtectionState.ARMED_HEALTHY -> {
                UserGuidanceCatalog.content(GuidanceCode.ARMED_HEALTHY).telegramTh
                    .withModeLine(context, includePromise = true)
            }
            previous == ProtectionState.ARMING && current == ProtectionState.ARMED_DEGRADED -> {
                // Filter out transient warm-up reasons (GPS acquiring initial satellite fix, audio classifier noise floor calibration)
                val hardReasons = degradationReasons.filter { reason ->
                    !reason.contains("LOCATION not healthy", ignoreCase = true) &&
                        !reason.contains("MICROPHONE not healthy", ignoreCase = true)
                }
                if (hardReasons.isEmpty()) {
                    UserGuidanceCatalog.content(GuidanceCode.ARMED_HEALTHY).telegramTh
                        .withModeLine(context, includePromise = true)
                } else {
                    val thaiReasons = hardReasons.map { formatReasonThai(it, context) }.joinToString(", ")
                    val base = (UserGuidanceCatalog.content(GuidanceCode.ARMED_DEGRADED).telegramTh ?: "⚠️ การป้องกันทำงานแบบจำกัด:").trim()
                    if (thaiReasons.isNotBlank()) {
                        "$base $thaiReasons"
                    } else {
                        base
                    }.withModeLine(context)
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
                }.withModeLine(context)
            }
            // All other transitions (including ARMED_HEALTHY <-> ARMED_DEGRADED health flaps) are silent
            else -> null
        }

        return alertMessage?.let { listOf(it) } ?: emptyList()
    }

    /**
     * Appends what this alert is about, or leaves the message exactly as it was.
     *
     * A null [context] is the customer who upgraded from before modes existed and has
     * never chosen one. Their alerts stay word for word what they were: inferring a mode
     * from whichever sensors happen to be running would be a guess, and a guess in the
     * line that names what is being protected is worse than the silence it replaces.
     */
    private fun String?.withModeLine(
        context: ProtectionModeContext?,
        includePromise: Boolean = false,
    ): String? {
        val base = this ?: return null
        val modeLines = modeLines(context, includePromise) ?: return base
        return "$base\n$modeLines"
    }

    private fun modeLines(context: ProtectionModeContext?, includePromise: Boolean): String? {
        if (context == null) return null

        // Mid-switch the old mode has stopped and the new one is not armed, so naming
        // either as the thing being watched would claim a watch that is not running.
        val switchingTo = context.switchingTo
        if (switchingTo != null && switchingTo != context.selectedProfile) {
            val from = context.selectedProfile?.let { PresentationTextCatalog.profile(it).name }
                ?: "ยังไม่ได้เลือก"
            val to = PresentationTextCatalog.profile(switchingTo).name
            return "🔄 กำลังสลับโหมด: $from → $to\n⚠️ ระหว่างนี้ยังไม่มีการเฝ้า"
        }

        val profile = context.selectedProfile ?: return null
        val header = "🛡️ " + PresentationTextCatalog.modeLabel(profile, context.entryLevel)
        return if (includePromise) {
            header + "\n" + PresentationTextCatalog.profilePromise(profile, context.entryLevel)
        } else {
            header
        }
    }

    private fun formatReasonThai(reason: String, context: ProtectionModeContext?): String = when {
        // The door watch at angle level hosts its alerts on an orientation verdict that
        // arrives as a vibration-kind observation, so this reason means the angle is what
        // stopped working. "เซนเซอร์แรงสั่น" would send the owner looking for the wrong fault.
        reason.contains("VIBRATION", ignoreCase = true) &&
            context?.selectedProfile == ProtectionProfile.ENTRY &&
            context.entryLevel == EntryWatchLevel.DOOR_ANGLE -> "เซนเซอร์ทิศทาง/มุมประตูไม่พร้อมใช้งาน"
        reason.contains("VIBRATION", ignoreCase = true) -> "เซนเซอร์แรงสั่นไม่พร้อมใช้งาน"
        reason.contains("LIGHT", ignoreCase = true) &&
            context?.selectedProfile == ProtectionProfile.POWER -> "เซนเซอร์แสง (ไฟยืนยัน) ไม่พร้อมใช้งาน"
        reason.contains("LIGHT", ignoreCase = true) -> "เซนเซอร์วัดแสงไม่พร้อมใช้งาน"
        reason.contains("MICROPHONE", ignoreCase = true) -> "ไมโครโฟนไม่พร้อมใช้งาน"
        reason.contains("LOCATION", ignoreCase = true) || reason.contains("GPS", ignoreCase = true) -> "ระบบระบุตำแหน่ง GPS ไม่พร้อมใช้งาน"
        reason.contains("TELEGRAM", ignoreCase = true) -> "การเชื่อมต่อ Telegram มีปัญหา"
        reason.contains("persistence", ignoreCase = true) -> "การบันทึกข้อมูลมีปัญหา"
        else -> reason
    }
}
