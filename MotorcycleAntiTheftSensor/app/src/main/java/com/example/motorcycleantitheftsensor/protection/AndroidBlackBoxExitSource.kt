package com.example.motorcycleantitheftsensor.protection

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The only place in the app that talks to `ActivityManager` about our own deaths.
 *
 * Everything above it is ordinary Kotlin that can be tested without a device, which matters
 * more than usual here: the machinery exists for the case where the app was killed, and that
 * case cannot be produced on demand in a unit test — so the parts that decide what gets
 * written have to be reachable without producing it.
 *
 * API 30 and up. Below that every call is a no-op that returns nothing, and the file falls
 * back to what it always had: the shape of the gap.
 */
object AndroidBlackBoxExitSource {

    private const val TAG = "BlackBoxExit"

    /** More than the platform keeps, so the window is the system's to decide and not ours. */
    private const val MAX_RECORDS = 16

    fun readExits(context: Context): List<BlackBoxExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        return try {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .map { info ->
                    BlackBoxExitRecord(
                        timestampMs = info.timestamp,
                        reasonCode = info.reason,
                        importance = info.importance,
                        pssKb = info.pss,
                        description = info.description,
                        stateSummary = info.processStateSummary,
                    )
                }
        } catch (error: Exception) {
            // A witness that takes the service down when it cannot testify is worse than a
            // missing witness.
            Log.w(TAG, "Could not read process exit reasons", error)
            emptyList()
        }
    }

    /**
     * Publishes the dying message. Cheap enough to call every minute, which is how it is
     * called — the copy that survives is whichever one the kill happens to catch.
     */
    fun publishStateSummary(context: Context, summary: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        try {
            manager.setProcessStateSummary(summary)
        } catch (error: Exception) {
            Log.w(TAG, "Could not publish process state summary", error)
        }
    }
}

/**
 * The high-water mark, in a file of its own.
 *
 * It lives outside the black box directory on purpose: everything in there is a day file that
 * the prune walks and the size ceiling counts, and a marker swept up by either of those would
 * make the app retell deaths it had already described.
 */
class FileBlackBoxExitMarkStore(private val file: File) : BlackBoxExitMarkStore {

    override fun lastRecordedExitMs(): Long =
        runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    override fun record(timestampMs: Long) {
        runCatching { file.writeText(timestampMs.toString()) }
    }
}
