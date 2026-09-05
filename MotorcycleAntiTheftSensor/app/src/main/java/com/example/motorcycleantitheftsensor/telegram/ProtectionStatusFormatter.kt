package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SetupBlocker
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

    fun format(projection: ProtectionStatusProjection): String =
        if (projection.modeAware) formatForMode(projection) else formatWithoutMode(projection)

    /**
     * The report an owner who has chosen a mode gets.
     *
     * Order is the argument, not decoration: which mode and how long, then what that mode
     * can see right now, then how far it can be believed, then what to do about it. Half of
     * owners read the notification preview and close it, so the first three lines have to
     * be the answer rather than a preamble about the Telegram connection — which the owner
     * is demonstrably not having trouble with, since this message reached them.
     */
    private fun formatForMode(projection: ProtectionStatusProjection): String = buildString {
        val identity = projection.modeIdentity ?: return@buildString
        appendLine(identity.headerTh)
        identity.armDurationTh?.let { appendLine(it) }
        identity.noticeTh?.let {
            appendLine()
            appendLine(it)
        }

        projection.watchScope?.let { section ->
            appendLine()
            appendLine(section.titleTh)
            section.lines.forEach { appendLine(it) }
        }

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
            appendLine("ยังไม่มีเหตุการณ์ในโหมดนี้")
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

    /**
     * The report of a customer who has never chosen a mode, kept exactly as it was.
     *
     * They are the one audience for whom listing all five sensor kinds is correct: nothing
     * has told this installation what it is guarding, so nothing may be filtered out of the
     * answer. The heading above says so plainly instead of leaving them to guess why the
     * report looks unlike the one in the manual.
     */
    private fun formatWithoutMode(projection: ProtectionStatusProjection): String = buildString {
        appendLine("🛡️ สถานะระบบป้องกัน")
        appendLine()
        projection.unchosenModeNoticeTh?.let {
            appendLine(it)
            appendLine()
        }
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
