package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.SensorKind
import java.util.Locale

class ProtectionStatusFormatter {
    fun format(snapshot: ProtectionSnapshot): String = buildString {
        appendLine("Protection: ${snapshot.state}")
        appendLine("Service: ${if (snapshot.serviceRunning) "running" else "offline"}")
        appendLine("Telegram: ${if (snapshot.telegramReachable) "reachable" else "unreachable"}")
        appendLine("Telegram polling: ${if (snapshot.telegramPolling) "active" else "inactive"}")
        appendLine("Last Telegram contact: ${snapshot.lastTelegramContactAtMs ?: "unknown"}")
        appendLine(
            "Permission blockers: ${snapshot.permissionBlockers.sorted().takeIf { it.isNotEmpty() }?.joinToString() ?: "none"}",
        )
        appendLine(
            "Degraded: ${snapshot.degradationReasons.sorted().takeIf { it.isNotEmpty() }?.joinToString() ?: "none"}",
        )
        SensorKind.entries.forEach { kind ->
            val health = snapshot.sensorHealth[kind]
            val state = health?.state?.name?.lowercase(Locale.US) ?: "unknown"
            val sample = health?.lastSampleAtMs?.let { atMs -> " at $atMs" }.orEmpty()
            val detail = health?.detail?.let { value -> " ($value)" }.orEmpty()
            appendLine("$kind: $state$sample$detail")
        }
        appendLine("Battery: ${snapshot.batteryLevelPercent?.let { "$it%" } ?: "unknown"}")
        appendLine(
            "Temperature: ${snapshot.batteryTemperatureCelsius?.let { value -> "${value}C" } ?: "unknown"}",
        )
        val incident = snapshot.lastIncident
        appendLine(
            if (incident == null) {
                "Last incident: none"
            } else {
                "Last incident: ${incident.id} ${incident.severity} ${incident.lifecycle}"
            },
        )
        append("Last delivery: ${snapshot.lastDeliveryState ?: "unknown"}")
    }
}
