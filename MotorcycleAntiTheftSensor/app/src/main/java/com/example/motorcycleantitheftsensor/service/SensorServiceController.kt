package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import kotlinx.coroutines.flow.StateFlow

interface ServiceEnvironment {
    fun ensureForeground()

    suspend fun stopForegroundAndSelf()

    fun ensureTelegramPolling(): Boolean

    fun stopTelegramPolling()

    fun renderNotification(snapshot: ProtectionSnapshot)
}

class SensorServiceController(
    private val coordinator: ProtectionCoordinator,
    private val environment: ServiceEnvironment,
) {
    val snapshot: StateFlow<ProtectionSnapshot> = coordinator.snapshot

    fun refreshTelegramPolling() {
        environment.ensureForeground()
        environment.stopTelegramPolling()
        coordinator.recordTelegramPolling(environment.ensureTelegramPolling())
        environment.renderNotification(coordinator.snapshot.value)
    }

    suspend fun handle(
        action: SensorServiceAction,
        commandId: String,
        origin: CommandOrigin = CommandOrigin.LOCAL,
    ) {
        when (action) {
            SensorServiceAction.Stop -> {
                coordinator.disarm(commandId, origin)
                environment.stopTelegramPolling()
                environment.stopForegroundAndSelf()
                coordinator.recordServiceStopped()
            }

            SensorServiceAction.Disarm -> {
                environment.ensureForeground()
                coordinator.recordTelegramPolling(environment.ensureTelegramPolling())
                coordinator.disarm(commandId, origin)
            }

            SensorServiceAction.Arm -> {
                environment.ensureForeground()
                coordinator.recordTelegramPolling(environment.ensureTelegramPolling())
                coordinator.arm(commandId, origin)
            }

            SensorServiceAction.Start,
            SensorServiceAction.Ignore,
            -> {
                environment.ensureForeground()
                coordinator.recordTelegramPolling(environment.ensureTelegramPolling())
            }
        }
        environment.renderNotification(coordinator.snapshot.value)
    }
}
