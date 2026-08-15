package com.example.motorcycleantitheftsensor.location

import android.app.ActivityManager

fun interface AppVisibilityProvider {
    fun isAppProcessForeground(): Boolean
}

class AndroidAppVisibilityProvider(
    private val importanceReader: () -> Int = ::readProcessImportance,
) : AppVisibilityProvider {
    override fun isAppProcessForeground(): Boolean {
        return importanceReader() == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        fun readProcessImportance(): Int {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            return try {
                ActivityManager.getMyMemoryState(appProcessInfo)
                appProcessInfo.importance
            } catch (_: Exception) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
            }
        }
    }
}

fun interface ForegroundStartGateway {
    fun start(foregroundTypes: Int)
}

data class ForegroundStartResult(
    val usedForegroundTypes: Int,
    val degradedReason: String?,
)

class ForegroundStartController {
    fun start(
        requestedTypes: Int,
        specialUseOnlyTypes: Int,
        gateway: ForegroundStartGateway,
    ): ForegroundStartResult {
        return try {
            gateway.start(requestedTypes)
            ForegroundStartResult(usedForegroundTypes = requestedTypes, degradedReason = null)
        } catch (_: SecurityException) {
            gateway.start(specialUseOnlyTypes)
            ForegroundStartResult(
                usedForegroundTypes = specialUseOnlyTypes,
                degradedReason = "Background location foreground start restricted"
            )
        }
    }
}
