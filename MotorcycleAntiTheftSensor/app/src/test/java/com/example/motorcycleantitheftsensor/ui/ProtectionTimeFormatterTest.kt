package com.example.motorcycleantitheftsensor.ui

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionTimeFormatterTest {
    @Test
    fun epochMillisIsFormattedAsReadableLocalDateAndTime() {
        assertEquals(
            "Jan 1, 1970, 07:00:00",
            formatProtectionTimestamp(
                epochMillis = 0L,
                locale = Locale.US,
                timeZone = TimeZone.getTimeZone("Asia/Bangkok"),
            ),
        )
    }
}
