package com.example.motorcycleantitheftsensor.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Hears the clock being set, and tells the black box before the next minute row would have.
 *
 * The recorder already compares the two clocks on every tick, and that catches a clock that
 * was moved and left. It cannot catch a clock that was moved and moved back inside the same
 * minute, because the comparison sees only the endpoints and they agree — and moving it back
 * is exactly what somebody hiding an hour would do. The system announces the change itself,
 * so the record does not have to infer it.
 *
 * Registered by the service and unregistered with it. Not declared in the manifest: these are
 * protected broadcasts that only reach a registered receiver anyway, and a manifest entry
 * would wake the app on every timezone change for the life of the install, including when
 * nothing is recording and there is nothing to write the row to.
 */
class ClockChangeWatcher(private val onClockChanged: (String) -> Unit) {

    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cause = when (intent?.action) {
                    Intent.ACTION_TIME_CHANGED -> BlackBoxRecorder.NOTE_CAUSE_SET
                    Intent.ACTION_TIMEZONE_CHANGED -> BlackBoxRecorder.NOTE_CAUSE_TIMEZONE
                    else -> return
                }
                // A receiver that throws takes the service down with it, and this one exists
                // to describe a problem rather than to be one.
                runCatching { onClockChanged(cause) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching { context.registerReceiver(created, filter) }
            .onSuccess { receiver = created }
    }

    fun stop(context: Context) {
        val current = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(current) }
    }
}
