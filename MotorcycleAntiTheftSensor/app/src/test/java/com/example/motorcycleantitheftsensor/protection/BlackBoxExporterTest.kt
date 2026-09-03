package com.example.motorcycleantitheftsensor.protection

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlackBoxExporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var nowMs = 1_756_800_000_000L

    @Test
    fun anExportTakenWhileTheRecorderIsWritingContainsNoHalfRow() {
        val writer = writer()
        val exporter = exporter(writer)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(1)

        // One thread appends for as long as the other keeps exporting. A copy that did not
        // share the recorder's lock would eventually catch a row mid-write, and a torn row in
        // the middle of a file — unlike the torn last row of a killed process — sits between
        // two intact ones and reads as data.
        val recorder = Thread {
            start.countDown()
            repeat(400) { index -> writer.append(row(index.toLong())) }
            finished.countDown()
        }
        recorder.start()
        start.await(5, TimeUnit.SECONDS)

        var exports = 0
        while (finished.count > 0L && exports < 40) {
            nowMs += 1_000L
            val result = exporter.export()
            if (result is BlackBoxExportResult.Ready) {
                assertNoTornRows(result.file)
                exports += 1
            }
        }
        recorder.join(10_000L)

        assertTrue("expected at least one export during the write", exports > 0)
        val last = exporter.export() as BlackBoxExportResult.Ready
        assertEquals(400, readRows(last.file).size)
    }

    @Test
    fun theExportHoldsEveryDayNotJustToday() {
        val writer = writer()
        writer.append(row(0L))
        nowMs += DAY_MS
        writer.append(row(1L))
        nowMs += DAY_MS
        writer.append(row(2L))

        val result = exporter(writer).export() as BlackBoxExportResult.Ready

        assertEquals(3, result.dayCount)
        assertEquals(3, readRows(result.file).size)
        assertTrue(result.file.name.endsWith(".csv.gz"))
    }

    @Test
    fun compressionHappensOnlyOnTheWayOutSoTheRecordItselfStaysReadable() {
        val writer = writer()
        writer.append(row(0L))

        val onDisk = writer.files().single().readText()
        assertTrue(onDisk.startsWith("# blackbox v1"))
        assertTrue(onDisk.contains(BlackBoxCsv.COLUMN_HEADER))
    }

    @Test
    fun nothingRecordedYetIsSaidPlainlyRatherThanHandingOverAnEmptyFile() {
        val result = exporter(writer()).export()

        assertEquals(BlackBoxExportResult.Empty, result)
        assertTrue(exportDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun onlyTheNewestExportIsKeptAround() {
        val writer = writer()
        writer.append(row(0L))
        val exporter = exporter(writer)

        val first = exporter.export() as BlackBoxExportResult.Ready
        nowMs += 60_000L
        val second = exporter.export() as BlackBoxExportResult.Ready

        // A folder quietly accumulating a compressed copy per press is a second store of the
        // same record that nobody remembers is there.
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
        assertEquals(listOf(second.file.name), exportDirectory().listFiles().orEmpty().map { it.name })
    }

    @Test
    fun anUnusableDestinationIsReportedRatherThanThrown() {
        val writer = writer()
        writer.append(row(0L))
        val blocked = temporaryFolder.newFile("export-blocked")

        val result = BlackBoxExporter(writer, blocked, { nowMs }).export()

        assertTrue(result is BlackBoxExportResult.Failed)
    }

    private fun assertNoTornRows(archive: File) {
        val text = GZIPInputStream(archive.inputStream()).bufferedReader().readText()
        text.split('\n').forEach { line ->
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("type,")) return@forEach
            assertTrue("torn row inside an export: $line", BlackBoxCsv.parse(line) != null)
        }
    }

    private fun readRows(archive: File): List<BlackBoxRow> =
        GZIPInputStream(archive.inputStream()).bufferedReader().readText()
            .split('\n')
            .mapNotNull(BlackBoxCsv::parse)

    private fun exportDirectory(): File = File(temporaryFolder.root, "export")

    private fun exporter(writer: BlackBoxWriter) =
        BlackBoxExporter(writer, exportDirectory(), { nowMs })

    private fun writer() = BlackBoxWriter(
        directory = File(temporaryFolder.root, "blackbox"),
        header = BlackBoxHeader(
            device = "Huawei/INE-LX2",
            androidSdk = 28,
            appVersion = "1.0",
            bootId = "boot-1",
            wallAnchorMs = nowMs,
            elapsedAtAnchorMs = 1_000L,
            sensors = "ACCELEROMETER:lsm6ds3:st:0.001",
        ),
        wallClockMs = { nowMs },
    )

    private fun row(index: Long) = BlackBoxRow(
        type = BlackBoxRowType.MINUTE,
        elapsedMs = index * 60_000L,
        wallMs = nowMs,
        state = BlackBoxState(
            armed = true,
            mode = "ENTRY",
            sourceMask = 7,
            incidents = 0,
            batteryPercent = 84,
            charging = false,
        ),
        sensors = BlackBoxSensorSummary(samples = 2_950, accelerationMaxG = 1.004, lux = 0.5),
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
