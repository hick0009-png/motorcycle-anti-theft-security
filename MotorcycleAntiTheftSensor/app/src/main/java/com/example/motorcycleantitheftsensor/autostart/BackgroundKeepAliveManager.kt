package com.example.motorcycleantitheftsensor.autostart

import android.content.Context
import android.os.Build

/**
 * Model representing the status of background execution permissions.
 */
data class KeepAliveStatus(
    val isBatteryOptimizedIgnored: Boolean,
    val isOemAutoStartAvailable: Boolean,
    val manufacturer: String,
) {
    val isFullyConfigured: Boolean
        get() = isBatteryOptimizedIgnored
}

/**
 * PWR-02: BackgroundKeepAliveManager
 * High-level coordinator that manages KeepAlive readiness, OEM AutoStart prompts,
 * and Battery Optimization whitelist requests.
 */
object BackgroundKeepAliveManager {

    private val helper: AutoStartPermissionHelper by lazy {
        AutoStartPermissionHelper.getInstance()
    }

    /**
     * Inspects the current device environment and returns the KeepAlive configuration status.
     */
    fun checkStatus(context: Context): KeepAliveStatus {
        val isBatteryIgnored = helper.isIgnoringBatteryOptimizations(context)
        val isOemAvailable = helper.isAutoStartPermissionAvailable(context)
        val manufacturer = Build.MANUFACTURER.orEmpty()

        return KeepAliveStatus(
            isBatteryOptimizedIgnored = isBatteryIgnored,
            isOemAutoStartAvailable = isOemAvailable,
            manufacturer = manufacturer,
        )
    }

    /**
     * Opens the OEM Autostart screen (Huawei, Xiaomi, Oppo, Vivo, Samsung, etc.).
     */
    fun openOemAutoStartSettings(context: Context): Boolean {
        return helper.getAutoStartPermission(context)
    }

    /**
     * Requests exemption from Android standard battery optimizations.
     */
    fun requestBatteryOptimizationExemption(context: Context): Boolean {
        return helper.requestIgnoreBatteryOptimization(context)
    }
}
