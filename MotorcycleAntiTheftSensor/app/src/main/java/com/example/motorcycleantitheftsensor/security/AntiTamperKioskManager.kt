package com.example.motorcycleantitheftsensor.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * SEC-06: AntiTamperKioskManager
 * Manages Kiosk / Lock Task Mode, disables USB Debugging (ADB), and detects Safe Mode boots.
 * Prevents thieves from shutting down or accessing Quick Settings on stolen/alarm devices.
 */
class AntiTamperKioskManager(private val context: Context) {

    companion object {
        private const val TAG = "AntiTamperKiosk"
    }

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminController::class.java)

    /**
     * Checks if the app is configured as Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            devicePolicyManager.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enables Lock Task Mode (Kiosk Mode) if Device Owner, blocking Power Menu & Status Bar.
     */
    fun enableKioskMode(activity: Activity) {
        if (isDeviceOwner()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                    devicePolicyManager.setLockTaskFeatures(adminComponent, flags)
                }
                devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting lock task features/packages", e)
            }
        }
        try {
            activity.startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed", e)
        }
    }

    /**
     * Disables Lock Task Mode (requires authorized admin disarm).
     */
    fun disableKioskMode(activity: Activity) {
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed", e)
        }
    }

    /**
     * Automatically disables USB Debugging (ADB) to block unauthorized shell access.
     */
    fun disableUsbDebugging() {
        if (isDeviceOwner()) {
            try {
                devicePolicyManager.setGlobalSetting(
                    adminComponent,
                    Settings.Global.ADB_ENABLED,
                    "0"
                )
            } catch (e: Exception) {
                Log.w(TAG, "disableUsbDebugging failed", e)
            }
        }
    }

    /**
     * Detects if the device was booted into Safe Mode.
     * Returns true if Safe Mode is currently active.
     */
    fun isSafeModeActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.isSafeMode
        } else {
            false
        }
    }
}
