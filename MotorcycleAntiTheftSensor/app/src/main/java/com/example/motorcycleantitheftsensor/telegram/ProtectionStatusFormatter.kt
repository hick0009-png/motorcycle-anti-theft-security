package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SetupBlocker

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
    /**
     * @param live readings taken now, for the lines that are only true now. Null is a
     *   normal answer and every such line then says it could not be read.
     */
    fun format(
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long = wallClock(),
        nowElapsedMs: Long = elapsedClock(),
        live: LiveStatusReadings? = null,
    ): String {
        return try {
            val projection =
                ProtectionStatusProjection.evaluate(snapshot, nowWallClockMs, nowElapsedMs, live)
            format(projection)
        } catch (e: RuntimeException) {
            "⚠️ ไม่สามารถสร้างรายงานสถานะฉบับเต็มได้ โปรดลองใหม่อีกครั้ง"
        }
    }

    /**
     * One report, in one order, for every installation.
     *
     * The order carries the argument rather than decorating it: which mode and for how
     * long, then what that mode can see right now, then how far it can be believed, then
     * what to do about it. Half of owners read the notification preview and close it, so
     * the first lines are the answer — not a preamble about the Telegram connection, whose
     * health is not in question for someone reading the message it just delivered.
     *
     * There was briefly a second layout for installations with no mode chosen. It was the
     * same mistake this whole report was rebuilt to remove: a second table of truth, which
     * drifts from the first one quietly and is discovered by an owner rather than by us.
     */
    fun format(projection: ProtectionStatusProjection): String = buildString {
        val identity = projection.modeIdentity
        appendLine(identity.headerTh)
        identity.armDurationTh?.let { appendLine(it) }
        identity.noticeTh?.let {
            appendLine()
            appendLine(it)
        }

        appendLine()
        appendLine(projection.watchScope.titleTh)
        projection.watchScope.lines.forEach { appendLine(it) }

        projection.modeSections.forEach { section ->
            appendLine()
            appendLine(section.titleTh)
            section.lines.forEach { appendLine(it) }
        }

        appendLine()
        appendLine(projection.sensorSummary.headerTh)
        projection.sensorSummary.orderedItems.forEach { item ->
            appendLine(item.modeLineTh ?: item.statusLineTh)
        }

        appendLine()
        appendLine("📡 ช่องทางแจ้งเตือน")
        appendLine(projection.primarySystems.serviceStatusTh)
        appendLine(projection.primarySystems.telegramStatusTh)
        appendLine(
            projection.smsFallbackMaskedTh
                ?.let { "SMS สำรอง: ตั้งไว้แล้ว ($it)" }
                ?: "SMS สำรอง: ยังไม่ได้ตั้งไว้",
        )
        appendLine(projection.batteryPower.combinedTh)
        appendLine(projection.batteryPower.powerSourceTh)

        appendLine()
        appendLine("🚨 เหตุการณ์ล่าสุด")
        val incident = projection.lastIncident
        if (incident != null && incident.hasIncident) {
            appendLine(
                listOfNotNull(incident.typeTh, incident.timeTh, incident.statusTh)
                    .joinToString(" · "),
            )
            incident.deliveryTh?.let { appendLine("📤 Telegram: $it") }
        } else {
            appendLine("ยังไม่มีเหตุการณ์")
        }

        appendLine()
        append(projection.issuesSummary.summaryMessageTh)
        if (projection.issuesSummary.hasIssues) {
            projection.issuesSummary.issues.forEach { issue ->
                append("\n")
                append(issue.issueTh)
                append("\nวิธีแก้: ")
                append(issue.guidanceTh)
            }
        }
    }.trimEnd()
}

/**
 * @param setupBlocker why setup is being asked for, when the caller knows.
 *
 * SETUP_REQUIRED is reached from six places and every one of them used to produce the same
 * sentence, which sends the owner to the Telegram settings. Callers that have the typed
 * reason pass it and get copy that matches; callers that do not keep the old sentence.
 */
fun ProtectionState.toGuidanceCode(setupBlocker: SetupBlocker? = null): GuidanceCode = when (this) {
    ProtectionState.SETUP_REQUIRED -> when (setupBlocker) {
        SetupBlocker.PROFILE_SETUP_REQUIRED -> GuidanceCode.SETUP_REQUIRED_PROFILE
        SetupBlocker.RECOMMISSION_REQUIRED -> GuidanceCode.SETUP_REQUIRED_RECOMMISSION
        SetupBlocker.PROFILE_UNSUPPORTED -> GuidanceCode.PROFILE_UNSUPPORTED
        SetupBlocker.NO_PRIMARY_SENSOR -> GuidanceCode.SETUP_REQUIRED_SENSOR
        null -> GuidanceCode.SETUP_REQUIRED
    }
    ProtectionState.DISARMED_ONLINE -> GuidanceCode.DISARMED
    ProtectionState.ARMING -> GuidanceCode.ARMING
    ProtectionState.ARMED_HEALTHY -> GuidanceCode.ARMED_HEALTHY
    ProtectionState.ARMED_DEGRADED -> GuidanceCode.ARMED_DEGRADED
    ProtectionState.ALERT_ACTIVE -> GuidanceCode.ALERT_ACTIVE
    ProtectionState.OFFLINE -> GuidanceCode.OFFLINE
}
