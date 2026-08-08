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
) {
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)
    private val armingEpoch = AtomicLong(0L)

    val snapshot: StateFlow<ProtectionSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun arm(
        commandId: String,
        origin: CommandOrigin,
    ): ProtectionCommandResult {
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
        val vibrationHealthy = health[SensorKind.VIBRATION]?.state == SensorHealthState.HEALTHY
        val finalState = if (readiness.degradations.isEmpty() && vibrationHealthy) {
            ProtectionState.ARMED_HEALTHY
        } else {
            ProtectionState.ARMED_DEGRADED
        }
        transition(
            state = finalState,
            blockers = emptySet(),
            degradations = readiness.degradations,
            sensorHealth = health,
        )
        return result(commandId, CommandOutcome.APPLIED, "Protection active")
    }

    suspend fun disarm(
        commandId: String,
        origin: CommandOrigin,
    ): ProtectionCommandResult {
        armingEpoch.incrementAndGet()
        runtime.stopDetectors()
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

    private fun transition(
        state: ProtectionState,
        blockers: Set<String>,
        degradations: Set<String>,
        sensorHealth: Map<SensorKind, SensorHealth> = snapshot.value.sensorHealth,
    ) {
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
}
