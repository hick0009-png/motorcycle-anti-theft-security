package com.example.motorcycleantitheftsensor.protection

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ProtectionCoordinator(
    initialSnapshot: ProtectionSnapshot,
    private val runtime: ProtectionRuntime,
    private val armingDelay: ArmingDelay,
    private val clock: ProtectionClock,
    private val healthPolicy: ProtectionHealthPolicy = ProtectionHealthPolicy(),
    private val incidentCloser: suspend (String) -> Unit = { },
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    private val armingEpoch = AtomicLong(0L)
    private val incidentEpoch = AtomicLong(0L)
    @Volatile private var lastServiceHeartbeatAtMs: Long? = null
    @Volatile private var stateBeforeAlert: ProtectionState? = null
    @Volatile private var baseDegradationReasons: Set<String> = initialSnapshot.degradationReasons

    val snapshot: StateFlow<ProtectionSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun arm(
        commandId: String,
        origin: CommandOrigin,
    ): ProtectionCommandResult {
        incidentEpoch.incrementAndGet()
        val readiness = runtime.readiness()
        if (readiness.blockers.isNotEmpty()) {
            armingEpoch.incrementAndGet()
            runtime.stopDetectors()
            transition(
                state = ProtectionState.SETUP_REQUIRED,
                blockers = readiness.blockers,
                degradations = readiness.degradations,
            )
            return result(
                commandId = commandId,
                outcome = CommandOutcome.REJECTED,
                reason = "Missing required: ${readiness.blockers.sorted().joinToString()}",
            )
        }

        val epoch = armingEpoch.incrementAndGet()
        transition(
            state = ProtectionState.ARMING,
            blockers = emptySet(),
            degradations = readiness.degradations,
        )

        val startResult = runtime.startDetectors()
        if (!startResult.started) {
            armingEpoch.incrementAndGet()
            runtime.stopDetectors()
            val reason = startResult.failureReason ?: "Detector startup failed"
            transition(
                state = ProtectionState.DISARMED_ONLINE,
                blockers = emptySet(),
                degradations = setOf(reason),
            )
            return result(commandId, CommandOutcome.REJECTED, reason)
        }

        armingDelay.await()
        if (epoch != armingEpoch.get() || snapshot.value.state != ProtectionState.ARMING) {
            return result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
        }

        val health = runtime.currentSensorHealth()
        val finalDegradations = readiness.degradations +
            unhealthySensorReasons(health) +
            telegramDegradationReasons(snapshot.value)
        val finalState = if (finalDegradations.isEmpty()) {
            ProtectionState.ARMED_HEALTHY
        } else {
            ProtectionState.ARMED_DEGRADED
        }
        transition(
            state = finalState,
            blockers = emptySet(),
            degradations = finalDegradations,
            sensorHealth = health,
            baseDegradations = readiness.degradations,
        )
        return result(commandId, CommandOutcome.APPLIED, "Protection active")
    }

    suspend fun disarm(
        commandId: String,
        origin: CommandOrigin,
    ): ProtectionCommandResult {
        armingEpoch.incrementAndGet()
        incidentEpoch.incrementAndGet()
        runtime.stopDetectors()
        incidentCloser("owner disarmed")
        stateBeforeAlert = null
        transition(
            state = ProtectionState.DISARMED_ONLINE,
            blockers = emptySet(),
            degradations = emptySet(),
        )
        return result(commandId, CommandOutcome.APPLIED, "Protection disarmed; remote control remains online")
    }

    fun changeSensitivity(
        commandId: String,
        level: Int,
    ): ProtectionCommandResult {
        if (level !in 1..10) {
            return result(commandId, CommandOutcome.REJECTED, "Sensitivity must be 1-10")
        }
        runtime.applySensitivity(level)
        return result(commandId, CommandOutcome.APPLIED, "Sensitivity applied: $level")
    }

    fun recordServiceHeartbeat(atMs: Long) {
        lastServiceHeartbeatAtMs = atMs
        mutableSnapshot.update { current -> current.copy(serviceRunning = true) }
    }

    fun recordTelegramContact(atMs: Long) {
        mutableSnapshot.update { current ->
            current.copy(
                telegramReachable = true,
                lastTelegramContactAtMs = atMs,
            )
        }
    }

    fun recordTelegramPolling(active: Boolean) {
        mutableSnapshot.update { current ->
            current.copy(
                telegramPolling = active,
                telegramReachable = if (active) current.telegramReachable else false,
            )
        }
    }

    fun recordServiceStopped() {
        armingEpoch.incrementAndGet()
        incidentEpoch.incrementAndGet()
        stateBeforeAlert = null
        baseDegradationReasons = emptySet()
        mutableSnapshot.update { current ->
            current.copy(
                state = ProtectionState.OFFLINE,
                lastTransitionAtMs = clock.nowMs(),
                serviceRunning = false,
                telegramPolling = false,
                telegramReachable = false,
            )
        }
    }

    fun recordSensorSample(
        kind: SensorKind,
        atMs: Long,
        detail: String? = null,
    ) {
        mutableSnapshot.update { current ->
            current.copy(
                sensorHealth = current.sensorHealth + (
                    kind to SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = atMs,
                        detail = detail,
                    )
                ),
            )
        }
    }

    fun currentIncidentEpoch(): Long = incidentEpoch.get()

    fun acceptsIncident(epoch: Long): Boolean = epoch == incidentEpoch.get() &&
        snapshot.value.state in ACTIVE_INCIDENT_STATES

    @Synchronized
    fun recordIncident(incident: SecurityIncident) {
        mutableSnapshot.update { current ->
            val canChangeProtectionState = current.state in ARMED_STATES ||
                current.state == ProtectionState.ALERT_ACTIVE
            val nextState = if (!canChangeProtectionState) {
                current.state
            } else when (incident.lifecycle) {
                IncidentLifecycle.OPEN -> {
                    if (current.state != ProtectionState.ALERT_ACTIVE) {
                        stateBeforeAlert = current.state.takeIf { state -> state in ARMED_STATES }
                    }
                    ProtectionState.ALERT_ACTIVE
                }

                IncidentLifecycle.CLOSED,
                IncidentLifecycle.INTERRUPTED,
                -> stateBeforeAlert ?: armedStateFrom(current)
            }
            if (incident.lifecycle != IncidentLifecycle.OPEN || !canChangeProtectionState) {
                stateBeforeAlert = null
            }
            current.copy(
                state = nextState,
                lastTransitionAtMs = clock.nowMs(),
                lastIncident = IncidentSummary(
                    id = incident.id,
                    source = incident.source,
                    severity = incident.severity,
                    lifecycle = incident.lifecycle,
                    updatedAtMs = incident.updatedAtMs,
                    deliveryState = incident.deliveryState,
                ),
                lastDeliveryState = incident.deliveryState,
            )
        }
    }

    fun evaluateFreshness(nowMs: Long) {
        mutableSnapshot.update { current ->
            val evaluatedSensors = current.sensorHealth.mapValues { (_, health) ->
                health.copy(state = healthPolicy.sensorState(health, nowMs))
            }
            val runtimeState = healthPolicy.runtimeState(
                current = current.state,
                lastServiceHeartbeatAtMs = lastServiceHeartbeatAtMs,
                nowMs = nowMs,
            )
            val telegramReachable = healthPolicy.telegramReachable(
                lastContactAtMs = current.lastTelegramContactAtMs,
                nowMs = nowMs,
            )
            val evaluatedChannels = current.copy(telegramReachable = telegramReachable)
            val degradations = baseDegradationReasons +
                unhealthySensorReasons(evaluatedSensors) +
                if (current.state in ARMED_STATES) telegramDegradationReasons(evaluatedChannels) else emptySet()
            val evaluatedState = when {
                runtimeState == ProtectionState.OFFLINE -> ProtectionState.OFFLINE
                current.state == ProtectionState.ALERT_ACTIVE -> ProtectionState.ALERT_ACTIVE
                current.state in ARMED_STATES && degradations.isNotEmpty() -> ProtectionState.ARMED_DEGRADED
                current.state in ARMED_STATES -> ProtectionState.ARMED_HEALTHY
                else -> current.state
            }

            current.copy(
                state = evaluatedState,
                serviceRunning = runtimeState != ProtectionState.OFFLINE,
                telegramReachable = telegramReachable,
                sensorHealth = evaluatedSensors,
                degradationReasons = degradations,
            )
        }
    }

    private fun transition(
        state: ProtectionState,
        blockers: Set<String>,
        degradations: Set<String>,
        sensorHealth: Map<SensorKind, SensorHealth> = snapshot.value.sensorHealth,
        baseDegradations: Set<String> = degradations,
    ) {
        baseDegradationReasons = baseDegradations
        mutableSnapshot.update { current ->
            current.copy(
                state = state,
                lastTransitionAtMs = clock.nowMs(),
                permissionBlockers = blockers,
                degradationReasons = degradations,
                sensorHealth = sensorHealth,
            )
        }
    }

    private fun result(
        commandId: String,
        outcome: CommandOutcome,
        reason: String,
    ): ProtectionCommandResult = ProtectionCommandResult(
        commandId = commandId,
        outcome = outcome,
        resultingState = snapshot.value.state,
        reason = reason,
    )

    private fun armedStateFrom(snapshot: ProtectionSnapshot): ProtectionState = if (
        snapshot.degradationReasons.isEmpty() &&
        snapshot.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY
    ) {
        ProtectionState.ARMED_HEALTHY
    } else {
        ProtectionState.ARMED_DEGRADED
    }

    private fun unhealthySensorReasons(
        health: Map<SensorKind, SensorHealth>,
    ): Set<String> = health
        .filterValues { item -> item.state != SensorHealthState.HEALTHY }
        .keys
        .mapTo(mutableSetOf()) { kind -> "$kind not healthy" }

    private fun telegramDegradationReasons(snapshot: ProtectionSnapshot): Set<String> = buildSet {
        if (!snapshot.telegramPolling) add("TELEGRAM polling inactive")
        if (!snapshot.telegramReachable) add("TELEGRAM unreachable")
    }

    private companion object {
        val ARMED_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
        )
        val ACTIVE_INCIDENT_STATES = ARMED_STATES + ProtectionState.ALERT_ACTIVE
    }
}
