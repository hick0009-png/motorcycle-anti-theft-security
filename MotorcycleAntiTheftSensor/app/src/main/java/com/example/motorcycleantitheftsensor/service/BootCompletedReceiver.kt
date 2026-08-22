package com.example.motorcycleantitheftsensor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionStore
import com.example.motorcycleantitheftsensor.protection.ProtectionClock
import com.example.motorcycleantitheftsensor.protection.ProtectionContinuityPolicy
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStore
import com.example.motorcycleantitheftsensor.protection.RecoveryTrigger

/**
 * SVC-03: BootCompletedReceiver
 * Automatically launches SensorService and AlarmWatchdog upon device boot.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootRecovery"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (DirectBootBootstrapPolicy.isLockedBootAction(intent.action)) {
            val marker = DirectBootProtectionStore(context).load()
            val allowed = DirectBootBootstrapPolicy.shouldStart(marker)
            Log.i(TAG, "received=${intent.action}, directBootAllowed=$allowed")
            if (allowed) {
                startDirectBootBootstrap(context)
            }
            return
        }

        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RecoveryTrigger.ANDROID_BOOT
            Intent.ACTION_USER_UNLOCKED -> RecoveryTrigger.ANDROID_USER_UNLOCKED
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecoveryTrigger.PACKAGE_REPLACED
            else -> return
        }
        if (trigger == RecoveryTrigger.ANDROID_USER_UNLOCKED) {
            context.stopService(Intent(context, DirectBootBootstrapService::class.java))
        }
        val allowed = allowsServiceStart(context, trigger)
        Log.i(TAG, "received=${intent.action}, trigger=$trigger, allowed=$allowed")
        if (allowed) {
            val serviceIntent = Intent(context, SensorService::class.java).apply {
                action = SensorService.ACTION_START_SERVICE
                putExtra(SensorService.EXTRA_RECOVERY_TRIGGER, trigger.name)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                AlarmWatchdogReceiver.scheduleWatchdog(context)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to start service for $trigger", error)
            }
        }
    }

    private fun startDirectBootBootstrap(context: Context) {
        val bootstrapIntent = Intent(context, DirectBootBootstrapService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(bootstrapIntent)
            } else {
                context.startService(bootstrapIntent)
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start direct-boot bootstrap", error)
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
