package com.example.motorcycleantitheftsensor.protection

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puts black box rows on disk, one file per day, append only.
 *
 * Everything here is deliberately dull. It opens the file, writes one line, closes it — no
 * buffer held across calls, no rewrite of a file that already exists, no temp-and-rename.
 * At one row a minute that costs about fourteen hundred opens a day, which is nothing, and
 * it buys the only property that matters for this file: what has been written is on disk
 * before the next row is even considered, so a kill takes the coming row and never a past one.
 *
 * `FileIncidentRepository` writes its whole file each time through a temp and a rename, which
 * is right for a state file and wrong for a log — a log rewritten every minute is a file
 * overwriting itself a thousand times a day, and the one moment it is mid-rename is the one
 * moment the phone is most likely to be killed.
 *
 * The caller is expected to be the black box's own writer thread. Nothing here may run on the
 * sensor thread: that thread is shared with the detection logic, and a thread blocked on the
 * disk is a phone that is not watching.
 */
class BlackBoxWriter(
    private val directory: File,
    private val header: BlackBoxHeader,
    private val wallClockMs: () -> Long,
    private val retentionDays: Int = RETENTION_DAYS,
    private val maxDirectoryBytes: Long = MAX_DIRECTORY_BYTES,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {

    private val dayStampFormat = SimpleDateFormat(DAY_STAMP_PATTERN, Locale.US)

    private var openDayStamp: String? = null
    private var currentFile: File? = null
    private var currentBytes: Long = 0L
    private var dayIsFull = false

    /** Rows that could not be written. Non-zero means the record has holes that are not kills. */
    @Volatile
    var failureCount: Int = 0
        private set

    @Synchronized
    fun append(row: BlackBoxRow): Boolean {
        val line = BlackBoxCsv.format(row) + "\n"
        return try {
            val target = fileForToday() ?: return false
            val bytes = line.toByteArray(Charsets.UTF_8)
            // The day's own ceiling, checked here rather than at the daily prune: a prune that
            // only runs at midnight cannot stop a runaway that starts at noon.
            if (currentBytes + bytes.size > maxFileBytes) {
                if (!dayIsFull) {
                    dayIsFull = true
                    writeBytes(target, "# capped bytes=$currentBytes\n".toByteArray(Charsets.UTF_8))
                }
                return false
            }
            writeBytes(target, bytes)
            currentBytes += bytes.size
            true
        } catch (_: Exception) {
            // A black box that takes the service down with it when the disk is full has made
            // the very outage it exists to record.
            failureCount += 1
            false
        }
    }

    /** Day files present, oldest first. The export of B3 reads this; nothing else needs it. */
    @Synchronized
    fun files(): List<File> = dayFiles()

    private fun fileForToday(): File? {
        val stamp = dayStampFormat.format(Date(wallClockMs()))
        val existing = currentFile
        if (stamp == openDayStamp && existing != null) return existing

        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return null
        val target = File(directory, "blackbox-$stamp.csv")
        val isNew = !target.exists() || target.length() == 0L
        if (isNew) {
            writeBytes(target, BlackBoxCsv.header(header).toByteArray(Charsets.UTF_8))
        }
        openDayStamp = stamp
        currentFile = target
        currentBytes = target.length()
        dayIsFull = false
        // Deleting on the day roll rather than on a timer keeps every deletion at a moment
        // that can be reasoned about, and never in the middle of recording an incident.
        prune(todayStamp = stamp)
        return target
    }

    private fun writeBytes(target: File, bytes: ByteArray) {
        FileOutputStream(target, true).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
    }

    private fun prune(todayStamp: String) {
        val cutoff = dayStampFormat.format(Date(wallClockMs() - retentionDays * MS_PER_DAY))
        val survivors = dayFiles().filter { file ->
            val stamp = stampOf(file)
            val expired = stamp != null && stamp < cutoff && stamp != todayStamp
            if (expired) file.delete()
            !expired
        }
        // Age first, then size. Both are needed: sixty quiet days fit inside the ceiling, but
        // a day that misbehaves must not be allowed to fill the phone before midnight.
        var total = survivors.sumOf { file -> file.length() }
        for (file in survivors) {
            if (total <= maxDirectoryBytes) break
            if (stampOf(file) == todayStamp) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun dayFiles(): List<File> =
        directory.listFiles()
            ?.filter { file -> file.isFile && stampOf(file) != null }
            ?.sortedBy { file -> file.name }
            ?: emptyList()

    private fun stampOf(file: File): String? {
        val name = file.name
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val stamp = name.substring(FILE_PREFIX.length, name.length - FILE_SUFFIX.length)
        return if (stamp.length == DAY_STAMP_PATTERN.length && stamp.all(Char::isDigit)) stamp else null
    }

    companion object {
        const val DIRECTORY = "blackbox"

        /** Sixty days of minute rows is about four megabytes; the ceiling leaves room over it. */
        const val RETENTION_DAYS = 60
        const val MAX_DIRECTORY_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_BYTES = 1L * 1024L * 1024L

        private const val DAY_STAMP_PATTERN = "yyyyMMdd"
        private const val FILE_PREFIX = "blackbox-"
        private const val FILE_SUFFIX = ".csv"
        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
