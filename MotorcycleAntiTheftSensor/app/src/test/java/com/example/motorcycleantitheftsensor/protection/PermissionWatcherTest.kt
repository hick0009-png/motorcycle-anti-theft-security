package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission baseline, and why it is a baseline rather than a stream of grants.
 *
 * The file is read months later by somebody who was not there. These pin the two things that
 * would make it read wrongly: a run whose held permissions were never stated, and a poll fast
 * enough to spend the domain's whole allowance on a question measured in days.
 */
class PermissionWatcherTest {

    private val held = mutableSetOf<BreadcrumbDetail>()
    private val baselines = mutableListOf<List<BreadcrumbDetail>>()
    private val changes = mutableListOf<Pair<BreadcrumbEvent, BreadcrumbDetail>>()

    private fun watcher() = PermissionWatcher(
        holds = { permission -> permission in held },
        onBaseline = { baselines += it },
        onChanged = { event, permission -> changes += event to permission },
    )

    @Test
    fun theBaselineSaysWhatThisRunIsHolding() {
        held += BreadcrumbDetail.PERM_LOCATION
        held += BreadcrumbDetail.PERM_NOTIFICATION

        watcher().baseline(0L)

        assertEquals(
            listOf(listOf(BreadcrumbDetail.PERM_LOCATION, BreadcrumbDetail.PERM_NOTIFICATION)),
            baselines,
        )
    }

    @Test
    fun aRunHoldingNothingStillSaysSo() {
        // The case the baseline exists for. Without a row, "never granted since install" and
        // "granted, then revoked before this file began" are the same absence — and an owner
        // reporting "it cannot find my bike" is in one of them.
        watcher().baseline(0L)

        assertEquals(listOf(emptyList<BreadcrumbDetail>()), baselines)
    }

    @Test
    fun onlyChangesAfterTheBaselineCostARow() {
        held += BreadcrumbDetail.PERM_LOCATION
        val watcher = watcher()
        watcher.baseline(0L)

        watcher.poll(PermissionWatcher.POLL_INTERVAL_MS)
        held -= BreadcrumbDetail.PERM_LOCATION
        watcher.poll(2 * PermissionWatcher.POLL_INTERVAL_MS)
        watcher.poll(3 * PermissionWatcher.POLL_INTERVAL_MS)

        assertEquals(
            listOf(BreadcrumbEvent.REVOKED to BreadcrumbDetail.PERM_LOCATION),
            changes,
        )
    }

    @Test
    fun aPermissionComingBackIsReportedToo() {
        val watcher = watcher()
        watcher.baseline(0L)
        held += BreadcrumbDetail.PERM_MICROPHONE

        watcher.poll(PermissionWatcher.POLL_INTERVAL_MS)

        assertEquals(
            listOf(BreadcrumbEvent.GRANTED to BreadcrumbDetail.PERM_MICROPHONE),
            changes,
        )
    }

    @Test
    fun pollingFasterThanTheIntervalDoesNothing() {
        // The interval lives here rather than at the call site, so a second caller cannot turn
        // the service's five second heartbeat into a five second permission poll.
        val watcher = watcher()
        watcher.baseline(0L)
        held += BreadcrumbDetail.PERM_SMS

        repeat(100) { tick -> watcher.poll(tick * 1_000L) }

        assertTrue(changes.isEmpty())
    }

    @Test
    fun aPermissionCheckThatThrowsIsNotAPermissionHeld() {
        val watcher = PermissionWatcher(
            holds = { throw SecurityException("no such package") },
            onBaseline = { baselines += it },
            onChanged = { event, permission -> changes += event to permission },
        )

        watcher.baseline(0L)

        assertEquals(listOf(emptyList<BreadcrumbDetail>()), baselines)
    }

    @Test
    fun pollingBeforeTheBaselineReportsNothing() {
        // There is nothing to compare against, and inventing one would report every held
        // permission as newly granted.
        watcher().poll(PermissionWatcher.POLL_INTERVAL_MS)

        assertTrue(changes.isEmpty())
    }
}
