package com.example.motorcycleantitheftsensor.ui

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionTimeFormatterTest {

    private val bangkok = TimeZone.getTimeZone("Asia/Bangkok")

    /** Thai owners read timestamps in the Buddhist era with Thai month names (spec §6.2). */
    @Test
    fun thaiLocaleRendersReadableBuddhistEraTimestamp() {
        assertEquals(
            "1 ม.ค. 2513 07:00",
            formatProtectionTimestamp(
                epochMillis = 0L,
                locale = Locale("th", "TH"),
                timeZone = bangkok,
            ),
        )
    }

    @Test
    fun explicitLocaleIsHonoredSoInstrumentedTestsCanPinExactOutput() {
        assertEquals(
            "1 Jan 1970 07:00",
            formatProtectionTimestamp(
                epochMillis = 0L,
                locale = Locale.US,
                timeZone = bangkok,
            ),
        )
    }
}
