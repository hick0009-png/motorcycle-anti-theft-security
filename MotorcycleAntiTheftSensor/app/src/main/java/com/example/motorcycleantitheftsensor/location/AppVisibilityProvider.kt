package com.example.motorcycleantitheftsensor.location

import android.app.ActivityManager
import android.content.pm.ServiceInfo
import android.os.Build

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
    /**
     * Tries each claim in turn and keeps the first the platform accepts.
     *
     * It used to be two rungs: everything, then special-use alone. With a third type in play
     * that shape gives up too much — a microphone claim the platform refuses would also throw
     * away the location claim it was perfectly willing to grant, and the vehicle watch would
     * silently lose its fixes because the door watch asked for a sensor. Each rung drops one
     * thing.
     */
    fun start(
        attempts: List<Int>,
        gateway: ForegroundStartGateway,
    ): ForegroundStartResult {
        require(attempts.isNotEmpty()) { "A foreground start needs at least one claim to try" }
        val distinct = attempts.distinct()
        distinct.forEachIndexed { index, types ->
            try {
                gateway.start(types)
                return ForegroundStartResult(
                    usedForegroundTypes = types,
                    degradedReason = if (index == 0) null else RESTRICTED_REASON,
                )
            } catch (error: SecurityException) {
                if (index == distinct.lastIndex) throw error
            }
        }
        error("unreachable")
    }

    private companion object {
        const val RESTRICTED_REASON = "Foreground service type restricted"
    }
}

/**
 * Which foreground service types the sensor service may claim right now.
 *
 * This decision was written inline twice, in the two places the service goes foreground, and
 * the two copies had to agree by hand. They are one function now, and one that can be asked
 * questions in a test rather than only on a phone.
 */
object ForegroundServiceTypePolicy {

    /**
     * @param microphoneGranted whether RECORD_AUDIO is held. The type may only be claimed with
     *   the permission in hand; claiming it without is refused outright, and claiming it when
     *   the app has no use for sound would put a microphone in the owner's status bar for
     *   nothing.
     */
    fun requestedTypes(
        sdkInt: Int,
        locationForeground: Boolean,
        microphoneGranted: Boolean,
    ): Int {
        if (sdkInt < Build.VERSION_CODES.Q) return NONE
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (locationForeground) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (microphoneGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    /** What is left of a claim once the microphone is given up, in order, best first. */
    fun attempts(
        sdkInt: Int,
        locationForeground: Boolean,
        microphoneGranted: Boolean,
    ): List<Int> {
        if (sdkInt < Build.VERSION_CODES.Q) return listOf(NONE)
        return listOf(
            requestedTypes(sdkInt, locationForeground, microphoneGranted),
            requestedTypes(sdkInt, locationForeground, microphoneGranted = false),
            requestedTypes(sdkInt, locationForeground = false, microphoneGranted = false),
        ).distinct()
    }

    /** Whether a claim that was granted has lost the location type the caller asked for. */
    fun lostLocation(requestedTypes: Int, usedTypes: Int): Boolean =
        requestedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0 &&
            usedTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0

    const val NONE = 0
}
