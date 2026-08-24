package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorKind

class ProtectionStatusFormatter(
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    },
) {
    fun format(
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long = wallClock(),
        nowElapsedMs: Long = elapsedClock(),
    ): String {
        return try {
            val projection = ProtectionStatusProjection.evaluate(snapshot, nowWallClockMs, nowElapsedMs)
            format(projection)
        } catch (e: RuntimeException) {
            "⚠️ ไม่สามารถสร้างรายงานสถานะฉบับเต็มได้ โปรดลองใหม่อีกครั้ง"
        }
    }

    fun format(projection: ProtectionStatusProjection): String = buildString {
        appendLine("🛡️ สถานะระบบป้องกัน")
        appendLine()
        appendLine("สถานะระบบ: ${projection.protectionState.displayStatusTh}")
        projection.protectionState.armDurationTh?.let {
            appendLine("ทำงานมาแล้ว: $it")
        }
        appendLine(projection.protectionState.sensitivityTh)
        appendLine()
        appendLine("📡 ระบบหลัก")
        appendLine(projection.primarySystems.serviceStatusTh)
        appendLine(projection.primarySystems.telegramStatusTh)
        appendLine()
        appendLine(projection.sensorSummary.headerTh)

        val orderedKinds = listOf(
            SensorKind.VIBRATION,
            SensorKind.LIGHT,
            SensorKind.MICROPHONE,
            SensorKind.LOCATION,
            SensorKind.POWER_THERMAL,
        )
        orderedKinds.forEach { kind ->
            projection.sensorSummary.sensors[kind]?.let { item ->
                appendLine(item.statusLineTh)
            }
        }
        appendLine()
        appendLine(projection.batteryPower.batteryPercentTh)
        appendLine(projection.batteryPower.temperatureTh)
        appendLine(projection.batteryPower.powerSourceTh)

        val inc = projection.lastIncident
        if (inc != null && inc.hasIncident) {
            appendLine()
            appendLine("🚨 เหตุการณ์ล่าสุด")
            inc.typeTh?.let { appendLine("ประเภท: $it") }
            inc.timeTh?.let { appendLine("เวลา: $it") }
            inc.statusTh?.let { appendLine("สถานะ: $it") }
            inc.deliveryTh?.let { appendLine("📤 Telegram: $it") }
        }

        appendLine()
        if (!projection.issuesSummary.hasIssues) {
            append(projection.issuesSummary.summaryMessageTh)
        } else {
            val issueText = projection.issuesSummary.issues.joinToString("\n\n") { issue ->
                "${issue.issueTh}\nวิธีแก้: ${issue.guidanceTh}"
            }
            append(issueText)
        }
    }.trimEnd()
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
