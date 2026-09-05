package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.EntryDriftVerdict
import com.example.motorcycleantitheftsensor.protection.EntryModeFacts
import com.example.motorcycleantitheftsensor.protection.EntryWatchLevel
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot

/**
 * Tells the owner, once, that their door watch has outlived the hours this phone measured
 * itself good for.
 *
 * Waiting to be asked is not good enough. An owner who does not send `/status` is exactly
 * the owner who does not know there is anything to ask about, and the price of silence is
 * not one false alert: it is that after the third alert about a door nobody touched, they
 * stop believing every alert this app sends, including the one that matters.
 *
 * A pure function, so the whole rule can be tested without a phone, a clock or a network.
 * Whatever calls it is only a pipe.
 */
object EntryDriftCeilingWarningPolicy {

    /**
     * @param lastWarnedSessionId the armed session already warned about, read from durable
     *   storage. It has to be durable: the process dies and is restarted routinely during a
     *   session that lasts a night, and an owner woken twice by the same advice learns to
     *   ignore the app rather than the drift. A new `/arm` mints a new session id and so
     *   becomes warnable again, which is correct — it is a new session with a new ceiling.
     * @return the message to send, or null when there is nothing to say. Null is the
     *   overwhelmingly common answer and is not a failure.
     */
    fun evaluate(
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long,
        lastWarnedSessionId: String?,
    ): String? {
        val context = snapshot.modeContext ?: return null
        if (context.selectedProfile != ProtectionProfile.ENTRY) return null
        // Only the angle level has a ceiling. Sound and movement measure no angle, so no
        // amount of elapsed time makes their evidence drift.
        if (context.entryLevel != EntryWatchLevel.DOOR_ANGLE) return null

        val sessionId = snapshot.armedProfileSnapshot?.armedSessionId ?: return null
        if (sessionId == lastWarnedSessionId) return null

        val verdict = (context.modeFacts as? EntryModeFacts)?.driftVerdict ?: return null
        // Limited is the only verdict with a ceiling to cross. Trustworthy has none,
        // NotMeasured has no number to quote, and Unusable was refused at Arm — warning
        // about a session that cannot exist would be a message about nothing.
        if (verdict !is EntryDriftVerdict.Limited) return null
        if (EntryCeilingPolicy.exceededBy(verdict, snapshot, nowWallClockMs) == null) return null

        val armedFor = ModeStatusSections.formatDurationTh(
            nowWallClockMs - (snapshot.protectionActivatedAtMs ?: nowWallClockMs),
        )
        return buildString {
            appendLine("⏳ เฝ้าประตูเกินเพดานเวลาแล้ว")
            appendLine(
                "เครื่องนี้วัดไว้ว่าเฝ้าต่อเนื่องได้" +
                    "${EntryCeilingPolicy.trustedHoursPhrase(verdict)} " +
                    "ตอนนี้อาร์มมาแล้ว $armedFor",
            )
            appendLine("หลังจากนี้มุมอาจไหลเองจนแจ้งเตือนทั้งที่ประตูไม่ได้เปิด")
            // The parenthesis is not decoration. An owner who reads this as "the watch has
            // stopped" will go home, and the watch is still running — less trustworthy, but
            // running.
            append("วิธีแก้: สั่ง /disarm แล้ว /arm ใหม่ เพื่อเริ่มนับมุมใหม่ (ยังเฝ้าอยู่ตามปกติระหว่างนี้)")
        }
    }

    /** The session id to record once [evaluate] has produced a message that was sent. */
    fun sessionIdToRecord(snapshot: ProtectionSnapshot): String? =
        snapshot.armedProfileSnapshot?.armedSessionId
}
