package com.example.motorcycleantitheftsensor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * SVC-02: AlarmWatchdogReceiver
 * Periodically woken up by AlarmManager to ensure SensorService remains active and running 24/7.
 */
class AlarmWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pendingIntent
            )
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val serviceIntent = Intent(context, SensorService::class.java).apply {
            action = SensorService.ACTION_START_SERVICE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
