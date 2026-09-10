package com.example.motorcycleantitheftsensor.protection

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sixteen bytes the app leaves with the system so that a death can be described by the
 * process that died.
 *
 * `ActivityManager.setProcessStateSummary` hands the OS a small blob that it keeps and gives
 * back through `ApplicationExitInfo.getProcessStateSummary()` after the process is gone —
 * including when it was gone without warning. That is the whole reason this exists: every
 * other way the app could say what it was doing requires it to still be alive to say it, and
 * the case worth describing is exactly the case where it is not.
 *
 * So this is not written at death. It is rewritten every minute alongside the minute row, and
 * whichever copy the kill happens to catch is the one that survives. A row that cannot be
 * written late cannot be written too late.
 *
 * Sixteen bytes is far under the platform's limit and is kept small on purpose anyway: it is
 * republished every minute for the life of the service, and it holds only what the black box's
 * own state columns hold, so an `X` row can be filled in with the dying process's last known
 * state rather than with blanks.
 */
object BlackBoxProcessStateSummary {

    const val VERSION = 1
    const val SIZE_BYTES = 16

    private const val PROFILE_NONE = 0xFF
    private const val BATTERY_UNKNOWN = 0xFF
    private const val FLAG_ARMED = 1
    private const val FLAG_CHARGING_KNOWN = 1 shl 1
    private const val FLAG_CHARGING = 1 shl 2

    /**
     * [bootIdHash] ties the blob to a boot rather than to a process, which is what makes the
     * difference between a kill and a reboot readable: two runs separated by a kill share it.
     */
    fun encode(
        state: BlackBoxState,
        elapsedMs: Long,
        bootIdHash: Int,
        profileOrdinal: Int?,
    ): ByteArray {
        var flags = 0
        if (state.armed) flags = flags or FLAG_ARMED
        state.charging?.let { charging ->
            flags = flags or FLAG_CHARGING_KNOWN
            if (charging) flags = flags or FLAG_CHARGING
        }
        return ByteBuffer.allocate(SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            put(VERSION.toByte())
            putInt(bootIdHash)
            // Seconds, not milliseconds: an int of seconds covers sixty-eight years of uptime
            // and the column it lands in is read at minute resolution anyway.
            putInt((elapsedMs / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            put(flags.toByte())
            put((profileOrdinal ?: PROFILE_NONE).toByte())
            putShort(state.sourceMask.toShort())
            // Saturating rather than wrapping: a count that rolled over to a small number
            // would read as a quiet night.
            putShort(state.incidents.coerceIn(0, 0xFFFF).toShort())
            put((state.batteryPercent ?: BATTERY_UNKNOWN).toByte())
        }.array()
    }

    /** Null for anything this build does not recognise, which is how a future layout is refused. */
    fun decode(bytes: ByteArray?): Decoded? {
        if (bytes == null || bytes.size != SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get().toInt() != VERSION) return null
        val bootIdHash = buffer.int
        val elapsedMs = buffer.int.toLong() * 1000L
        val flags = buffer.get().toInt() and 0xFF
        val profileOrdinal = (buffer.get().toInt() and 0xFF).takeIf { it != PROFILE_NONE }
        val sourceMask = buffer.short.toInt() and 0xFFFF
        val incidents = buffer.short.toInt() and 0xFFFF
        val batteryPercent = (buffer.get().toInt() and 0xFF).takeIf { it != BATTERY_UNKNOWN }
        return Decoded(
            bootIdHash = bootIdHash,
            elapsedMs = elapsedMs,
            state = BlackBoxState(
                armed = flags and FLAG_ARMED != 0,
                mode = profileOrdinal?.let { ordinal ->
                    ProtectionProfile.entries.getOrNull(ordinal)?.name
                } ?: BlackBoxState.MODE_NONE,
                sourceMask = sourceMask,
                incidents = incidents,
                batteryPercent = batteryPercent,
                charging = if (flags and FLAG_CHARGING_KNOWN != 0) flags and FLAG_CHARGING != 0 else null,
            ),
        )
    }

    data class Decoded(
        val bootIdHash: Int,
        val elapsedMs: Long,
        val state: BlackBoxState,
    )
}
