package com.example.motorcycleantitheftsensor.protection

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

@JvmInline
value class RecoveryGenerationToken(val value: Long)

class ProtectionCoordinator(
    initialSnapshot: ProtectionSnapshot,
    private val runtime: ProtectionRuntime,
    private val armingDelay: ArmingDelay,
    private val clock: ProtectionClock,
    private val healthPolicy: ProtectionHealthPolicy = ProtectionHealthPolicy(),
    private val incidentCloser: suspend (String) -> Boolean = { true },
    private val durableSnapshotWriter: suspend (ProtectionSnapshot) -> Unit = { },
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    private val commandMutex = Mutex()
    private val disarmPending = AtomicBoolean(false)
    private val armingEpoch = AtomicLong(0L)
    private val incidentEpoch = AtomicLong(0L)
    private val recoveryGeneration = AtomicLong(0L)
    @Volatile private var lastServiceHeartbeatAtMs: Long? = null
    @Volatile private var stateBeforeAlert: ProtectionState? = null
    @Volatile private var stateBeforeOffline: ProtectionState? = null
    @Volatile private var baseDegradationReasons: Set<String> = initialSnapshot.degradationReasons
    private val unavailablePersistence = AtomicReference<Set<PersistenceSource>>(emptySet())
    private val runtimeDegradations = AtomicReference<Set<String>>(emptySet())

    val snapshot: StateFlow<ProtectionSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun arm(
        commandId: String,
        origin: CommandOrigin,
        recoveryToken: RecoveryGenerationToken? = null,
    ): ProtectionCommandResult {
        if (origin != CommandOrigin.RECOVERY) invalidateRecovery()
        if (disarmPending.get()) return result(commandId, CommandOutcome.REJECTED, "Disarm in progress")
        var epoch = -1L
        var armingDegradations = emptySet<String>()
        val immediateResult = commandMutex.withLock {
            if (!recoveryIsCurrent(origin, recoveryToken)) {
                return@withLock result(
                    commandId,
                    CommandOutcome.REJECTED,
                    "Recovery superseded by owner command",
                )
            }
            if (disarmPending.get()) {
                return@withLock result(commandId, CommandOutcome.REJECTED, "Disarm in progress")
            }
            if (snapshot.value.state == ProtectionState.ARMING) {
                return@withLock result(commandId, CommandOutcome.REJECTED, "Arming already in progress")
            }
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
                return@withLock result(
                    commandId = commandId,
                    outcome = CommandOutcome.REJECTED,
                    reason = "Missing required: ${readiness.blockers.sorted().joinToString()}",
                )
            }

            armingDegradations = readiness.degradations
            epoch = armingEpoch.incrementAndGet()
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
                return@withLock result(commandId, CommandOutcome.REJECTED, reason)
            }
            null
        }
        if (immediateResult != null) return immediateResult

        armingDelay.await()
        return commandMutex.withLock {
            if (!recoveryIsCurrent(origin, recoveryToken)) {
                runtime.stopDetectors()
                if (snapshot.value.state == ProtectionState.ARMING) {
                    transition(
                        state = ProtectionState.DISARMED_ONLINE,
                        blockers = emptySet(),
                        degradations = persistenceDegradations(),
                        baseDegradations = emptySet(),
                    )
                }
                return@withLock result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
            }
            if (epoch != armingEpoch.get() || snapshot.value.state != ProtectionState.ARMING) {
                return@withLock result(commandId, CommandOutcome.UNKNOWN, "Arming was cancelled")
            }

            val health = runtime.currentSensorHealth()
            val finalDegradations = armingDegradations +
                unhealthySensorReasons(health) +
                telegramDegradationReasons(snapshot.value) +
                persistenceDegradations()
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
                baseDegradations = armingDegradations,
            )
            result(commandId, CommandOutcome.APPLIED, "Protection active")
        }
    }

    suspend fun disarm(
        commandId: String,
        origin: CommandOrigin,
        recoveryToken: RecoveryGenerationToken? = null,
    ): ProtectionCommandResult {
        val explicitOwnerCommand = origin != CommandOrigin.RECOVERY
        if (explicitOwnerCommand) {
            invalidateRecovery()
            disarmPending.set(true)
            armingEpoch.incrementAndGet()
        }
        return try {
            commandMutex.withLock {
                if (!recoveryIsCurrent(origin, recoveryToken)) {
                    return@withLock result(
                        commandId,
                        CommandOutcome.REJECTED,
                        "Recovery superseded by owner command",
                    )
                }
                if (!explicitOwnerCommand) armingEpoch.incrementAndGet()
                incidentEpoch.incrementAndGet()
                runtime.stopDetectors()
                val incidentHistoryPersisted = incidentCloser("owner disarmed")
                if (!incidentHistoryPersisted) {
                    recordPersistenceFailure(PersistenceSource.INCIDENT_HISTORY)
                }
                stateBeforeAlert = null
                stateBeforeOffline = null
                transition(
                    state = ProtectionState.DISARMED_ONLINE,
                    blockers = emptySet(),
                    degradations = persistenceDegradations(),
                    baseDegradations = emptySet(),
                )
                try {
                    durableSnapshotWriter(snapshot.value)
                    if (incidentHistoryPersisted) {
                        result(commandId, CommandOutcome.APPLIED, "Protection disarmed; remote control remains online")
                    } else {
                        result(
                            commandId,
                            CommandOutcome.UNKNOWN,
                            "Protection stopped, but incident history could not be confirmed",
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordPersistenceFailure(PersistenceSource.SNAPSHOT)
                    result(
                        commandId,
                        CommandOutcome.UNKNOWN,
                        "Protection stopped, but durable disarm could not be confirmed",
                    )
                }
            }
        } finally {
            if (explicitOwnerCommand) disarmPending.set(false)
        }
    }

    fun captureRecoveryToken(): RecoveryGenerationToken =
        RecoveryGenerationToken(recoveryGeneration.get())

    fun invalidateRecovery() {
        recoveryGeneration.incrementAndGet()
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
        updateSnapshot { current -> current.copy(serviceRunning = true) }
    }

    fun recordPersistenceFailure(source: PersistenceSource) {
        if (updatePersistenceSources { current -> current + source }) {
            refreshPersistenceDegradations()
        }
    }

    fun recordPersistenceRecovered(source: PersistenceSource) {
        if (updatePersistenceSources { current -> current - source }) {
            refreshPersistenceDegradations()
        }
    }

    private fun refreshPersistenceDegradations() {
        val persistenceReasons = persistenceDegradations()
        updateSnapshot { current ->
            val remaining = current.degradationReasons - PERSISTENCE_REASON_LABELS
            val updated = remaining + persistenceReasons
            current.copy(
                state = when {
                    current.state == ProtectionState.ARMED_HEALTHY && updated.isNotEmpty() ->
                        ProtectionState.ARMED_DEGRADED
                    current.state == ProtectionState.ARMED_DEGRADED &&
                        updated.isEmpty() &&
                        current.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY ->
                        ProtectionState.ARMED_HEALTHY
                    else -> current.state
                },
                degradationReasons = updated,
            )
        }
    }

    fun recordLocationForegroundRestriction(restricted: Boolean) {
        val reason = "Background location foreground start restricted"
        var changed = false
        while (true) {
            val current = runtimeDegradations.get()
            val updated = if (restricted) current + reason else current - reason
            if (updated == current) break
            if (runtimeDegradations.compareAndSet(current, updated)) {
                changed = true
                break
            }
        }
        if (changed) {
            refreshRuntimeDegradations()
        }
    }

    private fun refreshRuntimeDegradations() {
        val runtimeReasons = runtimeDegradations.get()
        updateSnapshot { current ->
            val remaining = current.degradationReasons - setOf("Background location foreground start restricted")
            val updated = remaining + runtimeReasons
            current.copy(
                state = when {
                    current.state == ProtectionState.ARMED_HEALTHY && updated.isNotEmpty() ->
                        ProtectionState.ARMED_DEGRADED
                    current.state == ProtectionState.ARMED_DEGRADED &&
                        updated.isEmpty() &&
                        current.sensorHealth[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY ->
                        ProtectionState.ARMED_HEALTHY
                    else -> current.state
                },
                degradationReasons = updated,
            )
        }
    }

    fun recordTelegramContact(atMs: Long) {
        updateSnapshot { current ->
            current.copy(
                telegramReachable = true,
                lastTelegramContactAtMs = atMs,
            )
        }
    }

    fun recordTelegramPolling(active: Boolean) {
        updateSnapshot { current ->
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
        stateBeforeOffline = null
        baseDegradationReasons = emptySet()
        updateSnapshot { current ->
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
        normalizedValue: Double? = null,
    ) {
        updateSnapshot { current ->
            current.copy(
                sensorHealth = current.sensorHealth + (
                    kind to SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = atMs,
                        detail = detail,
                        latestReading = createReadingSummary(kind, normalizedValue),
                    )
                ),
                batteryLevelPercent = if (
                    kind == SensorKind.POWER_THERMAL &&
                    detail == "battery_level_percent" &&
                    normalizedValue?.isFinite() == true
                ) {
                    normalizedValue.roundToInt().takeIf { it in 0..100 }
                        ?: current.batteryLevelPercent
                } else {
                    current.batteryLevelPercent
                },
                batteryTemperatureCelsius = if (
                    kind == SensorKind.POWER_THERMAL &&
                    detail == "temperature_celsius" &&
                    normalizedValue?.isFinite() == true
                ) {
                    normalizedValue.toFloat()
                } else {
                    current.batteryTemperatureCelsius
                },
            )
        }
    }

    fun currentIncidentEpoch(): Long = incidentEpoch.get()

    fun acceptsIncident(epoch: Long): Boolean = epoch == incidentEpoch.get() &&
        snapshot.value.state in ACTIVE_INCIDENT_STATES

    @Synchronized
    fun recordIncident(incident: SecurityIncident) {
        updateSnapshot { current ->
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
        updateSnapshot { current ->
            val evaluatedSensors = current.sensorHealth.mapValues { (_, health) ->
                health.copy(state = healthPolicy.sensorState(health, nowMs))
            }
            val serviceFresh = healthPolicy.serviceIsFresh(
                lastServiceHeartbeatAtMs = lastServiceHeartbeatAtMs,
                nowMs = nowMs,
            )
            val telegramReachable = healthPolicy.telegramReachable(
                lastContactAtMs = current.lastTelegramContactAtMs,
                nowMs = nowMs,
            )
            val evaluatedChannels = current.copy(telegramReachable = telegramReachable)
            val liveState = if (current.state == ProtectionState.OFFLINE) {
                stateBeforeOffline ?: ProtectionState.DISARMED_ONLINE
            } else {
                current.state
            }
            val channelDegradations = if (liveState in ARMED_STATES) {
                telegramDegradationReasons(evaluatedChannels)
            } else {
                emptySet()
            }
            val degradations = baseDegradationReasons +
                unhealthySensorReasons(evaluatedSensors) +
                channelDegradations +
                persistenceDegradations() +
                runtimeDegradations.get()
            val evaluatedState = when {
                !serviceFresh -> {
                    if (current.state != ProtectionState.OFFLINE) stateBeforeOffline = current.state
                    ProtectionState.OFFLINE
                }
                liveState == ProtectionState.ALERT_ACTIVE -> ProtectionState.ALERT_ACTIVE
                liveState in ARMED_STATES && degradations.isNotEmpty() -> ProtectionState.ARMED_DEGRADED
                liveState in ARMED_STATES -> ProtectionState.ARMED_HEALTHY
                else -> liveState
            }
            if (serviceFresh) stateBeforeOffline = null

            current.copy(
                state = evaluatedState,
                serviceRunning = serviceFresh,
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
        updateSnapshot { current ->
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

    private inline fun updateSnapshot(transform: (ProtectionSnapshot) -> ProtectionSnapshot) {
        mutableSnapshot.update { current ->
            transform(current).copy(revision = current.revision + 1L)
        }
    }

    private fun recoveryIsCurrent(
        origin: CommandOrigin,
        token: RecoveryGenerationToken?,
    ): Boolean = origin != CommandOrigin.RECOVERY ||
        (token != null && token.value == recoveryGeneration.get())

    private fun updatePersistenceSources(
        transform: (Set<PersistenceSource>) -> Set<PersistenceSource>,
    ): Boolean {
        while (true) {
            val current = unavailablePersistence.get()
            val updated = transform(current)
            if (updated == current) return false
            if (unavailablePersistence.compareAndSet(current, updated)) return true
        }
    }

    private fun persistenceDegradations(): Set<String> = unavailablePersistence.get()
        .mapTo(mutableSetOf()) { source ->
            when (source) {
                PersistenceSource.SNAPSHOT -> SNAPSHOT_PERSISTENCE_DEGRADATION
                PersistenceSource.INCIDENT_HISTORY -> INCIDENT_PERSISTENCE_DEGRADATION
                PersistenceSource.MOVEMENT_TRACKING -> MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION
            }
        }

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

    private fun createReadingSummary(kind: SensorKind, value: Double?): SensorReadingSummary? = when (kind) {
        SensorKind.VIBRATION -> SensorReadingSummary(value, "m/s²", "Acceleration")
        SensorKind.LIGHT -> SensorReadingSummary(value, "lux", "Ambient Light")
        SensorKind.POWER_THERMAL -> SensorReadingSummary(null, null, "Power Status")
        SensorKind.MICROPHONE -> SensorReadingSummary(null, null, "Signal Received")
        SensorKind.LOCATION -> SensorReadingSummary(null, null, "Fix Acquired")
    }

    private companion object {
        val ARMED_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
        )
        val ACTIVE_INCIDENT_STATES = ARMED_STATES + ProtectionState.ALERT_ACTIVE
        const val SNAPSHOT_PERSISTENCE_DEGRADATION = "SNAPSHOT persistence unavailable"
        const val INCIDENT_PERSISTENCE_DEGRADATION = "INCIDENT history unavailable"
        const val MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION = "Movement tracking persistence unavailable"
        val PERSISTENCE_REASON_LABELS = setOf(
            SNAPSHOT_PERSISTENCE_DEGRADATION,
            INCIDENT_PERSISTENCE_DEGRADATION,
            MOVEMENT_TRACKING_PERSISTENCE_DEGRADATION,
        )
    }
}
