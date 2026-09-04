package com.example.motorcycleantitheftsensor.protection

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlackBoxWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)
    private var nowMs = 1_756_800_000_000L

    @Test
    fun rowsSurviveTheTornLastLineOfAProcessThatWasKilled() {
        val directory = temporaryFolder.newFolder("blackbox")
        val writer = writer(directory)
        repeat(5) { index -> writer.append(row(elapsedMs = index * 60_000L)) }

        val file = File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv")
        RandomAccessFile(file, "rw").use { handle -> handle.setLength(file.length() - 12L) }

        val rows = BlackBoxCsv.readRows(file)
        assertEquals(4, rows.size)
        assertEquals(0L, rows.first().elapsedMs)
        assertEquals(180_000L, rows.last().elapsedMs)
    }

    @Test
    fun eachDayOpensItsOwnFileWithItsOwnHeader() {
        val directory = temporaryFolder.newFolder("blackbox")
        val writer = writer(directory)
        writer.append(row(elapsedMs = 0L))
        val firstDay = dayStamp.format(Date(nowMs))
        nowMs += DAY_MS
        writer.append(row(elapsedMs = DAY_MS))
        val secondDay = dayStamp.format(Date(nowMs))

        val files = directory.listFiles().orEmpty().map { file -> file.name }.sorted()
        assertEquals(listOf("blackbox-$firstDay.csv", "blackbox-$secondDay.csv"), files)
        val second = File(directory, "blackbox-$secondDay.csv").readText()
        assertTrue(second.startsWith("# blackbox v${BlackBoxCsv.VERSION}\n"))
        assertTrue(second.contains(BlackBoxCsv.COLUMN_HEADER))
        assertEquals(1, BlackBoxCsv.readRows(File(directory, "blackbox-$secondDay.csv")).size)
    }

    @Test
    fun reopeningTheSameDayAppendsInsteadOfStartingTheFileOver() {
        val directory = temporaryFolder.newFolder("blackbox")
        writer(directory).append(row(elapsedMs = 0L))
        writer(directory).append(row(elapsedMs = 60_000L))

        val file = File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv")
        assertEquals(2, BlackBoxCsv.readRows(file).size)
        assertEquals(1, file.readText().split("# blackbox v${BlackBoxCsv.VERSION}").size - 1)
    }

    @Test
    fun daysOlderThanTheRetentionWindowAreDeletedWhenTheNewDayOpens() {
        val directory = temporaryFolder.newFolder("blackbox")
        val expired = existingDay(directory, nowMs - 61L * DAY_MS, sizeBytes = 100)
        val kept = existingDay(directory, nowMs - 59L * DAY_MS, sizeBytes = 100)

        writer(directory).append(row(elapsedMs = 0L))

        assertFalse(expired.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun theOldestDaysGoWhenTheDirectoryCeilingWouldBeCrossed() {
        val directory = temporaryFolder.newFolder("blackbox")
        val oldest = existingDay(directory, nowMs - 5L * DAY_MS, sizeBytes = 400)
        val middle = existingDay(directory, nowMs - 4L * DAY_MS, sizeBytes = 400)
        val newest = existingDay(directory, nowMs - 3L * DAY_MS, sizeBytes = 400)

        writer(directory, maxDirectoryBytes = 900L).append(row(elapsedMs = 0L))

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
        assertTrue(File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv").exists())
    }

    @Test
    fun aDayThatKeepsWritingIsCappedInsteadOfFillingThePhone() {
        val directory = temporaryFolder.newFolder("blackbox")
        val writer = writer(directory, maxFileBytes = 900L)

        val accepted = (0 until 200).count { index -> writer.append(row(elapsedMs = index * 1_000L)) }

        val file = File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv")
        assertTrue(accepted in 1 until 200)
        assertTrue(file.length() <= 900L + CAP_MARKER_SLACK)
        assertEquals(accepted, BlackBoxCsv.readRows(file).size)
        assertEquals(0, writer.failureCount)
    }

    @Test
    fun aDirectoryThatCannotBeOpenedCostsTheRowAndNothingElse() {
        val blocked = temporaryFolder.newFile("blackbox")
        val writer = writer(blocked)

        assertFalse(writer.append(row(elapsedMs = 0L)))
        assertTrue(blocked.isFile)
    }

    private fun writer(
        directory: File,
        maxDirectoryBytes: Long = BlackBoxWriter.MAX_DIRECTORY_BYTES,
        maxFileBytes: Long = BlackBoxWriter.MAX_FILE_BYTES,
    ) = BlackBoxWriter(
        directory = directory,
        header = BlackBoxHeader(
            device = "Huawei/INE-LX2",
            androidSdk = 29,
            appVersion = "1.0",
            bootId = "boot-1",
            wallAnchorMs = nowMs,
            elapsedAtAnchorMs = 1_000L,
            sensors = "acc:LSM6DSL:ST:0.001",
        ),
        wallClockMs = { nowMs },
        maxDirectoryBytes = maxDirectoryBytes,
        maxFileBytes = maxFileBytes,
    )

    @Test
    fun theRowAfterFailedWritesSaysHowManyWereLost() {
        val directory = temporaryFolder.newFolder("blackbox")
        // A directory where the day file belongs: every write to it throws, which is what a
        // full disk or a revoked directory looks like from in here.
        val blocker = File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv")
        assertTrue(blocker.mkdir())

        val writer = writer(directory)
        repeat(3) { index -> assertFalse(writer.append(row(elapsedMs = index * 60_000L))) }
        assertEquals(3, writer.failureCount)

        assertTrue(blocker.delete())
        assertTrue(writer.append(row(elapsedMs = 180_000L)))

        val rows = BlackBoxCsv.readRows(File(directory, "blackbox-${dayStamp.format(Date(nowMs))}.csv"))
        assertEquals(1, rows.size)
        // Without this the three dropped rows are a gap, and a gap reads as a kill.
        assertEquals(3, rows.single().writeFailures)
    }

    @Test
    fun aRowWrittenBeforeTheColumnExistedStillReads() {
        val v1 = "M,60000,1756800060000,1,ENTRY,3,,,,,,,0,84,0,"
        val row = BlackBoxCsv.parse(v1)

        assertEquals(60_000L, row?.elapsedMs)
        assertEquals(0, row?.writeFailures)
    }

    private fun existingDay(directory: File, atMs: Long, sizeBytes: Int): File =
        File(directory, "blackbox-${dayStamp.format(Date(atMs))}.csv").apply {
            writeText("x".repeat(sizeBytes))
        }

    private fun row(elapsedMs: Long) = BlackBoxRow(
        type = BlackBoxRowType.MINUTE,
        elapsedMs = elapsedMs,
        wallMs = nowMs + elapsedMs,
        state = BlackBoxState(
            armed = true,
            mode = "ENTRY",
            sourceMask = 3,
            incidents = 0,
            batteryPercent = 84,
            charging = false,
        ),
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val CAP_MARKER_SLACK = 64L
    }
}
