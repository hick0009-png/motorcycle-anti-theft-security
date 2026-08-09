package com.example.motorcycleantitheftsensor.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatProtectionTimestamp(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat("MMM d, yyyy, HH:mm:ss", locale).run {
    this.timeZone = timeZone
    format(Date(epochMillis))
}
