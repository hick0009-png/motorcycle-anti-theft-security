package com.example.motorcycleantitheftsensor.protection

/**
 * Per-arm lamp off/on integrity challenge for Power Guard (parent spec section 4.3):
 * the owner physically confirms the witness lamp placement by toggling it while the
 * charger stays connected. A pass recorded within [DEFAULT_VALIDITY_MS] satisfies the
 * immediately following first Arm in the same uninterrupted flow; skipping the
 * challenge arms as `Armed Degraded / witness placement not revalidated`.
 *
 * Process-wide holder backing the coordinator's `powerIntegrityChallenge` lambda at
 * graph composition: `registry::isSatisfied`. Purely in-memory on purpose — a stale
 * pass must never survive process death, because the physical setup may have changed.
 */
class PowerArmChallengeRegistry {

    @Volatile private var passedAtMs: Long? = null

    /** Records that the owner just completed the guided lamp off/on confirmation. */
    fun markPassed(nowMs: Long) {
        passedAtMs = nowMs
    }

    /**
     * True only when a pass was recorded within [DEFAULT_VALIDITY_MS] before [nowMs].
     * An expired or never-recorded pass fails closed (challenge not satisfied).
     */
    fun isSatisfied(nowMs: Long): Boolean {
        val passed = passedAtMs ?: return false
        return nowMs - passed in 0..DEFAULT_VALIDITY_MS
    }

    companion object {
        /** Challenge validity window: 10 minutes (spec section 4.3 first-Arm rule). */
        const val DEFAULT_VALIDITY_MS: Long = 10L * 60_000L
    }
}
