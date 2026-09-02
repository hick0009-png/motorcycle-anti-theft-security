package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

/**
 * Whether an orientation verdict is allowed to raise an alarm on its own.
 *
 * The door watch reads an angle and nothing else. That angle can move without a door
 * moving: the reported orientation of a phone lying still walks on its own, by an amount
 * that depends on the chipset, and the baseline is frozen for the whole armed session and
 * never rebaselined. A session that lasts a working day — arm on the way out, disarm on the
 * way back — gives that walk eight hours to reach a threshold meant for a door.
 *
 * A door opening is not only an angle. It shakes the door, and the phone mounted on it, and
 * a real opening therefore arrives with movement beside it. Drift arrives alone, silently,
 * at a few degrees an hour. That difference is what this asks about: not "did the angle
 * cross the line" — the detection policy has already answered that — but "was there
 * anything else at all, at the same moment, that a door opening would have produced".
 *
 * The one rule that must never be broken: **a phone that cannot supply movement is never
 * refused.** A use that does not run a movement sensor, or a device that has none, would
 * otherwise be silenced completely by a corroboration it can never produce — a far worse
 * failure than the false alert this prevents. When in doubt, the alert goes out.
 */
object EntryCorroborationPolicy {

    /**
     * How far from the verdict a movement sample still counts as belonging to it.
     *
     * A door opening is confirmed after the angle holds for [EntryProfileSettings.openConfirmationMs]
     * (750 ms by default), and the shake that starts it precedes that hold, so the movement
     * that belongs to an opening arrives a little before the verdict rather than with it.
     * Ten seconds is generous enough to cover the whole of a slow opening and a detector
     * that needs a moment to cross its own threshold, and still far too short to be
     * satisfied by anything in a night that was silent.
     */
    const val MOVEMENT_WINDOW_MS = 10_000L

    /**
     * @param corroborationArmed whether this armed session actually runs a movement signal.
     *   False means the question cannot be asked, and an unasked question never refuses.
     * @param lastMovementElapsedMs the most recent movement observation the engine holds, on
     *   the same elapsed-time base as [verdictElapsedMs].
     */
    fun mayOpen(
        verdictElapsedMs: Long,
        lastMovementElapsedMs: Long?,
        corroborationArmed: Boolean,
        windowMs: Long = MOVEMENT_WINDOW_MS,
    ): Boolean {
        if (!corroborationArmed) return true
        val movementAtMs = lastMovementElapsedMs ?: return false
        return abs(verdictElapsedMs - movementAtMs) <= windowMs
    }
}
