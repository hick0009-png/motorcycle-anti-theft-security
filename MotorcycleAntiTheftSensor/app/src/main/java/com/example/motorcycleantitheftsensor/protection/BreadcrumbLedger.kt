package com.example.motorcycleantitheftsensor.protection

import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides which breadcrumbs become rows, and says out loud what it refused.
 *
 * All of the layer's judgement lives here and none of its plumbing does: it takes crumbs and
 * returns the notes to write, so every rule below is testable without a disk, a clock or a
 * thread. [BlackBoxRecorder] does the writing.
 *
 * Three rules, and each exists because of a way the layer could quietly lie:
 *
 * A **reservation per domain** stops the loudest subsystem silencing the most important one.
 * **Coalescing** stops a flap costing one row per occurrence — and keeps the count, because
 * how often a network dropped is the symptom itself and plain rate limiting throws it away.
 * An **hourly ceiling** stops the layer that reports trouble from being able to fill the
 * day's file and silence the record it belongs to.
 *
 * What it refuses is never silent. Every suppressed crumb is counted against its domain and
 * reported when the hour turns, so a gap in this layer can always be told apart from quiet.
 */
class BreadcrumbLedger(
    private val hourMs: Long = HOUR_MS,
    private val coalesceWindowMs: Long = COALESCE_WINDOW_MS,
    private val sharedPerHour: Int = BreadcrumbDomain.SHARED_PER_HOUR,
) {

    private var hourStartedAtMs: Long? = null
    private val usedByDomain = mutableMapOf<BreadcrumbDomain, Int>()
    private var sharedUsed = 0
    private val suppressedByDomain = mutableMapOf<BreadcrumbDomain, Int>()

    /** Written to from whatever thread lost the crumb, read when the hour turns. */
    private val queueDrops = AtomicInteger(0)

    // The crumb currently being repeated, if any.
    private var openNote: String? = null
    private var openDomain: BreadcrumbDomain? = null
    private var openedAtMs = 0L
    private var repeats = 0

    /**
     * Takes one crumb and returns the notes to write for it, in order.
     *
     * The first of a kind is written straight away rather than held for the window to close.
     * Holding it would put every one-off crumb — a revoked permission, a failed send — a
     * minute behind the event, in a process that is killed without warning by design. The
     * window counts repeats only, and repeats are the part that can afford to wait.
     */
    fun offer(crumb: Breadcrumb): List<String> {
        val notes = mutableListOf<String>()
        notes += rollHourIfDue(crumb.elapsedMs)

        val note = crumb.note
        if (note == openNote && crumb.elapsedMs - openedAtMs < coalesceWindowMs) {
            repeats += 1
            return notes
        }

        notes += closeWindow()
        if (take(crumb.domain)) {
            notes += note
            openNote = note
            openDomain = crumb.domain
            openedAtMs = crumb.elapsedMs
            repeats = 0
        } else {
            suppress(crumb.domain)
            // Nothing was written, so there is nothing for a later repeat to be counted
            // against; the suppression count is what carries those.
            openNote = null
            openDomain = null
            repeats = 0
        }
        return notes
    }

    /**
     * Advances the clock without a crumb — called from the minute tick.
     *
     * Without it a flap that stops dead leaves its last repeats unwritten until the next
     * crumb of any kind arrives, which on a quiet phone could be hours.
     */
    fun tick(nowMs: Long): List<String> {
        val notes = mutableListOf<String>()
        notes += rollHourIfDue(nowMs)
        if (openNote != null && nowMs - openedAtMs >= coalesceWindowMs) {
            notes += closeWindow()
            openNote = null
            openDomain = null
        }
        return notes
    }

    /** A crumb that never got here. Safe to call from any thread. */
    fun recordQueueDrop() {
        queueDrops.incrementAndGet()
    }

    /**
     * Emits the repeat summary, if the window that is closing held any.
     *
     * Budgeted like any other row. A summary a minute is sixty rows an hour from a single
     * domain, which is the whole allowance — exempting it would reintroduce, through the
     * mechanism meant to prevent flooding, exactly the flood it prevents.
     */
    private fun closeWindow(): List<String> {
        val note = openNote ?: return emptyList()
        val domain = openDomain ?: return emptyList()
        if (repeats <= 0) return emptyList()
        val summary = Breadcrumb.repeatNote(note, repeats)
        repeats = 0
        return if (take(domain)) {
            listOf(summary)
        } else {
            suppress(domain)
            emptyList()
        }
    }

    /**
     * Reports the hour that just ended, then starts a fresh one.
     *
     * The cap rows are written here rather than at the moment of capping, for the reason the
     * `T` row's ceiling already found: at the moment of capping there is by definition no
     * allowance left to pay for the row that would announce it, and by the time the hour
     * turns the number is final. They bypass the ceiling themselves — a limit that could
     * suppress the report of the limit would be indistinguishable from no limit at all.
     */
    private fun rollHourIfDue(nowMs: Long): List<String> {
        val startedAt = hourStartedAtMs
        if (startedAt == null) {
            hourStartedAtMs = nowMs
            return emptyList()
        }
        if (nowMs - startedAt < hourMs) return emptyList()

        val notes = mutableListOf<String>()
        // Enum order, so two runs of the same hour produce the same file.
        BreadcrumbDomain.entries.forEach { domain ->
            val suppressed = suppressedByDomain[domain] ?: 0
            if (suppressed > 0) notes += Breadcrumb.capNote(domain, suppressed)
        }
        val dropped = queueDrops.getAndSet(0)
        if (dropped > 0) notes += Breadcrumb.queueDropNote(dropped)

        usedByDomain.clear()
        suppressedByDomain.clear()
        sharedUsed = 0
        hourStartedAtMs = nowMs
        return notes
    }

    /** Own reservation first, then the shared pool. */
    private fun take(domain: BreadcrumbDomain): Boolean {
        val used = usedByDomain[domain] ?: 0
        if (used < domain.reservedPerHour) {
            usedByDomain[domain] = used + 1
            return true
        }
        if (sharedUsed < sharedPerHour) {
            sharedUsed += 1
            usedByDomain[domain] = used + 1
            return true
        }
        return false
    }

    private fun suppress(domain: BreadcrumbDomain) {
        suppressedByDomain[domain] = (suppressedByDomain[domain] ?: 0) + 1
    }

    companion object {
        private const val HOUR_MS = 60L * 60L * 1000L

        /**
         * Matches the minute row's cadence on purpose: repeats wait in memory, and memory
         * dies with the process, so the longest a crumb can be lost for is the same interval
         * everything else in the file can be lost for. A five minute window would save about
         * 0.7% of the layer's disk budget and buy it with rows that vanish in the minutes the
         * phone is most likely to be killed.
         */
        private const val COALESCE_WINDOW_MS = 60_000L
    }
}
