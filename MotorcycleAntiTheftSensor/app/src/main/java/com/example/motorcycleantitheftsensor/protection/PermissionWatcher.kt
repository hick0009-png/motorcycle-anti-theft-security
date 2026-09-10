package com.example.motorcycleantitheftsensor.protection

/**
 * States the permissions the app is holding, then reports only what changes.
 *
 * The baseline row is the point, and it is not obvious. Recording revocations alone leaves two
 * very different situations looking identical in the file — a permission that was held and
 * taken away before the day's file began, and one that was never granted since install. Both
 * read as "no `perm` rows", both present as "it cannot find my bike", and they have different
 * causes and different fixes. One line at the start of every run removes the ambiguity for
 * good, and a run already writes a `start` row, so there is always a recent one.
 *
 * Recording every grant instead would cost five to eight rows in the two minutes an owner
 * spends in first-time setup — more than the domain's whole hourly reservation — to say
 * something the baseline says once.
 *
 * Android does not announce a revocation to the app it took it from; in most cases it stops
 * the process instead. So this polls, on a cadence far slower than anything else here: the
 * question is which day a permission disappeared, never which second.
 */
class PermissionWatcher(
    /** Answers whether the app holds one capability right now. */
    private val holds: (BreadcrumbDetail) -> Boolean,
    private val onBaseline: (List<BreadcrumbDetail>) -> Unit,
    private val onChanged: (BreadcrumbEvent, BreadcrumbDetail) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {

    private var known: Map<BreadcrumbDetail, Boolean>? = null
    private var lastPolledAtMs: Long? = null

    /**
     * Reads every watched permission and writes the baseline row.
     *
     * The list is what the app holds, not what it wants. An empty list is a real answer and
     * still gets its row — "this run held nothing" is exactly the kind of thing the file is
     * asked about later.
     */
    fun baseline(nowMs: Long) {
        val snapshot = WATCHED.associateWith { permission -> reads(permission) }
        known = snapshot
        lastPolledAtMs = nowMs
        runCatching { onBaseline(snapshot.filterValues { it }.keys.toList()) }
    }

    /**
     * Reports anything that changed since the last look, if it is time to look again.
     *
     * Safe to call as often as the caller likes; the interval is held here rather than at the
     * call site so that a second caller cannot quietly turn this into a per-second poll.
     */
    fun poll(nowMs: Long) {
        val previous = known ?: return
        val last = lastPolledAtMs
        if (last != null && nowMs - last < pollIntervalMs) return
        lastPolledAtMs = nowMs

        val updated = previous.toMutableMap()
        previous.forEach { (permission, had) ->
            val has = reads(permission)
            if (has == had) return@forEach
            updated[permission] = has
            val event = if (has) BreadcrumbEvent.GRANTED else BreadcrumbEvent.REVOKED
            runCatching { onChanged(event, permission) }
        }
        known = updated
    }

    /** A permission check that throws is a permission the app cannot prove it has. */
    private fun reads(permission: BreadcrumbDetail): Boolean =
        runCatching { holds(permission) }.getOrDefault(false)

    companion object {
        /**
         * The capabilities whose absence an owner would notice as the app "not working".
         *
         * Battery optimisation is not a permission and belongs here anyway: being subject to
         * it is indistinguishable, from the outside, from the app having been uninstalled.
         */
        val WATCHED = listOf(
            BreadcrumbDetail.PERM_LOCATION,
            BreadcrumbDetail.PERM_MICROPHONE,
            BreadcrumbDetail.PERM_NOTIFICATION,
            BreadcrumbDetail.PERM_SMS,
            BreadcrumbDetail.PERM_BATTERY_UNRESTRICTED,
        )

        /** Slow on purpose. Which day it went is the question; which second never is. */
        const val POLL_INTERVAL_MS = 15L * 60_000L
    }
}
