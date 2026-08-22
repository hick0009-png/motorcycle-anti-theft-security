package com.example.motorcycleantitheftsensor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionContinuityPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStore
import com.example.motorcycleantitheftsensor.protection.RecoveryTrigger

/**
 * SVC-02: AlarmWatchdogReceiver
 * Periodically woken up by AlarmManager to ensure SensorService remains active and running 24/7.
 */
class AlarmWatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val WATCHDOG_REQUEST_CODE = 2001

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
            val pendingIntent = try {
                PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (_: Exception) {
                null
            } ?: return

            val triggerAtMs = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            val canExact = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
            } catch (_: Exception) {
                false
            }

            if (canExact) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    }
                    return
                } catch (_: SecurityException) {
                    // Fallback below if SecurityException occurs
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }
            } catch (_: Exception) {}
        }

        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
            val pendingIntent = try {
                PendingIntent.getBroadcast(
                    context,
                    WATCHDOG_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (_: Exception) {
                null
            }
            if (pendingIntent != null) {
                try {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        if (!allowsServiceStart(context)) {
            cancelWatchdog(context)
            return
        }

        // Reschedule next exact watchdog cycle
        scheduleWatchdog(context)

        val serviceIntent = Intent(context, SensorService::class.java).apply {
            action = SensorService.ACTION_START_SERVICE
            putExtra(SensorService.EXTRA_RECOVERY_TRIGGER, RecoveryTrigger.WATCHDOG.name)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("AlarmWatchdog", "Failed to start SensorService from background watchdog alarm", e)
        }
    }

    private fun allowsServiceStart(context: Context): Boolean = try {
        val recovery = ProtectionSnapshotStore(
            context = context,
            clock = ProtectionClock(System::currentTimeMillis),
        ).loadForRecovery()
        ProtectionContinuityPolicy.allowsServiceStart(
            intent = recovery.continuityIntent,
            trigger = RecoveryTrigger.WATCHDOG,
            continuityValid = recovery.continuityValid,
        )
    } catch (_: RuntimeException) {
        false
    }
}
