package com.example.motorcycleantitheftsensor.autostart

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Represents an OEM-specific activity target for autostart settings.
 */
data class OemTarget(
    val packageName: String,
    val className: String,
)

/**
 * PWR-01: AutoStartPermissionHelper
 * Inspects device manufacturer (Huawei, Honor, Xiaomi, Oppo, Vivo, Samsung, Asus, OnePlus, etc.)
 * and launches OEM-specific background autostart / power management screens.
 * Inspired by judemanutd/AutoStarter with enhanced Android 9-16 safety checks.
 */
class AutoStartPermissionHelper private constructor() {

    companion object {
        private const val TAG = "AutoStartPermission"

        @Volatile
        private var instance: AutoStartPermissionHelper? = null

        fun getInstance(): AutoStartPermissionHelper {
            return instance ?: synchronized(this) {
                instance ?: AutoStartPermissionHelper().also { instance = it }
            }
        }
    }

    /**
     * Checks if the device manufacturer has a known custom autostart or power manager screen.
     */
    fun isAutoStartPermissionAvailable(
        context: Context,
        manufacturer: String = Build.MANUFACTURER?.lowercase().orEmpty(),
        brand: String = Build.BRAND?.lowercase().orEmpty(),
    ): Boolean {
        val targets = getOemTargets(manufacturer, brand)
        if (targets.isEmpty()) return false
        val packageManager = context.packageManager
        for (target in targets) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(target.packageName, target.className)
                }
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, 0)
                }
                if (list.isNotEmpty()) {
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Attempts to open the OEM autostart screen. Returns true if successfully launched.
     */
    fun getAutoStartPermission(
        context: Context,
        manufacturer: String = Build.MANUFACTURER?.lowercase().orEmpty(),
        brand: String = Build.BRAND?.lowercase().orEmpty(),
    ): Boolean {
        val targets = getOemTargets(manufacturer, brand)
        for (target in targets) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(target.packageName, target.className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.queryIntentActivities(intent, 0)
                }
                if (list.isNotEmpty()) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching intent: ${target.className}", e)
            }
        }

        // Fallback to Application Details Settings
        return try {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening app details settings fallback", e)
            false
        }
    }

    /**
     * Checks if the app is whitelisted from Android standard battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    /**
     * Requests user exemption from standard battery optimizations (Doze mode).
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                return true
            }
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed, trying fallback", e)
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    return true
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Battery optimization settings fallback failed", fallbackError)
                }
            }
        }
        return false
    }

    internal fun getOemTargets(
        manufacturer: String = Build.MANUFACTURER?.lowercase().orEmpty(),
        brand: String = Build.BRAND?.lowercase().orEmpty(),
    ): List<OemTarget> {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val targets = mutableListOf<OemTarget>()

        when {
            // HUAWEI / HONOR / EMUI / HARMONYOS / MAGICOS
            m.contains("huawei") || b.contains("huawei") ||
            m.contains("honor") || b.contains("honor") -> {
                targets.add(OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
                targets.add(OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
                targets.add(OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                targets.add(OemTarget("com.huawei.systemmanager", "com.huawei.systemmanager.mainscreen.MainScreenActivity"))
                targets.add(OemTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                targets.add(OemTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"))
            }

            // XIAOMI / REDMI / POCO / MIUI / HYPEROS
            m.contains("xiaomi") || b.contains("xiaomi") ||
            m.contains("redmi") || b.contains("redmi") ||
            m.contains("poco") || b.contains("poco") -> {
                targets.add(OemTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                targets.add(OemTarget("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
            }

            // OPPO / REALME / COLOROS / REALMEUI
            m.contains("oppo") || b.contains("oppo") ||
            m.contains("realme") || b.contains("realme") -> {
                targets.add(OemTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                targets.add(OemTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                targets.add(OemTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"))
                targets.add(OemTarget("com.oplus.battery", "com.oplus.battery.clean.DeepCleanActivity"))
                targets.add(OemTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }

            // VIVO / IQOO / FUNTOUCHOS / ORIGINOS
            m.contains("vivo") || b.contains("vivo") ||
            m.contains("iqoo") || b.contains("iqoo") -> {
                targets.add(OemTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
                targets.add(OemTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                targets.add(OemTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            }

            // SAMSUNG / ONEUI
            m.contains("samsung") || b.contains("samsung") -> {
                targets.add(OemTarget("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                targets.add(OemTarget("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.AppSleepListActivity"))
                targets.add(OemTarget("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.AppSleepListActivity"))
            }

            // ASUS / ZENUI / ROG UI
            m.contains("asus") || b.contains("asus") -> {
                targets.add(OemTarget("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"))
                targets.add(OemTarget("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
            }

            // ONEPLUS / OXYGENOS / HYDROGENOS
            m.contains("oneplus") || b.contains("oneplus") -> {
                targets.add(OemTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListAct"))
                targets.add(OemTarget("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"))
                targets.add(OemTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            }

            // TRANSSION / INFINIX / TECNO / ITEL / HIOS / XOS
            m.contains("transsion") || m.contains("infinix") || m.contains("tecno") || m.contains("itel") -> {
                targets.add(OemTarget("com.transsion.phonemanager", "com.transsion.phonemanager.subpages.bgmanage.AutoStartManageActivity"))
            }
        }

        return targets
    }
}
