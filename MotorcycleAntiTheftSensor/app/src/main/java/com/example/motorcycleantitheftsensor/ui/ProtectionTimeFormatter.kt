package com.example.motorcycleantitheftsensor.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Thai owner-facing timestamp (profile-aware Thai UX, Task 4 / spec §6.2): day-month-year
 * with the caller's locale so Thai renders Buddhist-era month names and no raw epoch
 * millis ever reach the UI. Locale stays injectable so instrumented tests can pin output.
 */
internal fun formatProtectionTimestamp(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat("d MMM yyyy HH:mm", locale).run {
    this.timeZone = timeZone
    format(Date(epochMillis))
}
