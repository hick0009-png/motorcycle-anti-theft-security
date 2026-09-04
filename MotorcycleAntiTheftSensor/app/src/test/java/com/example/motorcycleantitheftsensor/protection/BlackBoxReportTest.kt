package com.example.motorcycleantitheftsensor.protection

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBoxReportTest {

    private val startWallMs = 1_756_800_000_000L

    @Test
    fun aStopRowBeforeTheGapMakesItAnOrdinaryShutdown() {
        val report = BlackBoxReportBuilder.build(
            listOf(
                minute(0),
                minute(1),
                state(1, note = BlackBoxRecorder.NOTE_STOP, armed = false),
                minute(40),
            ),
        )

        assertEquals(BlackBoxGapVerdict.CLEAN_SHUTDOWN, report.gaps.single().verdict)
    }

    @Test
    fun anExitRowInsideTheGapNamesWhatKilledIt() {
        val report = BlackBoxReportBuilder.build(
            listOf(
                minute(0),
                minute(1),
                minute(40),
                exit(minuteIndex = 20, note = "exit:EXCESSIVE_RESOURCE_USAGE:imp=125:samboot"),
            ),
        )

        val gap = report.gaps.single()
        assertEquals(BlackBoxGapVerdict.KILLED, gap.verdict)
        assertEquals(38, gap.minutes)
        assertTrue(gap.detail.contains("EXCESSIVE_RESOURCE_USAGE"))
    }

    @Test
    fun aRiseInWriteFailuresMakesTheGapDroppedRowsRatherThanADeath() {
        val report = BlackBoxReportBuilder.build(
            listOf(minute(0), minute(1), minute(40, writeFailures = 7)),
        )

        val gap = report.gaps.single()
        assertEquals(BlackBoxGapVerdict.DROPPED_ROWS, gap.verdict)
        assertTrue(gap.detail.contains("7"))
    }

    @Test
    fun aGapWithNoEvidenceEitherWayIsLeftAsAQuestion() {
        val report = BlackBoxReportBuilder.build(listOf(minute(0), minute(1), minute(40)))

        assertEquals(BlackBoxGapVerdict.UNEXPLAINED, report.gaps.single().verdict)
    }

    @Test
    fun anElapsedClockRunningBackwardsIsABootAndNotAHole() {
        // What a restart writes into an append-only file: the wall clock carries on, the
        // clock since boot starts over. Passed in file order, which is the order it happened.
        val report = BlackBoxReportBuilder.build(
            listOf(
                minute(10),
                minute(0).copy(wallMs = startWallMs + 11 * 60_000L),
                minute(1).copy(wallMs = startWallMs + 12 * 60_000L),
            ),
        )

        assertEquals(BlackBoxGapVerdict.REBOOT, report.gaps.single().verdict)
        // And the span is not made nonsense by the clock starting again.
        assertEquals(2, report.expectedMinuteRows)
    }

    @Test
    fun aWallClockThatMovesFurtherThanTheElapsedClockIsCalledOut() {
        val report = BlackBoxReportBuilder.build(
            listOf(
                minute(0),
                // One minute of uptime, but the wall clock jumped an hour.
                minute(1).copy(wallMs = startWallMs + 60_000L + 3_600_000L),
            ),
        )

        assertEquals(1, report.contradictions.size)
        assertTrue(report.contradictions.single().summary.contains("3600"))
    }

    @Test
    fun aMaskClaimingASensorThatNeverDeliveredANumberIsCalledOut() {
        val vibrationBit = 1 shl SensorKind.VIBRATION.ordinal
        val report = BlackBoxReportBuilder.build(
            (0..20).map { index ->
                minute(index, armed = true, sourceMask = vibrationBit or 2)
                    .copy(sensors = BlackBoxSensorSummary(samples = 504, lux = 900.0))
            },
        )

        assertTrue(report.contradictions.any { issue -> issue.summary.contains("srcMask") })
    }

    @Test
    fun aMaskIsBelievedWhenTheSensorActuallyDelivered() {
        val vibrationBit = 1 shl SensorKind.VIBRATION.ordinal
        val report = BlackBoxReportBuilder.build(
            (0..20).map { index ->
                minute(index, armed = true, sourceMask = vibrationBit)
                    .copy(sensors = BlackBoxSensorSummary(samples = 30_000, accelerationMaxG = 1.02))
            },
        )

        assertTrue(report.contradictions.none { issue -> issue.summary.contains("srcMask") })
    }

    @Test
    fun anArmedStretchIsSummarisedByModeDurationAndWhatItSaw() {
        val report = BlackBoxReportBuilder.build(
            listOf(
                minute(0),
                state(1, note = "arm", armed = true),
                minute(2, armed = true, mode = "VEHICLE")
                    .copy(sensors = BlackBoxSensorSummary(accelerationMaxG = 4.9, lux = 136.0)),
                minute(3, armed = true, mode = "VEHICLE", incidents = 2)
                    .copy(sensors = BlackBoxSensorSummary(accelerationMaxG = 2.1)),
                state(4, note = "disarm", armed = false, incidents = 2),
            ),
        )

        val session = report.sessions.single()
        assertEquals("VEHICLE", session.mode)
        assertEquals(3, session.minutes)
        assertEquals(2, session.incidents)
        assertEquals(4.9, session.accelerationMaxG!!, 0.001)
        assertEquals(false, session.stillOpen)
    }

    @Test
    fun aStretchStillArmedAtTheEndOfTheFileSaysSo() {
        val report = BlackBoxReportBuilder.build(
            listOf(minute(0), minute(1, armed = true, mode = "POWER"), minute(2, armed = true, mode = "POWER")),
        )

        assertTrue(report.sessions.single().stillOpen)
    }

    @Test
    fun theReportCannotChangeWhatAMachineReadsBackOutOfTheFile() {
        val rows = listOf(minute(0), minute(1), minute(40, writeFailures = 3))
        val body = rows.joinToString("\n", postfix = "\n", transform = BlackBoxCsv::format)
        val preamble = BlackBoxReportText.render(
            BlackBoxReportBuilder.build(rows),
            TimeZone.getTimeZone("Asia/Bangkok"),
        )

        // Every line a comment, and the rows underneath parse to exactly what went in.
        assertTrue(preamble.trim().lines().all { line -> line.startsWith("#") })
        assertEquals(rows, (preamble + body).split("\n").mapNotNull(BlackBoxCsv::parse))
    }

    @Test
    fun anEmptyRecordProducesAReportThatSaysSoRatherThanCrashing() {
        val report = BlackBoxReportBuilder.build(emptyList())
        val text = BlackBoxReportText.render(report, TimeZone.getTimeZone("Asia/Bangkok"))

        assertEquals(0, report.minuteRows)
        assertEquals(100, report.coveragePercent)
        assertTrue(text.contains("ไม่มีข้อมูลในไฟล์"))
    }

    @Test
    fun theRenderedReportNamesTheFindingsASupportCallNeeds() {
        val text = BlackBoxReportText.render(
            BlackBoxReportBuilder.build(
                listOf(
                    minute(0),
                    minute(1),
                    minute(40, armed = true, mode = "POWER"),
                    exit(minuteIndex = 20, note = "exit:SIGNALED:imp=125:samboot"),
                ),
            ),
            TimeZone.getTimeZone("Asia/Bangkok"),
        )

        assertTrue(text.contains("= ถูกฆ่า"))
        assertTrue(text.contains("SIGNALED"))
        assertTrue(text.contains("ยังอาร์มอยู่"))
        assertTrue(text.contains("ข้อมูลดิบเริ่มด้านล่างนี้"))
    }

    private fun minute(
        minuteIndex: Int,
        armed: Boolean = false,
        mode: String = BlackBoxState.MODE_NONE,
        sourceMask: Int = 0,
        incidents: Int = 0,
        writeFailures: Int = 0,
    ) = BlackBoxRow(
        type = BlackBoxRowType.MINUTE,
        elapsedMs = minuteIndex * 60_000L,
        wallMs = startWallMs + minuteIndex * 60_000L,
        state = state(armed, mode, sourceMask, incidents),
        writeFailures = writeFailures,
    )

    private fun state(
        minuteIndex: Int,
        note: String,
        armed: Boolean,
        incidents: Int = 0,
    ) = BlackBoxRow(
        type = BlackBoxRowType.STATE,
        elapsedMs = minuteIndex * 60_000L,
        wallMs = startWallMs + minuteIndex * 60_000L,
        state = state(armed, if (armed) "VEHICLE" else BlackBoxState.MODE_NONE, 0, incidents),
        note = note,
    )

    private fun exit(minuteIndex: Int, note: String) = BlackBoxRow(
        type = BlackBoxRowType.EXIT,
        elapsedMs = minuteIndex * 60_000L,
        wallMs = startWallMs + minuteIndex * 60_000L,
        state = BlackBoxState.UNKNOWN,
        note = note,
    )

    private fun state(armed: Boolean, mode: String, sourceMask: Int, incidents: Int) = BlackBoxState(
        armed = armed,
        mode = mode,
        sourceMask = sourceMask,
        incidents = incidents,
        batteryPercent = 80,
        charging = false,
    )
}
