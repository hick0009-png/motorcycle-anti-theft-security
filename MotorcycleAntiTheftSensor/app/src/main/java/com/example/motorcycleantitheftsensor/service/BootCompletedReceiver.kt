package com.example.motorcycleantitheftsensor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionContinuityPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStore
import com.example.motorcycleantitheftsensor.protection.RecoveryTrigger

/**
 * SVC-03: BootCompletedReceiver
 * Automatically launches SensorService and AlarmWatchdog upon device boot.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RecoveryTrigger.ANDROID_BOOT
            Intent.ACTION_USER_UNLOCKED -> RecoveryTrigger.ANDROID_USER_UNLOCKED
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecoveryTrigger.PACKAGE_REPLACED
            else -> return
        }
        if (allowsServiceStart(context, trigger)) {
            val serviceIntent = Intent(context, SensorService::class.java).apply {
                action = SensorService.ACTION_START_SERVICE
                putExtra(SensorService.EXTRA_RECOVERY_TRIGGER, trigger.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            AlarmWatchdogReceiver.scheduleWatchdog(context)
        }
    }

    private fun allowsServiceStart(context: Context, trigger: RecoveryTrigger): Boolean = try {
        val recovery = ProtectionSnapshotStore(
            context = context,
            clock = ProtectionClock(System::currentTimeMillis),
        ).loadForRecovery()
        ProtectionContinuityPolicy.allowsServiceStart(
            intent = recovery.continuityIntent,
            trigger = trigger,
            continuityValid = recovery.continuityValid,
        )
    } catch (_: RuntimeException) {
        false
    }
}
