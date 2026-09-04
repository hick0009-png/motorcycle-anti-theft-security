package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rationing rules of the breadcrumb layer.
 *
 * Every one of these pins a way the layer could quietly lie rather than a behaviour that is
 * merely nice: a domain silenced by a noisier one, a flap whose frequency was thrown away, a
 * suppression that left no trace, a ceiling able to suppress the report of itself.
 */
class BreadcrumbLedgerTest {

    private var clock = 0L

    private fun crumb(
        domain: BreadcrumbDomain,
        event: BreadcrumbEvent = BreadcrumbEvent.FAILED,
        details: List<BreadcrumbDetail> = emptyList(),
        atMs: Long = clock,
    ) = Breadcrumb(
        domain = domain,
        event = event,
        details = details,
        elapsedMs = atMs,
        wallMs = 1_700_000_000_000L + atMs,
    )

    /**
     * Offers [times] crumbs of one domain, one second apart.
     *
     * A second rather than longer so that a flood of any size these tests use stays inside one
     * hour — an hour that rolls mid-flood hands the allowance back and quietly invalidates
     * every count the test is about. Coalescing is avoided by giving each crumb its own
     * detail instead of by spacing them out.
     */
    private fun BreadcrumbLedger.flood(
        domain: BreadcrumbDomain,
        times: Int,
        detailFor: (Int) -> List<BreadcrumbDetail> = { emptyList() },
    ): List<String> {
        val written = mutableListOf<String>()
        repeat(times) { index ->
            clock += 1_000L
            written += offer(crumb(domain, details = detailFor(index), atMs = clock))
        }
        return written
    }

    /** Distinct details, so each crumb is its own kind and coalescing never applies. */
    private fun distinct(index: Int): List<BreadcrumbDetail> =
        listOf(BreadcrumbDetail.entries[index % BreadcrumbDetail.entries.size])

    @Test
    fun aDomainCannotBeSilencedByANoisierOne() {
        // The flaw in the original single-bucket sketch. A flapping network and a retrying
        // Telegram client would spend the hour before a revoked permission — the most common
        // reason an owner says the app just stopped working — writes a single row.
        val ledger = BreadcrumbLedger()

        ledger.flood(BreadcrumbDomain.NET, times = 40, detailFor = ::distinct)
        ledger.flood(BreadcrumbDomain.TELEGRAM, times = 40, detailFor = ::distinct)

        val perm = ledger.offer(
            crumb(
                BreadcrumbDomain.PERMISSION,
                event = BreadcrumbEvent.REVOKED,
                details = listOf(BreadcrumbDetail.PERM_LOCATION),
                atMs = clock + 90_000L,
            ),
        )

        assertEquals(listOf("perm:revoked:loc"), perm)
    }

    @Test
    fun aNoisyDomainStillGetsTheSharedPoolUntilItIsGone() {
        val ledger = BreadcrumbLedger()

        val written = ledger.flood(BreadcrumbDomain.NET, times = 60, detailFor = ::distinct)

        // Its own four, plus the whole shared pool, and not one row more.
        val reserved = BreadcrumbDomain.NET.reservedPerHour
        assertEquals(reserved + BreadcrumbDomain.SHARED_PER_HOUR, written.size)
    }

    @Test
    fun aFlapCostsOneRowAndKeepsItsCount() {
        // Rate limiting alone would throw away how often the network dropped, which is the
        // symptom itself.
        val ledger = BreadcrumbLedger()
        val start = 10_000L

        val first = ledger.offer(
            crumb(BreadcrumbDomain.NET, BreadcrumbEvent.LOST, listOf(BreadcrumbDetail.WIFI), start),
        )
        repeat(39) { index ->
            ledger.offer(
                crumb(
                    BreadcrumbDomain.NET,
                    BreadcrumbEvent.LOST,
                    listOf(BreadcrumbDetail.WIFI),
                    start + 1_000L * (index + 1),
                ),
            )
        }
        val closed = ledger.tick(start + 120_000L)

        assertEquals(listOf("net:lost:wifi"), first)
        assertEquals(listOf("net:lost:wifi x39"), closed)
    }

    @Test
    fun aOneOffCrumbIsNeverHeldWaitingForItsWindow() {
        // Holding the first of a kind would put every revoked permission and failed send a
        // minute behind the event, inside a process that is killed without warning.
        val ledger = BreadcrumbLedger()

        val written = ledger.offer(
            crumb(BreadcrumbDomain.PERMISSION, BreadcrumbEvent.REVOKED, listOf(BreadcrumbDetail.PERM_MICROPHONE)),
        )

        assertEquals(listOf("perm:revoked:mic"), written)
    }

    @Test
    fun whatTheCeilingRefusedIsReportedWhenTheHourTurns() {
        val ledger = BreadcrumbLedger()
        ledger.offer(crumb(BreadcrumbDomain.NET, atMs = 0L))
        ledger.flood(BreadcrumbDomain.NET, times = 60, detailFor = ::distinct)

        val rolled = ledger.tick(clock + 3_600_000L)

        val cap = rolled.single { it.startsWith("cap:net:") }
        val suppressed = cap.removePrefix("cap:net:").toInt()
        assertTrue("some crumbs were refused", suppressed > 0)
        // Offered 61, allowed its reservation plus the shared pool, refused the rest.
        assertEquals(61 - BreadcrumbDomain.NET.reservedPerHour - BreadcrumbDomain.SHARED_PER_HOUR, suppressed)
    }

    @Test
    fun theCeilingCannotSuppressTheReportOfItself() {
        // A limit able to silence its own cap row would be indistinguishable from no limit.
        val ledger = BreadcrumbLedger()
        BreadcrumbDomain.entries.forEach { domain ->
            ledger.flood(domain, times = 40, detailFor = ::distinct)
        }

        val rolled = ledger.tick(clock + 3_600_000L)

        assertEquals(
            BreadcrumbDomain.entries.map { "cap:${it.code}" },
            rolled.filter { it.startsWith("cap:") }.map { it.substringBeforeLast(':') },
        )
    }

    @Test
    fun theAllowanceComesBackWhenTheHourDoes() {
        val ledger = BreadcrumbLedger()
        ledger.flood(BreadcrumbDomain.NET, times = 60, detailFor = ::distinct)
        ledger.tick(clock + 3_600_000L)
        clock += 3_600_000L

        val afterRoll = ledger.flood(BreadcrumbDomain.NET, times = 4, detailFor = ::distinct)

        assertEquals(4, afterRoll.size)
    }

    @Test
    fun crumbsLostToAFullQueueReachTheFile() {
        // The lesson of `failureCount`: a counter nobody reads is a counter that is not there,
        // and a queue that overflows silently leaves a hole that reads as nothing happening.
        val ledger = BreadcrumbLedger()
        ledger.offer(crumb(BreadcrumbDomain.NET, atMs = 0L))
        repeat(7) { ledger.recordQueueDrop() }

        val rolled = ledger.tick(3_600_000L)

        assertTrue(rolled.contains("drop:queue:7"))
    }

    @Test
    fun aNoteNeverCarriesACommaIntoTheRow() {
        // A note that can shift its own row's columns can corrupt the file.
        BreadcrumbDomain.entries.forEach { domain ->
            BreadcrumbEvent.entries.forEach { event ->
                BreadcrumbDetail.entries.forEach { detail ->
                    val note = crumb(domain, event, listOf(detail)).note
                    assertTrue(note, !note.contains(','))
                }
            }
        }
    }

    @Test
    fun theHourlyAllowanceIsWhatTheDiskBudgetWasSizedAgainst() {
        assertEquals(60, BreadcrumbDomain.TOTAL_PER_HOUR)
    }
}
