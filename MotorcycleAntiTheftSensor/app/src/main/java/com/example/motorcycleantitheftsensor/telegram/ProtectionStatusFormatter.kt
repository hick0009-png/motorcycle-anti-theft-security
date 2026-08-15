package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.GuidanceDetail
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorHealthState

class ProtectionStatusFormatter {
    fun format(snapshot: ProtectionSnapshot): String = buildString {
        val protectionCode = snapshot.state.toGuidanceCode()
        val protectionStatusTh = UserGuidanceCatalog.content(protectionCode).titleTh

        val template = UserGuidanceCatalog.content(GuidanceCode.COMMAND_STATUS_SUCCESS).telegramTh!!
        appendLine(template.replace("{protectionStatus}", protectionStatusTh))

        if (!snapshot.serviceRunning) {
            val offlineCode = GuidanceCode.OFFLINE
            appendLine(UserGuidanceCatalog.content(offlineCode).telegramTh!!)
        }

        if (!snapshot.telegramReachable) {
            val unreachableCode = GuidanceCode.TELEGRAM_UNREACHABLE
            appendLine(UserGuidanceCatalog.content(unreachableCode).telegramTh!!)
        }

        SensorKind.entries.forEach { kind ->
            val health = snapshot.sensorHealth[kind]
            if (health?.state == SensorHealthState.UNAVAILABLE) {
                appendLine("⚠️ ${kind.name}: ${health.detail ?: "Unknown"}")
            } else if (health?.state == SensorHealthState.FAILED) {
                appendLine("⚠️ ${kind.name}: อ่านค่าเซนเซอร์ไม่สำเร็จ")
            }
        }

        snapshot.batteryLevelPercent?.let { appendLine("🔋 แบตเตอรี่: $it%") }
    }
}

fun ProtectionState.toGuidanceCode(): GuidanceCode = when (this) {
    ProtectionState.SETUP_REQUIRED -> GuidanceCode.SETUP_REQUIRED
    ProtectionState.DISARMED_ONLINE -> GuidanceCode.DISARMED
    ProtectionState.ARMING -> GuidanceCode.ARMING
    ProtectionState.ARMED_HEALTHY -> GuidanceCode.ARMED_HEALTHY
    ProtectionState.ARMED_DEGRADED -> GuidanceCode.ARMED_DEGRADED
    ProtectionState.ALERT_ACTIVE -> GuidanceCode.ALERT_ACTIVE
    ProtectionState.OFFLINE -> GuidanceCode.OFFLINE
}
