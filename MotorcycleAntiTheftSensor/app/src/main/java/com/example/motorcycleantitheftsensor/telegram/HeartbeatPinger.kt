package com.example.motorcycleantitheftsensor.telegram

import android.content.Context
import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.BreadcrumbDomain
import com.example.motorcycleantitheftsensor.protection.BreadcrumbEvent
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionModeContext
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

interface HeartbeatScheduler {
    val isShutdown: Boolean

    fun scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: () -> Unit,
    )

    fun shutdownNow()
}

private class ExecutorHeartbeatScheduler(
    private val executor: ScheduledExecutorService,
) : HeartbeatScheduler {
    override val isShutdown: Boolean get() = executor.isShutdown

    override fun scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: () -> Unit,
    ) {
        executor.scheduleAtFixedRate(task, initialDelay, period, unit)
    }

    override fun shutdownNow() {
        executor.shutdownNow()
    }
}

/**
 * COM-02: HeartbeatPinger
 * Sends a silent heartbeat "alive" status ping every 15 minutes to Telegram Bot.
 * Note: Fixes VULN-08 by omitting cleartext GPS coordinates from heartbeat messages.
 */
class HeartbeatPinger(
    private val context: Context,
    private val prefsManager: EncryptedPrefsManager,
    private val telegramBotClient: TelegramBotClient,
    private var scheduler: HeartbeatScheduler? = null,
    /**
     * The current protection snapshot, for the checks that ride this tick. Absent on a
     * build with no coordinator, where nothing rides it and the heartbeat is unaffected.
     */
    private val snapshotSupplier: (() -> ProtectionSnapshot)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Records that the once-per-session ceiling warning went out, or did not. */
    private val breadcrumb: (BreadcrumbDomain, BreadcrumbEvent) -> Unit = { _, _ -> },
) {
    private var started = false

    @Synchronized
    fun startHeartbeat() {
        if (started && scheduler != null && !scheduler!!.isShutdown) return

        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = ExecutorHeartbeatScheduler(Executors.newSingleThreadScheduledExecutor())
        }
        scheduler?.scheduleAtFixedRate(
            initialDelay = HEARTBEAT_INTERVAL_MINUTES,
            period = HEARTBEAT_INTERVAL_MINUTES,
            unit = TimeUnit.MINUTES,
        ) {
            try {
                sendHeartbeatPing()
            } catch (ignored: Exception) {}
            // Separate try: a heartbeat that failed to send must not also swallow the
            // warning, and a warning that failed must not stop the next heartbeat.
            try {
                sendEntryCeilingWarningIfDue()
            } catch (ignored: Exception) {}
        }
        started = true
    }

    @Synchronized
    fun stopHeartbeat() {
        scheduler?.shutdownNow()
        scheduler = null
        started = false
    }

    private fun sendHeartbeatPing() {
        val allowedChatIds = prefsManager.getAllowedChatIds()
        if (allowedChatIds.isEmpty()) return

        val protectionStatus = if (prefsManager.isSystemArmed()) {
            "เปิดใช้งาน | Protection: Armed"
        } else {
            "ปิดใช้งาน | Protection: Disarmed"
        }
        val timestamp = SimpleDateFormat(
            "d MMM yyyy HH:mm 'ICT'",
            Locale.forLanguageTag("th-TH"),
        ).apply {
            timeZone = TimeZone.getTimeZone("Asia/Bangkok")
        }.format(Date())

        // Placed under the protection line and above the timestamp: an owner reading
        // this on a lock screen needs "armed" and "at what" together, and the clock last.
        val modeLine = modeLine()?.let { "$it\n" } ?: ""

        for (chatId in allowedChatIds) {
            telegramBotClient.sendTelegramMessage(
                chatId,
                "รายงานสถานะระบบ / System Status\n" +
                    "บริการ: ออนไลน์ | Service: Online\n" +
                    "การป้องกัน: $protectionStatus\n" +
                    modeLine +
                    "รายงานเมื่อ: $timestamp",
            )
        }
    }

    /**
     * Which mode this ping speaks for, or null when this build has no protection layer
     * to ask.
     *
     * Every fifteen minutes for the whole of an overnight watch, this message said only
     * that protection was on. An owner who keeps a door watch and a lamp watch set up
     * could not tell from it which one was running — the fault `/status` and the
     * state-change alerts were both rebuilt to remove, arriving here ninety-six times a
     * day. The words are [PresentationTextCatalog]'s, so this cannot answer differently
     * from either of them.
     */
    private fun modeLine(): String? {
        val context = snapshotSupplier?.invoke()?.modeContext ?: return null
        return "โหมด: " + modeDescription(context)
    }

    private fun modeDescription(context: ProtectionModeContext): String {
        // Mid-switch the old mode has stopped and the new one is not armed. A ping that
        // named either would claim a watch that is not running.
        val switchingTo = context.switchingTo
        if (switchingTo != null && switchingTo != context.selectedProfile) {
            val from = context.selectedProfile?.let { PresentationTextCatalog.profile(it).name }
                ?: "ยังไม่ได้เลือก"
            val to = PresentationTextCatalog.profile(switchingTo).name
            return "กำลังสลับ $from → $to (ระหว่างนี้ยังไม่มีการเฝ้า)"
        }
        val profile = context.selectedProfile
            ?: return "ยังไม่ได้เลือก (เปิดแอปเพื่อเลือกโหมด)"
        return PresentationTextCatalog.modeName(profile, context.entryLevel)
    }

    /**
     * Rides the fifteen-minute heartbeat rather than scheduling its own alarm.
     *
     * The obvious implementation is a delay for however many hours the ceiling is, and it
     * would not survive either of the two things that happen routinely during an overnight
     * door watch: doze, and the process being killed. This tick already survives both, and
     * being up to fifteen minutes late on an eight-hour ceiling is an error of a third of
     * one percent — against a warning that today never arrives at all.
     */
    private fun sendEntryCeilingWarningIfDue() {
        val snapshot = snapshotSupplier?.invoke() ?: return
        val message = EntryDriftCeilingWarningPolicy.evaluate(
            snapshot = snapshot,
            nowWallClockMs = nowMs(),
            lastWarnedSessionId = prefsManager.getEntryCeilingWarnedSessionId(),
        ) ?: return
        val sessionId = EntryDriftCeilingWarningPolicy.sessionIdToRecord(snapshot) ?: return
        val allowedChatIds = prefsManager.getAllowedChatIds()
        if (allowedChatIds.isEmpty()) return

        // Recorded before the send, and never after it: a process killed mid-send must not
        // wake the owner again on the next tick with advice they have already read.
        prefsManager.setEntryCeilingWarnedSessionId(sessionId)
        breadcrumb(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.CEILING)
        // Telegram only. This is advice, not an alarm, and burning the SMS allowance on it
        // is how the SMS fallback comes to be unavailable on the night it is needed.
        for (chatId in allowedChatIds) {
            telegramBotClient.sendTelegramMessage(chatId, message)
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MINUTES = 15L
    }
}
