package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classifiers that stand between a real measurement and what the file is allowed to say.
 *
 * They exist so that no caller ever has a reason to reach for a raw number. A metre, a
 * millisecond or an error body would all be more precise and every one of them is a way for
 * something identifying to end up on a disk that travels with a stolen phone.
 */
class BreadcrumbDetailTest {

    @Test
    fun aRateLimitKeepsItsOwnValueBecauseAnOwnerCanActOnIt() {
        // 429 is the app being told to slow down, which is not the same news as being broken.
        assertEquals(BreadcrumbDetail.HTTP_429, BreadcrumbDetail.httpClass(429))
        assertEquals(BreadcrumbDetail.HTTP_4XX, BreadcrumbDetail.httpClass(400))
        assertEquals(BreadcrumbDetail.HTTP_4XX, BreadcrumbDetail.httpClass(401))
        assertEquals(BreadcrumbDetail.HTTP_5XX, BreadcrumbDetail.httpClass(503))
    }

    @Test
    fun aRequestThatNeverGotAStatusIsATimeoutAndNotAnUnknown() {
        // No status means it never reached Telegram: a timeout, a refused socket, no route.
        // That is the answer an owner needs, and it is the opposite of a server error.
        assertEquals(BreadcrumbDetail.TIMEOUT, BreadcrumbDetail.httpClass(null))
    }

    @Test
    fun latencyIsABandAndNeverAMillisecondCount() {
        assertEquals(BreadcrumbDetail.UNDER_2S, BreadcrumbDetail.latency(0L))
        assertEquals(BreadcrumbDetail.UNDER_2S, BreadcrumbDetail.latency(1_999L))
        assertEquals(BreadcrumbDetail.UNDER_10S, BreadcrumbDetail.latency(2_000L))
        assertEquals(BreadcrumbDetail.OVER_10S, BreadcrumbDetail.latency(60_000L))
    }

    @Test
    fun accuracyIsABandAndNeverAMetreCount() {
        assertEquals(BreadcrumbDetail.ACCURACY_UNDER_10M, BreadcrumbDetail.accuracy(9.9f))
        assertEquals(BreadcrumbDetail.ACCURACY_10_TO_50M, BreadcrumbDetail.accuracy(50f))
        assertEquals(BreadcrumbDetail.ACCURACY_OVER_50M, BreadcrumbDetail.accuracy(50.1f))
    }

    @Test
    fun everyValueThatCanReachTheFileIsShortAndPlain() {
        // The whole vocabulary is reviewable in one place, which is the point of it being a
        // closed enum. Anything long or punctuated is a sign somebody has started encoding
        // real data into a code.
        BreadcrumbDetail.entries.forEach { detail ->
            assertEquals(detail.name, true, detail.code.length <= 8)
            assertEquals(detail.name, true, detail.code.all { it.isLetterOrDigit() || it == '_' })
        }
    }
}
