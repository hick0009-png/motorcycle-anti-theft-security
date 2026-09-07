package com.example.motorcycleantitheftsensor.protection

/**
 * Single source of truth for the reasons an incident is closed. The reason is authored where
 * the close happens ([IncidentEngine], [ProtectionCoordinator], [com.example.motorcycleantitheftsensor.service.SensorService])
 * and read back by [IncidentMessageFormatter] to decide what a closed incident says.
 *
 * The distinction that matters to the owner copy is between a *terminal verdict* and every other
 * reason. A terminal verdict is the watch's own resolution of the episode — the door shut, the
 * supply came back — and its evidence was landed on the incident just before the close, so the
 * message is entitled to speak that outcome. Every other reason ends the session from outside the
 * episode: the owner disarmed, switched profile, the process was interrupted, the movement went
 * quiet, or a new episode took the single active slot. Those closes append no resolving evidence,
 * so the message must say only that the watch ended, and must never read the last live sample
 * back as if it were the outcome — the failure that had a door-mode disarm announce "the power
 * came back" off a charger reading that was never a recovery.
 */
internal object IncidentCloseReason {
    // Terminal verdicts — the episode resolved itself; resolving evidence appended before close.
    const val ENTRY_DOOR_CLOSED_CONFIRMED = "entry door closed confirmed"
    const val ENTRY_MOUNT_RESTORED = "entry mount restored"
    const val ENTRY_SOURCE_RECOVERED = "entry source recovered"
    const val POWER_SUPPLY_STABLE = "power supply stable again"

    // Session ended from outside the episode — no resolving evidence was appended.
    const val OWNER_DISARMED = "owner disarmed"
    const val OWNER_CHANGED_PROFILE = "owner changed protection profile"
    const val PROCESS_INTERRUPTED = "process interrupted"
    const val QUIET_WINDOW_ELAPSED = "quiet window elapsed"

    /**
     * A close that carries this substring resolved a door episode whose position evidence had
     * dropped out under the owner's hands; the entry copy speaks its own line for it. [PROCESS_INTERRUPTED]
     * contains it too, which is intended — an interrupted door episode is exactly that condition.
     */
    const val EVIDENCE_INTERRUPTED_MARKER = "interrupted"

    private val TERMINAL_VERDICTS = setOf(
        ENTRY_DOOR_CLOSED_CONFIRMED,
        ENTRY_MOUNT_RESTORED,
        ENTRY_SOURCE_RECOVERED,
        POWER_SUPPLY_STABLE,
    )

    /**
     * Whether a close reason is the episode's own resolution verdict, whose landed evidence the
     * message may speak. A null or unrecognized reason is treated as a session-end: the safe
     * reading of "nobody said this was a resolution" is that it was not one.
     */
    fun isTerminalVerdict(reason: String?): Boolean = reason in TERMINAL_VERDICTS
}
