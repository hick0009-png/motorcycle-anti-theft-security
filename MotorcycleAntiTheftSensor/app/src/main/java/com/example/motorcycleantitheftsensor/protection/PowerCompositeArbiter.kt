package com.example.motorcycleantitheftsensor.protection

/**
 * One combined charging/witness observation during an armed POWER session.
 * `chargingConnected = null` or `witnessLux = null` means the signal is unknown;
 * `fresh = false` marks a stale source. Neither may ever produce an outage claim.
 */
data class PowerSignalSample(
    val chargingConnected: Boolean?,
    val witnessLux: Double?,
    val fresh: Boolean,
    val timestampMs: Long,
)

/** Typed outcomes of one evaluated composite sample (parent spec section 4.3). */
sealed interface PowerArbiterVerdict {
    data class ChargingHealthAlert(val episodeId: String) : PowerArbiterVerdict
    data class WitnessHealthAlert(val episodeId: String) : PowerArbiterVerdict
    data class ConfirmedLossOpened(val episodeId: String) : PowerArbiterVerdict
    data class LossStillConfirmed(val episodeId: String) : PowerArbiterVerdict
    data class ConditionChanged(
        val from: PowerCompositeArbiter.SemanticState,
        val to: PowerCompositeArbiter.SemanticState,
        val episodeId: String,
    ) : PowerArbiterVerdict
    data class PartialRecovery(val episodeId: String) : PowerArbiterVerdict
    data class RecoveredClosed(val episodeId: String) : PowerArbiterVerdict
}

/**
 * Pure armed-session arbiter for Power Guard (parent spec section 4.3 "Decision
 * contract" and "Power episode arbitration and idempotency").
 *
 * Only dual-signal loss opens a confirmed outage; either one-signal condition is a
 * health alert that must never be called a power outage. Each composite state owns
 * its own continuous debounce window: entering dual loss restarts its 10 s timer
 * from zero and never inherits elapsed time from a preceding one-signal condition.
 * All state changes stay inside one episode (`POWER-<n>` per armed session) until
 * both signals have been healthy for the 30-second close window. Stale or ambiguous
 * evidence degrades honestly instead of concluding anything.
 */
class PowerCompositeArbiter(
    private val model: PowerWitnessModel,
    private val settings: PowerProfileSettings,
) {

    enum class SemanticState { HEALTHY_DUAL, CHARGING_LOST, WITNESS_LOST, DUAL_LOST }

    data class State(
        val episodeId: String? = null,
        val episodeCounter: Int = 0,
        val currentSemantic: SemanticState? = null,
        val streakStartMs: Long? = null,
        val streakFired: Boolean = false,
        val healthySinceMs: Long? = null,
        val confirmedLossOpen: Boolean = false,
        val ownerVisibleOpening: Boolean = false,
        val openedAs: SemanticState? = null,
    )

    fun initialState(): State = State()

    fun evaluate(state: State, sample: PowerSignalSample): Pair<PowerArbiterVerdict?, State> {
        // Gate 1: evidence quality. Stale, unknown, or guard-band-ambiguous samples
        // can never advance any window nor conclude an outage.
        if (!sample.fresh || sample.chargingConnected == null || sample.witnessLux == null) {
            return null to resetWindows(state)
        }
        val witnessLit = when {
            sample.witnessLux <= model.darkMaxLux -> false
            sample.witnessLux >= model.litMinLux -> true
            else -> return null to resetWindows(state)
        }
        val semantic = when {
            sample.chargingConnected && witnessLit -> SemanticState.HEALTHY_DUAL
            !sample.chargingConnected && witnessLit -> SemanticState.CHARGING_LOST
            sample.chargingConnected && !witnessLit -> SemanticState.WITNESS_LOST
            else -> SemanticState.DUAL_LOST
        }

        if (semantic != state.currentSemantic) {
            return onSemanticChange(state, semantic, sample.timestampMs)
        }
        return onSameSemantic(state, semantic, sample.timestampMs)
    }

    private fun onSemanticChange(
        state: State,
        semantic: SemanticState,
        timestampMs: Long,
    ): Pair<PowerArbiterVerdict?, State> {
        // Leaving confirmed dual loss toward a one-signal state is partial recovery:
        // the dashboard updates but the episode stays open without a close message.
        val partial = if (
            state.currentSemantic == SemanticState.DUAL_LOST &&
            state.confirmedLossOpen &&
            state.episodeId != null &&
            semantic != SemanticState.HEALTHY_DUAL
        ) {
            PowerArbiterVerdict.PartialRecovery(state.episodeId)
        } else {
            null
        }
        val next = state.copy(
            currentSemantic = semantic,
            streakStartMs = timestampMs,
            streakFired = false,
            healthySinceMs = if (semantic == SemanticState.HEALTHY_DUAL) timestampMs else null,
        )
        return partial to next
    }

    private fun onSameSemantic(
        state: State,
        semantic: SemanticState,
        timestampMs: Long,
    ): Pair<PowerArbiterVerdict?, State> {
        if (semantic == SemanticState.HEALTHY_DUAL) {
            val since = state.healthySinceMs ?: timestampMs
            val episodeId = state.episodeId
            if (episodeId != null && timestampMs - since >= settings.recoveryConfirmationMs) {
                return PowerArbiterVerdict.RecoveredClosed(episodeId) to clearEpisode(state)
            }
            return null to state.copy(healthySinceMs = since)
        }
        val streakStart = state.streakStartMs ?: timestampMs
        val elapsed = timestampMs - streakStart
        if (state.streakFired || elapsed < settings.lossConfirmationMs) {
            return null to state.copy(streakStartMs = streakStart, healthySinceMs = null)
        }
        return fire(state, semantic, timestampMs)
    }

    private fun fire(
        state: State,
        semantic: SemanticState,
        timestampMs: Long,
    ): Pair<PowerArbiterVerdict?, State> {
        val marked = state.copy(streakFired = true, healthySinceMs = null)
        if (semantic == SemanticState.DUAL_LOST) {
            val existing = state.episodeId
            if (existing == null) {
                val created = newEpisode(marked)
                return PowerArbiterVerdict.ConfirmedLossOpened(created.episodeId!!) to created.copy(
                    currentSemantic = semantic,
                    confirmedLossOpen = true,
                    ownerVisibleOpening = true,
                    openedAs = SemanticState.DUAL_LOST,
                )
            }
            return if (!state.confirmedLossOpen) {
                PowerArbiterVerdict.ConfirmedLossOpened(existing) to marked.copy(
                    confirmedLossOpen = true,
                    ownerVisibleOpening = true,
                    openedAs = SemanticState.DUAL_LOST,
                )
            } else {
                PowerArbiterVerdict.LossStillConfirmed(existing) to marked.copy(
                    ownerVisibleOpening = true,
                    openedAs = SemanticState.DUAL_LOST,
                )
            }
        }
        // One-signal conditions are health alerts, never outages.
        val episodeId = state.episodeId
        return when {
            episodeId == null -> {
                val created = newEpisode(marked)
                healthAlert(semantic, created.episodeId!!) to created.copy(
                    currentSemantic = semantic,
                    ownerVisibleOpening = true,
                    openedAs = semantic,
                )
            }
            !state.ownerVisibleOpening ->
                healthAlert(semantic, episodeId) to marked.copy(
                    ownerVisibleOpening = true,
                    openedAs = semantic,
                )
            state.openedAs != semantic ->
                PowerArbiterVerdict.ConditionChanged(state.openedAs!!, semantic, episodeId) to marked.copy(
                    openedAs = semantic,
                )
            // Same condition already reached the owner: no continuation spam.
            else -> null to marked
        }
    }

    private fun healthAlert(semantic: SemanticState, episodeId: String): PowerArbiterVerdict =
        if (semantic == SemanticState.CHARGING_LOST) {
            PowerArbiterVerdict.ChargingHealthAlert(episodeId)
        } else {
            PowerArbiterVerdict.WitnessHealthAlert(episodeId)
        }

    /** Opens the next numbered episode inside this armed session. */
    private fun newEpisode(state: State): State = state.copy(
        episodeId = "POWER-${state.episodeCounter + 1}",
        episodeCounter = state.episodeCounter + 1,
    )

    private fun clearEpisode(state: State): State = state.copy(
        episodeId = null,
        confirmedLossOpen = false,
        ownerVisibleOpening = false,
        openedAs = null,
        streakStartMs = null,
        streakFired = false,
        healthySinceMs = null,
    )

    private fun resetWindows(state: State): State = state.copy(
        streakStartMs = null,
        streakFired = false,
        healthySinceMs = null,
    )
}
