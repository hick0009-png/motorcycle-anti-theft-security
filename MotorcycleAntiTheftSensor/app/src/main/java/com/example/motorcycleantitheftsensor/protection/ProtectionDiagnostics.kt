package com.example.motorcycleantitheftsensor.protection

/**
 * Single source of truth for typed guard diagnostics. The runtime synthesizes these
 * strings, [IncidentEngine] classifies them, and [IncidentMessageFormatter] renders
 * owner copy from them; all three must reference these constants so a rename can
 * never silently desynchronize the pipeline.
 */
internal object ProtectionDiagnostics {
    const val ENTRY_PREFIX = "entry_"
    const val ENTRY_DOOR_OPEN = "entry_door_open"
    const val ENTRY_DOOR_STILL_OPEN = "entry_door_still_open"
    const val ENTRY_DOOR_CLOSED = "entry_door_closed"
    const val ENTRY_SOURCE_UNAVAILABLE = "entry_source_unavailable"
    const val ENTRY_SOURCE_RECOVERED = "entry_source_recovered"
    const val ENTRY_MOUNT_MOVED = "entry_mount_moved"

    const val POWER_PREFIX = "power_"
    const val POWER_CHARGING_HEALTH = "power_charging_health"
    const val POWER_WITNESS_DARK = "power_witness_dark"
    const val POWER_CONFIRMED_LOSS = "power_confirmed_loss"
    const val POWER_RECOVERED = "power_recovered"
}
