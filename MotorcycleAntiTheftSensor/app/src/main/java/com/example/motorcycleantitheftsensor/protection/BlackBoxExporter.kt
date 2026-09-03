package com.example.motorcycleantitheftsensor.protection

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/** What an export attempt produced, or why it produced nothing. */
sealed interface BlackBoxExportResult {
    data class Ready(val file: File, val dayCount: Int, val bytes: Long) : BlackBoxExportResult
    /** The recorder has run but has not written a day file yet, or every one was pruned. */
    data object Empty : BlackBoxExportResult
    data class Failed(val reason: String) : BlackBoxExportResult
}

/**
 * Makes one file out of the record, for a phone the owner is holding.
 *
 * The record itself lives in private storage, where no other app can read it and — on any
 * phone that is not rooted, which is every customer's phone — `adb pull` cannot reach it
 * either. That is the right place for it to sit and the wrong place for it to stay when
 * somebody actually needs to look: getting it out has to be something the owner does on
 * purpose, from inside the app, and not a file left lying in a public folder for the life
 * of the install.
 *
 * Compression happens here and nowhere else. Compressing while recording would mean a
 * partial deflate stream at the moment the app is killed, which is the moment the record
 * matters most; text lines cost a little more disk and survive it.
 */
class BlackBoxExporter(
    private val writer: BlackBoxWriter,
    private val directory: File,
    private val wallClockMs: () -> Long,
) {

    private val stampFormat = SimpleDateFormat(STAMP_PATTERN, Locale.US)

    fun export(): BlackBoxExportResult {
        if (writer.files().isEmpty()) return BlackBoxExportResult.Empty
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            return BlackBoxExportResult.Failed("no export directory")
        }
        // Only the newest export is kept. These are copies of something that still exists in
        // full, and a folder quietly growing a compressed copy of the record every time the
        // button is pressed is a second store nobody remembers is there.
        clearPreviousExports()

        val target = File(directory, "blackbox-${stampFormat.format(Date(wallClockMs()))}.csv.gz")
        val days = try {
            FileOutputStream(target).use { file ->
                GZIPOutputStream(file).use { gzip -> writer.snapshot(gzip) }
            }
        } catch (error: Exception) {
            target.delete()
            return BlackBoxExportResult.Failed(error.javaClass.simpleName)
        }
        if (days == 0) {
            target.delete()
            return BlackBoxExportResult.Empty
        }
        return BlackBoxExportResult.Ready(file = target, dayCount = days, bytes = target.length())
    }

    private fun clearPreviousExports() {
        directory.listFiles()
            ?.filter { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?.forEach { file -> file.delete() }
    }

    companion object {
        const val DIRECTORY = "blackbox-export"

        private const val PREFIX = "blackbox-"
        private const val SUFFIX = ".csv.gz"
        private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
    }
}
