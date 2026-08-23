package com.example.motorcycleantitheftsensor.protection

/**
 * Semantic owner-visible transitions of a Power Guard episode (parent spec section
 * 4.3). Each genuine semantic change enqueues exactly one durable outbox item.
 */
enum class PowerTransitionKind {
    CHARGING_HEALTH_OPENING,
    WITNESS_HEALTH_OPENING,
    CONFIRMED_LOSS_OPENING,
    CONDITION_CHANGED,
    ONE_SIGNAL_RECOVERY,
    CONFIRMED_CLOSE,
    UNCERTAINTY_SETTLEMENT,
}

/**
 * Durable delivery states of one Power Guard outbox item (parent spec section 4.3).
 * A superseded item must never send later when connectivity returns; an in-flight
 * item cannot be assumed cancelled; an uncertain acceptance is never a failure.
 */
enum class PowerOutboxState {
    PENDING,
    IN_FLIGHT,
    ACCEPTED,
    DELIVERY_UNCERTAIN,
    SUPERSEDED,
    FAILED_FINAL,
}

/**
 * One durable remote transition keyed by
 * `armedSessionId + powerEpisodeId + transitionOrdinal + transitionKind`.
 */
data class PowerOutboxItem(
    val armedSessionId: String,
    val powerEpisodeId: String,
    val transitionOrdinal: Long,
    val kind: PowerTransitionKind,
    val text: String,
    val state: PowerOutboxState = PowerOutboxState.PENDING,
    val createdAtMs: Long,
) {
    val outboxKey: String
        get() = "$armedSessionId|$powerEpisodeId|$transitionOrdinal|$kind"
}

/**
 * Pure durable-outbox policy for Power Guard episodes (parent spec section 4.3).
 *
 * The caller (the serialized episode arbiter) invokes [enqueue] only when the stable
 * semantic state genuinely changes, so the monotonic [PowerOutboxItem.transitionOrdinal]
 * advances once per real transition and repeated samples never enqueue another item.
 * Enqueueing supersedes every not-yet-started PENDING copy of the same episode first;
 * state transitions are guarded so superseded items can never enter flight.
 */
class PowerEpisodeOutboxPolicy(private val nowMs: () -> Long) {

    data class State(
        val nextOrdinal: Long = 1L,
        val items: List<PowerOutboxItem> = emptyList(),
    )

    /** Only a not-yet-started item may be dispatched to the transport. */
    fun canDispatch(item: PowerOutboxItem): Boolean = item.state == PowerOutboxState.PENDING

    /**
     * Records the newer semantic condition: marks any not-started obsolete PENDING
     * item of the same episode SUPERSEDED, then appends the new PENDING copy with the
     * next monotonic ordinal.
     */
    fun enqueue(
        state: State,
        armedSessionId: String,
        powerEpisodeId: String,
        kind: PowerTransitionKind,
        text: String,
    ): Pair<PowerOutboxItem, State> {
        val superseded = state.items.map { item ->
            if (item.state == PowerOutboxState.PENDING && item.powerEpisodeId == powerEpisodeId) {
                item.copy(state = PowerOutboxState.SUPERSEDED)
            } else {
                item
            }
        }
        val item = PowerOutboxItem(
            armedSessionId = armedSessionId,
            powerEpisodeId = powerEpisodeId,
            transitionOrdinal = state.nextOrdinal,
            kind = kind,
            text = text,
            state = PowerOutboxState.PENDING,
            createdAtMs = nowMs(),
        )
        return item to State(nextOrdinal = state.nextOrdinal + 1, items = superseded + item)
    }

    private fun transition(
        state: State,
        item: PowerOutboxItem,
        allowedFrom: Set<PowerOutboxState>,
        target: PowerOutboxState,
    ): State {
        // The guard reads the DURABLE stored state, not the caller's possibly stale
        // snapshot, so chained transitions in one expression behave correctly.
        val stored = state.items.firstOrNull { it.outboxKey == item.outboxKey } ?: return state
        if (stored.state !in allowedFrom) return state
        return state.copy(
            items = state.items.map { existing ->
                if (existing.outboxKey == item.outboxKey) existing.copy(state = target) else existing
            },
        )
    }

    /** PENDING → IN_FLIGHT. An IN_FLIGHT item cannot re-enter flight in parallel. */
    fun markInFlight(state: State, item: PowerOutboxItem): State =
        transition(state, item, allowedFrom = setOf(PowerOutboxState.PENDING), target = PowerOutboxState.IN_FLIGHT)

    /** IN_FLIGHT → ACCEPTED. Durable accepted receipts suppress duplicate delivery. */
    fun markAccepted(state: State, item: PowerOutboxItem): State =
        transition(state, item, allowedFrom = setOf(PowerOutboxState.IN_FLIGHT), target = PowerOutboxState.ACCEPTED)

    /**
     * IN_FLIGHT → DELIVERY_UNCERTAIN. Telegram provides no server-side idempotency
     * key, so a crash/timeout after possible acceptance is uncertainty, not failure.
     */
    fun markUncertain(state: State, item: PowerOutboxItem): State =
        transition(state, item, allowedFrom = setOf(PowerOutboxState.IN_FLIGHT), target = PowerOutboxState.DELIVERY_UNCERTAIN)

    /** IN_FLIGHT → FAILED_FINAL. Retires the episode for the transport path. */
    fun markFailedFinal(state: State, item: PowerOutboxItem): State =
        transition(state, item, allowedFrom = setOf(PowerOutboxState.IN_FLIGHT), target = PowerOutboxState.FAILED_FINAL)
}
