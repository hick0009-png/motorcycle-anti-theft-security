package com.example.motorcycleantitheftsensor.protection

/** What a stretch with no minute rows turned out to be. */
enum class BlackBoxGapVerdict {
    /** A `stop` row sits at its edge: the service was shut down, not taken. */
    CLEAN_SHUTDOWN,

    /** The system told us why, and an `X` row inside the gap carries the answer. */
    KILLED,

    /** The app was alive and writing; the disk was not taking it. */
    DROPPED_ROWS,

    /** The clock went backwards across it, which is a boot and not a hole. */
    REBOOT,

    /**
     * Rows stop and nothing accounts for it. On a phone below API 30 this is what an ordinary
     * kill looks like, so it is a question and not an accusation.
     */
    UNEXPLAINED,
}

data class BlackBoxGap(
    val fromWallMs: Long,
    val toWallMs: Long,
    val minutes: Int,
    val verdict: BlackBoxGapVerdict,
    val detail: String,
)

data class BlackBoxArmedSession(
    val fromWallMs: Long,
    val toWallMs: Long,
    val minutes: Int,
    val mode: String,
    val incidents: Int,
    val minuteRows: Int,
    val accelerationMaxG: Double?,
    val luxMax: Double?,
    val stillOpen: Boolean,
)

/** Something the file says about itself that cannot be true at the same time. */
data class BlackBoxContradiction(
    val atWallMs: Long,
    val summary: String,
)

data class BlackBoxReport(
    val fromWallMs: Long?,
    val toWallMs: Long?,
    val minuteRows: Int,
    val expectedMinuteRows: Int,
    val gaps: List<BlackBoxGap>,
    val sessions: List<BlackBoxArmedSession>,
    val exits: List<BlackBoxRow>,
    val contradictions: List<BlackBoxContradiction>,
    /**
     * Arm-then-disarm inside a single minute, counted rather than listed. On the test phone
     * eight of the fourteen stretches were these — someone pressing the button in the app —
     * and listing them buried the two that covered real hours.
     */
    val momentaryArms: Int,
) {
    /** Minutes the file actually accounts for, as a percentage of the span it covers. */
    val coveragePercent: Int
        get() = if (expectedMinuteRows <= 0) 100 else (minuteRows * 100 / expectedMinuteRows).coerceIn(0, 100)
}

/**
 * Reads the record back and says what happened, in the words someone answering the phone to
 * a customer needs.
 *
 * The file has always held the answers and has never stated one. Working out that a gap was a
 * kill, that a mask disagreed with a sample count, or that four hundred minute rows were
 * missing meant writing a parser by hand each time — which is fine for whoever wrote the
 * format and useless to the person who actually takes the call.
 *
 * Everything here is derived, never trusted: the verdicts come from the rows themselves and
 * each one names the evidence it rests on, so a reader can disagree with the conclusion
 * without having to re-derive the data.
 *
 * Gaps are measured on the elapsed clock, not the wall clock. The wall clock is the one a
 * thief or a bug can move, and a record that measured its own holes with it could be made to
 * show no holes at all.
 */
object BlackBoxReportBuilder {

    /** A minute row is due every 60s; anything past this is a hole rather than jitter. */
    private const val GAP_THRESHOLD_MS = 90_000L

    /** Two clocks that disagree by more than this have been moved, not merely rounded. */
    private const val CLOCK_JUMP_TOLERANCE_MS = 2_000L

    fun build(rows: List<BlackBoxRow>): BlackBoxReport {
        if (rows.isEmpty()) {
            return BlackBoxReport(null, null, 0, 0, emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }
        // File order, not sorted. The file is append only, so its order is the record of what
        // happened; sorting by the elapsed clock would quietly repair the one thing a reboot
        // looks like — that clock starting over — and a reboot would read as a hole instead.
        // `X` rows are the exception because they are retrospective by construction.
        val ordered = rows.filter { row -> row.type != BlackBoxRowType.EXIT }
        val minutes = ordered.filter { row -> row.type == BlackBoxRowType.MINUTE }
        val exits = rows.filter { row -> row.type == BlackBoxRowType.EXIT }

        val gaps = gapsIn(ordered, minutes, exits)
        // Summed per pair and only where the clock moved forward, so a boot in the middle
        // neither inflates the span nor makes it negative.
        val span = minutes.zipWithNext()
            .sumOf { (before, after) -> minutesBetween(after.elapsedMs - before.elapsedMs).toLong() }
            .toInt()
            .let { covered -> if (minutes.isEmpty()) 0 else covered + 1 }

        val allSessions = sessionsIn(ordered)
        val (lasting, momentary) = allSessions.partition { session ->
            session.minutes > 0 || session.minuteRows > 0
        }
        return BlackBoxReport(
            fromWallMs = ordered.first().wallMs,
            toWallMs = ordered.last().wallMs,
            minuteRows = minutes.size,
            expectedMinuteRows = span,
            gaps = gaps,
            sessions = lasting,
            exits = exits.sortedBy { row -> row.wallMs },
            contradictions = contradictionsIn(ordered, minutes),
            momentaryArms = momentary.size,
        )
    }

    private fun gapsIn(
        ordered: List<BlackBoxRow>,
        minutes: List<BlackBoxRow>,
        exits: List<BlackBoxRow>,
    ): List<BlackBoxGap> = minutes.zipWithNext().mapNotNull { (before, after) ->
        val elapsedDelta = after.elapsedMs - before.elapsedMs
        if (elapsedDelta in 0L until GAP_THRESHOLD_MS) return@mapNotNull null

        // A clock that ran backwards is a boot, and the rows around it belong to two different
        // runs of the phone rather than to one hole in a single run.
        if (elapsedDelta < 0L) {
            return@mapNotNull BlackBoxGap(
                fromWallMs = before.wallMs,
                toWallMs = after.wallMs,
                minutes = 0,
                verdict = BlackBoxGapVerdict.REBOOT,
                detail = "นาฬิกาตั้งแต่บูตเดินถอยหลัง — เครื่องรีสตาร์ท",
            )
        }

        val missing = minutesBetween(elapsedDelta) - 1
        // Anything the file recorded between the two minute rows, by position rather than by
        // clock: across a boot the clocks are not comparable and the positions still are.
        val insideGap = ordered.subList(
            ordered.indexOf(before) + 1,
            ordered.indexOf(after).coerceAtLeast(ordered.indexOf(before) + 1),
        )
        val stopped = insideGap.any { row ->
            row.type == BlackBoxRowType.STATE && row.note.contains(BlackBoxRecorder.NOTE_STOP)
        }
        val exit = exits.firstOrNull { row -> row.wallMs in before.wallMs..after.wallMs }
        val dropped = after.writeFailures - before.writeFailures

        val (verdict, detail) = when {
            stopped -> BlackBoxGapVerdict.CLEAN_SHUTDOWN to "มีแถว stop ก่อนหน้า — ปิดบริการตามปกติ"
            exit != null -> BlackBoxGapVerdict.KILLED to "ระบบรายงาน: ${exit.note}"
            dropped > 0 -> BlackBoxGapVerdict.DROPPED_ROWS to
                "แอปยังทำงานอยู่ แต่เขียนไม่ลง $dropped แถว — ตรวจพื้นที่ว่างในเครื่อง"
            else -> BlackBoxGapVerdict.UNEXPLAINED to
                "ไม่มีแถว stop ไม่มีรายงานการตาย และไม่มีการเขียนพลาด"
        }
        BlackBoxGap(before.wallMs, after.wallMs, missing, verdict, detail)
    }

    private fun sessionsIn(ordered: List<BlackBoxRow>): List<BlackBoxArmedSession> {
        val sessions = mutableListOf<BlackBoxArmedSession>()
        var open: MutableList<BlackBoxRow>? = null

        fun close(last: BlackBoxRow, stillOpen: Boolean) {
            val window = open ?: return
            open = null
            val minuteRows = window.filter { row -> row.type == BlackBoxRowType.MINUTE }
            val first = window.first()
            sessions += BlackBoxArmedSession(
                fromWallMs = first.wallMs,
                toWallMs = last.wallMs,
                minutes = minutesBetween(last.elapsedMs - first.elapsedMs),
                mode = window.map { row -> row.state.mode }
                    .filter { mode -> mode != BlackBoxState.MODE_NONE }
                    .distinct()
                    .joinToString("/")
                    .ifEmpty { BlackBoxState.MODE_NONE },
                // Summed as rises rather than subtracted end to end. The counter belongs to a
                // process and starts again with the next one, so a session that outlived a
                // restart — which is exactly the session worth reading — subtracted to a
                // negative number of incidents.
                incidents = window.zipWithNext()
                    .sumOf { (a, b) -> (b.state.incidents - a.state.incidents).coerceAtLeast(0) },
                minuteRows = minuteRows.size,
                accelerationMaxG = minuteRows.mapNotNull { row -> row.sensors.accelerationMaxG }.maxOrNull(),
                luxMax = minuteRows.mapNotNull { row -> row.sensors.lux }.maxOrNull(),
                stillOpen = stillOpen,
            )
        }

        ordered.filter { row -> row.type != BlackBoxRowType.EXIT }.forEach { row ->
            if (row.state.armed) {
                (open ?: mutableListOf<BlackBoxRow>().also { list -> open = list }).add(row)
            } else if (open != null) {
                open?.add(row)
                close(row, stillOpen = false)
            }
        }
        open?.lastOrNull()?.let { last -> close(last, stillOpen = true) }
        return sessions
    }

    private fun contradictionsIn(
        ordered: List<BlackBoxRow>,
        minutes: List<BlackBoxRow>,
    ): List<BlackBoxContradiction> {
        val found = mutableListOf<BlackBoxContradiction>()

        // The wall clock moved further than the elapsed clock did. Nothing else in the file
        // would show this, and the header's anchor is only ever taken once.
        ordered.zipWithNext().forEach { (before, after) ->
            val elapsedDelta = after.elapsedMs - before.elapsedMs
            if (elapsedDelta < 0L) return@forEach
            val drift = (after.wallMs - before.wallMs) - elapsedDelta
            if (kotlin.math.abs(drift) > CLOCK_JUMP_TOLERANCE_MS) {
                found += BlackBoxContradiction(
                    after.wallMs,
                    "นาฬิกาเครื่องกระโดด ${drift / 1000L} วินาที เทียบกับนาฬิกาตั้งแต่บูต — เวลาในไฟล์ช่วงนี้เชื่อไม่ได้เต็มที่",
                )
            }
        }

        minutes.lastOrNull { row -> row.writeFailures > 0 }?.let { row ->
            found += BlackBoxContradiction(
                row.wallMs,
                "เขียนไฟล์พลาดสะสม ${row.writeFailures} แถว — ช่องว่างบางช่วงเป็นแถวที่หาย ไม่ใช่แอปถูกฆ่า",
            )
        }

        // The mask naming a kind that never delivered a number is the shape of the bug that
        // put vibration in nine and a half hours of Power Guard rows on the test phone.
        val watching = minutes.filter { row ->
            row.state.armed &&
                row.state.sourceMask and (1 shl SensorKind.VIBRATION.ordinal) != 0 &&
                (row.sensors.samples ?: 0) > 0
        }
        if (watching.size >= CONTRADICTION_MIN_ROWS && watching.all { row -> row.sensors.accelerationMaxG == null }) {
            found += BlackBoxContradiction(
                watching.first().wallMs,
                "srcMask บอกว่าเซ็นเซอร์สั่นสะเทือนทำงานอยู่ ${watching.size} นาที แต่ไม่มีค่าความเร่งสักค่า — สองคอลัมน์ขัดกันเอง",
            )
        }
        return found
    }

    /** Below this a run of blank acceleration is a quiet stretch, not a claim worth doubting. */
    private const val CONTRADICTION_MIN_ROWS = 10

    /**
     * Rounded to the nearest minute, never truncated.
     *
     * `scheduleAtFixedRate` lands a few milliseconds early: on the test phone 275 of 1,511
     * intervals measured 59,995–59,999 ms. Dividing those down gave zero, so a fifth of a
     * day's minutes vanished from the expected count and every real gap was reported as
     * having lost nothing. The jitter is the scheduler being a scheduler; only the
     * arithmetic was wrong.
     */
    private fun minutesBetween(deltaMs: Long): Int =
        ((deltaMs.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()
}
