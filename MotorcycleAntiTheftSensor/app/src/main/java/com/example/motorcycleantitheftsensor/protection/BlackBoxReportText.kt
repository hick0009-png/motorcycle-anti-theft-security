package com.example.motorcycleantitheftsensor.protection

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The report as it appears at the top of an exported file.
 *
 * Every line is prefixed with `#`, which is not decoration: `BlackBoxCsv.parse` already drops
 * comment lines, so an export carrying its own summary is still exactly as machine-readable
 * as one without. The person who opens the file in a text editor gets the story before the
 * numbers, the person who opens it in pandas passes `comment='#'` and never sees it, and
 * neither has to be told which kind of file this is.
 *
 * Written in Thai because the reader is whoever answers the phone to the owner, and written
 * as findings rather than as a table dump: a support call needs "the app was killed at 03:12
 * and here is what the system said", not four hundred rows to scroll.
 */
object BlackBoxReportText {

    private const val WIDTH = 78

    fun render(report: BlackBoxReport, timeZone: TimeZone = TimeZone.getDefault()): String {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        val shortClock = SimpleDateFormat("dd/MM HH:mm", Locale.US).apply { this.timeZone = timeZone }
        val lines = mutableListOf<String>()

        fun say(text: String = "") = lines.add(if (text.isEmpty()) "#" else "# $text")
        fun rule() = lines.add("# " + "-".repeat(WIDTH))

        rule()
        say("รายงานกล่องดำ — สร้างตอน export ไม่ใช่ตอนอ่าน")
        rule()

        if (report.fromWallMs == null || report.toWallMs == null) {
            say("ไม่มีข้อมูลในไฟล์")
            rule()
            return lines.joinToString("\n") + "\n"
        }

        say("ช่วงเวลา : ${clock.format(Date(report.fromWallMs))} ถึง ${clock.format(Date(report.toWallMs))}")
        say("แถวนาที : ${report.minuteRows} จากที่ควรมี ${report.expectedMinuteRows} (${report.coveragePercent}%)")
        say()

        say("[ ช่องว่าง ]")
        if (report.gaps.isEmpty()) {
            say("  ไม่มีเลย — บันทึกต่อเนื่องตลอดช่วง")
        } else {
            report.gaps.forEach { gap ->
                say(
                    "  ${shortClock.format(Date(gap.fromWallMs))} -> ${shortClock.format(Date(gap.toWallMs))}" +
                        "  ขาด ${gap.minutes} นาที  ${verdictLabel(gap.verdict)}",
                )
                say("      ${gap.detail}")
            }
            val unexplained = report.gaps.count { gap -> gap.verdict == BlackBoxGapVerdict.UNEXPLAINED }
            if (unexplained > 0) {
                say("  ($unexplained ช่วงยังอธิบายไม่ได้ — เครื่อง Android 10 หรือเก่ากว่าไม่มีรายงานสาเหตุการตาย)")
            }
        }
        say()

        say("[ ช่วงที่เฝ้าอยู่ ]")
        if (report.sessions.isEmpty() && report.momentaryArms == 0) {
            say("  ไม่เคยอาร์มเลยตลอดช่วงนี้")
        } else if (report.sessions.isEmpty()) {
            say("  ไม่มีช่วงที่อาร์มค้างไว้จริงเลย")
        } else {
            report.sessions.forEach { session ->
                val tail = buildString {
                    append("  ${session.minutes} นาที  โหมด ${session.mode}")
                    append("  เหตุ ${session.incidents}")
                    session.accelerationMaxG?.let { g -> append("  แรงสูงสุด ${format(g)}g") }
                    session.luxMax?.let { lux -> append("  แสงสูงสุด ${lux.toInt()}") }
                    if (session.stillOpen) append("  [ยังอาร์มอยู่]")
                }
                say("  ${shortClock.format(Date(session.fromWallMs))}$tail")
            }
        }
        if (report.momentaryArms > 0) {
            say("  (อีก ${report.momentaryArms} ครั้งเป็นการอาร์มแล้วปลดทันทีภายในนาทีเดียว — ไม่ใช่ช่วงที่เฝ้าจริง)")
        }
        say()

        if (report.exits.isNotEmpty()) {
            say("[ ระบบรายงานการตายของแอป ]")
            report.exits.forEach { row ->
                say("  ${clock.format(Date(row.wallMs))}  ${row.note}")
                if (row.state != BlackBoxState.UNKNOWN) {
                    say(
                        "      ตอนนั้น: ${if (row.state.armed) "อาร์มอยู่" else "ไม่ได้อาร์ม"}" +
                            " โหมด ${row.state.mode} แบต ${row.state.batteryPercent ?: "?"}%" +
                            " เหตุสะสม ${row.state.incidents}",
                    )
                }
            }
            say()
        }

        say("[ จุดที่ไฟล์ขัดแย้งกันเอง ]")
        if (report.contradictions.isEmpty()) {
            say("  ไม่พบ — ทุกคอลัมน์สอดคล้องกัน")
        } else {
            report.contradictions.forEach { issue ->
                say("  ${shortClock.format(Date(issue.atWallMs))}  ${issue.summary}")
            }
        }
        rule()
        say("บรรทัดที่ขึ้นต้นด้วย # ถูกข้ามโดยตัวอ่านไฟล์ ข้อมูลดิบเริ่มด้านล่างนี้")
        rule()

        return lines.joinToString("\n") + "\n"
    }

    private fun verdictLabel(verdict: BlackBoxGapVerdict): String = when (verdict) {
        BlackBoxGapVerdict.CLEAN_SHUTDOWN -> "= ปิดเอง"
        BlackBoxGapVerdict.KILLED -> "= ถูกฆ่า"
        BlackBoxGapVerdict.DROPPED_ROWS -> "= เขียนไม่ลง"
        BlackBoxGapVerdict.REBOOT -> "= รีบูต"
        BlackBoxGapVerdict.UNEXPLAINED -> "= ไม่ทราบสาเหตุ"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
