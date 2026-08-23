package com.example.motorcycleantitheftsensor.protection

/**
 * Durable owner-notification state of one Power Guard episode, used to choose the
 * settlement copy after the complete healthy window (parent spec section 4.3).
 */
enum class PowerOwnerNotificationState {
    /** Nothing accepted or ambiguous ever reached the owner: retire silently. */
    NO_OPENING_REACHED_OWNER,
    ONE_SIGNAL_OPENING_ACCEPTED,
    CONFIRMED_OPENING_ACCEPTED,
    OPENING_DELIVERY_UNCERTAIN,
}

/**
 * Safety-weighted ambiguity policy for Power Guard delivery (parent spec section
 * 4.3). Telegram provides no server-side idempotency key, so a crash or timeout
 * after possible acceptance is `DELIVERY_UNCERTAIN` — never claimed as failure and
 * never claimed as exactly-once.
 *
 * Rules:
 * - Uncertain ONE-SIGNAL health/recovery messages are never automatically retried.
 * - An uncertain CONFIRMED-outage opening permits at most ONE labeled safety retry
 *   after [safetyRetryDelayMs], only while that episode is still in continuous
 *   confirmed loss and the opening transition remains current.
 * - Partial/complete recovery, semantic supersession, or episode close cancels any
 *   not-started safety retry; a healthy episode after an uncertain opening uses the
 *   uncertainty-referencing settlement copy instead of a stale outage retry.
 */
class PowerAmbiguityPolicy(
    private val nowMs: () -> Long,
    private val safetyRetryDelayMs: Long = 30_000L,
) {

    data class State(
        val uncertainOpening: PowerOutboxItem? = null,
        val uncertainSinceMs: Long? = null,
        val safetyRetryEnqueued: Boolean = false,
    )

    /**
     * Records an uncertain acceptance. Only a confirmed-loss opening is tracked for
     * the single labeled safety retry; one-signal health copy is never auto-retried.
     */
    fun onUncertain(state: State, item: PowerOutboxItem): State =
        if (item.kind == PowerTransitionKind.CONFIRMED_LOSS_OPENING) {
            state.copy(uncertainOpening = item, uncertainSinceMs = nowMs())
        } else {
            state
        }

    /**
     * True only when the single labeled safety retry may be enqueued right now:
     * an uncertain confirmed opening exists, no retry was enqueued yet, the delay
     * elapsed, the episode is still continuously lost, and the opening is still the
     * current owner-visible transition.
     */
    fun safetyRetryDue(
        state: State,
        nowMs: Long,
        stillContinuouslyLost: Boolean,
        openingStillCurrent: Boolean,
    ): Boolean {
        val since = state.uncertainSinceMs ?: return false
        if (state.safetyRetryEnqueued) return false
        if (!stillContinuouslyLost || !openingStillCurrent) return false
        return nowMs - since >= safetyRetryDelayMs
    }

    /** Marks the one permitted labeled retry as enqueued (never again afterwards). */
    fun markSafetyRetryEnqueued(state: State): State = state.copy(safetyRetryEnqueued = true)

    /**
     * Recovery, supersession, or episode close cancels a not-started safety retry by
     * clearing the tracked uncertain opening entirely.
     */
    fun cancelNotStartedSafetyRetry(state: State): State =
        state.copy(uncertainOpening = null, uncertainSinceMs = null, safetyRetryEnqueued = false)

    companion object {
        const val SAFETY_RETRY_PREFIX = "ส่งซ้ำเพื่อยืนยันเหตุเดิม—ผลการส่งครั้งแรกไม่แน่นอน"

        /**
         * Settlement kind chosen from durable owner-notification state after the
         * complete healthy window. `null` means silent retirement with no orphan
         * recovery message.
         */
        fun settlementKind(notificationState: PowerOwnerNotificationState): PowerTransitionKind? =
            when (notificationState) {
                PowerOwnerNotificationState.NO_OPENING_REACHED_OWNER -> null
                PowerOwnerNotificationState.ONE_SIGNAL_OPENING_ACCEPTED -> PowerTransitionKind.ONE_SIGNAL_RECOVERY
                PowerOwnerNotificationState.CONFIRMED_OPENING_ACCEPTED -> PowerTransitionKind.CONFIRMED_CLOSE
                PowerOwnerNotificationState.OPENING_DELIVERY_UNCERTAIN -> PowerTransitionKind.UNCERTAINTY_SETTLEMENT
            }

        /** The single labeled retry carries the visible episode ID and uncertainty prefix. */
        fun labeledSafetyRetryText(originalText: String, episodeId: String): String =
            "$SAFETY_RETRY_PREFIX [$episodeId] $originalText"

        /**
         * When the initial delivery result was uncertain, the settlement references
         * the same episode ID and states the uncertainty instead of assuming the
         * owner saw the original message.
         */
        fun uncertaintySettlementText(episodeId: String, currentCondition: String): String =
            "เหตุการณ์ $episodeId: ผลการส่งครั้งแรกไม่แน่นอน สถานะปัจจุบัน: $currentCondition"
    }
}
