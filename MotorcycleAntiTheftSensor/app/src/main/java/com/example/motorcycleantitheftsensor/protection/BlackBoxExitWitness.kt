package com.example.motorcycleantitheftsensor.protection

/**
 * One process death as the system reported it, with nothing Android-shaped left in it.
 *
 * [reasonCode] is kept as the raw integer rather than a mapped name so that a constant this
 * build has never heard of still lands in the file as a number someone can look up, instead
 * of being flattened into "other".
 */
data class BlackBoxExitRecord(
    val timestampMs: Long,
    val reasonCode: Int,
    val importance: Int,
    val pssKb: Long,
    val description: String?,
    val stateSummary: ByteArray?,
) {
    // Data class equality on a ByteArray compares references, which would make two identical
    // records unequal and is the kind of thing that quietly breaks a de-duplicating reader.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is BlackBoxExitRecord &&
                timestampMs == other.timestampMs &&
                reasonCode == other.reasonCode &&
                importance == other.importance &&
                pssKb == other.pssKb &&
                description == other.description &&
                stateSummary.contentEquals(other.stateSummary)
            )

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + reasonCode
        result = 31 * result + importance
        result = 31 * result + pssKb.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + stateSummary.contentHashCode()
        return result
    }
}

/** Remembers how far the record has already been told, so a restart does not retell it. */
interface BlackBoxExitMarkStore {
    fun lastRecordedExitMs(): Long

    fun record(timestampMs: Long)
}

/**
 * Turns what the system knows about our deaths into rows in our own file.
 *
 * Until now the record could only imply a kill: a run of minutes with no row, bracketed by
 * rows on either side and no `stop` between them. That inference is sound and it is all the
 * file had, but it cannot say *why*, and "why" is the difference between a phone that ran out
 * of memory and a vendor power manager deciding the guard did not deserve to run. Android has
 * kept that answer since API 30 and this app had never asked for it.
 *
 * It does not replace the gap. The gap works on every version and every vendor; this works on
 * API 30 and up, and on some vendors reports little more than "signalled". They are two
 * witnesses to the same event, and the file is better for holding both.
 *
 * Rows are written pointing backwards in time — an `X` row's `wallMs` is the moment of a death
 * that happened before this process existed, so it sits in the file after rows that are newer
 * than it. That is inherent to a retrospective record and is left as is: a reader sorting by
 * time gets the truth, and a reader scanning downward finds the explanation next to the
 * `start` row that follows the gap it explains.
 */
class BlackBoxExitWitness(
    private val writer: BlackBoxWriter,
    private val source: () -> List<BlackBoxExitRecord>,
    private val markStore: BlackBoxExitMarkStore,
    private val elapsedMs: () -> Long,
    /**
     * This boot, so the row can say whether the dead run shared it. A death inside our own
     * boot is something that happened to us; a death on the other side of a boot boundary may
     * be nothing more than the phone being switched off, and the two are worth telling apart
     * before anyone concludes a vendor is killing the guard.
     */
    private val currentBootIdHash: Int,
) {

    /** Returns how many deaths were newly described. */
    fun recordNewExits(): Int {
        val lastRecorded = markStore.lastRecordedExitMs()
        val fresh = runCatching { source() }.getOrNull().orEmpty()
            .filter { record -> record.timestampMs > lastRecorded }
            .sortedBy { record -> record.timestampMs }
        if (fresh.isEmpty()) return 0

        var written = 0
        fresh.forEach { record ->
            if (writer.append(rowFor(record))) written += 1
        }
        // Marked even for rows that failed to land. Retrying them next start would compete
        // with whatever made the write fail, and the `writeFails` column already says rows
        // were lost there.
        markStore.record(fresh.last().timestampMs)
        return written
    }

    private fun rowFor(record: BlackBoxExitRecord): BlackBoxRow {
        val summary = BlackBoxProcessStateSummary.decode(record.stateSummary)
        return BlackBoxRow(
            type = BlackBoxRowType.EXIT,
            // The dead process's own elapsed clock when it last spoke, so the distance to the
            // death timestamp shows how long it went unheard before it went.
            elapsedMs = summary?.elapsedMs ?: elapsedMs(),
            wallMs = record.timestampMs,
            state = summary?.state ?: BlackBoxState.UNKNOWN,
            note = noteFor(record, summary),
        )
    }

    private fun noteFor(
        record: BlackBoxExitRecord,
        summary: BlackBoxProcessStateSummary.Decoded?,
    ): String = buildString {
        append("exit:").append(reasonName(record.reasonCode))
        append(":imp=").append(record.importance)
        if (record.pssKb > 0L) append(":pss=").append(record.pssKb)
        when {
            summary == null -> append(":nostate")
            summary.bootIdHash == currentBootIdHash -> append(":samboot")
            else -> append(":reboot")
        }
        record.description?.takeIf { text -> text.isNotBlank() }?.let { text ->
            append(':').append(text.take(DESCRIPTION_LIMIT))
        }
    }

    companion object {
        /** Long enough for a vendor to name itself, short enough not to reshape the row. */
        const val DESCRIPTION_LIMIT = 48

        /**
         * Mapped from the raw integer rather than the platform constants: the newer ones do
         * not exist on every SDK this app compiles against a device for, and an unknown code
         * is more useful printed than swallowed.
         */
        fun reasonName(code: Int): String = when (code) {
            0 -> "UNKNOWN"
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INIT_FAILURE"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE_USAGE"
            10 -> "USER_REQUESTED"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER"
            14 -> "FREEZER"
            15 -> "PACKAGE_STATE_CHANGE"
            16 -> "PACKAGE_UPDATED"
            else -> "CODE_$code"
        }
    }
}
