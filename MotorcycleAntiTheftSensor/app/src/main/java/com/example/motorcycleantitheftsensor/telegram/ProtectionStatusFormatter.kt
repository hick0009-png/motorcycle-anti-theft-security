package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
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
     * The report for a mode the owner asked about but is not the one running.
     *
     * Deliberately not the full report with different numbers in it. Half of that report
     * is about right now — the angle at this moment, the lamp at this moment, how long the
     * watch has been up — and none of that exists for a mode that is not watching. Printing
     * those lines from the running mode's session would describe the wrong watch while
     * appearing to describe this one, which is the exact fault the mode-aware report was
     * built to remove.
     *
     * What is left is what the owner actually wants when they ask: is this mode still
     * calibrated, what would it alert on, and could it be armed right now.
     */
    fun formatOtherMode(
        snapshot: ProtectionSnapshot,
        profile: ProtectionProfile,
        asked: ProtectionModeContext,
        nowWallClockMs: Long = wallClock(),
    ): String = buildString {
        appendLine(
            "🛡️ " + PresentationTextCatalog.modeLabel(profile, asked.entryLevel) +
                " · ไม่ได้เฝ้าอยู่ตอนนี้",
        )
        appendLine(runningModeLine(snapshot, nowWallClockMs))

        val scope = ModeStatusSections.watchScope(snapshot, asked)
        appendLine()
        appendLine(scope.titleTh)
        scope.lines.forEach { appendLine(it) }

        val readiness = ModeStatusSections.readiness(asked)
        appendLine()
        appendLine(readiness.titleTh)
        readiness.lines.forEach { appendLine(it) }

        appendLine()
        appendLine("ℹ️ ค่าที่วัดได้ขณะนี้มีเฉพาะโหมดที่กำลังเฝ้าอยู่")
        appendLine("สลับมาโหมดนี้ในแอปแล้วสั่ง /arm จึงจะเห็นค่าสด · ดูโหมดที่เฝ้าอยู่ด้วย /status")
    }.trimEnd()

    /**
     * What is watching instead, named so the owner cannot mistake this report for the
     * running one.
     */
    private fun runningModeLine(snapshot: ProtectionSnapshot, nowWallClockMs: Long): String {
        val context = snapshot.modeContext
        val running = context?.selectedProfile
            ?: return "ตอนนี้ยังไม่ได้เลือกโหมดไว้เฝ้า"
        val state = ModeStatusSections.stateLabel(snapshot.state)
        if (!ModeStatusSections.isWatching(snapshot.state)) {
            // "watching with the vehicle mode · not watching" is a sentence that argues with
            // itself. Nothing is running, so the honest line names the selection instead.
            return "โหมดที่เลือกไว้ตอนนี้: " +
                PresentationTextCatalog.modeName(running, context.entryLevel) + " · " + state
        }
        val duration = ModeStatusSections.armedDuration(snapshot, nowWallClockMs)
            ?.let { " · เฝ้ามาแล้ว $it" }
            ?: ""
        return "ตอนนี้เฝ้าด้วย" + PresentationTextCatalog.modeLabel(running, context.entryLevel) +
            " · " + state + duration
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
