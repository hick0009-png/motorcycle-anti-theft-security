package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

interface ServiceEnvironment {
    fun ensureForeground()

    suspend fun stopForegroundAndSelf()

    fun ensureTelegramPolling(): Boolean

    fun stopTelegramPolling()

    suspend fun refreshTelegramPolling(): Boolean

    fun renderNotification(snapshot: ProtectionSnapshot)
}

class SensorServiceController(
    private val coordinator: ProtectionCoordinator,
    private val environment: ServiceEnvironment,
) {
    val snapshot: StateFlow<ProtectionSnapshot> = coordinator.snapshot

    suspend fun refreshTelegramPolling() {
        environment.ensureForeground()
        coordinator.recordTelegramPolling(environment.refreshTelegramPolling())
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

internal class TelegramPollingRefreshBoundary(
    private val stopAndAwait: suspend () -> Unit,
    private val resetCursor: suspend () -> Boolean,
    private val start: () -> Boolean,
) {
    suspend fun refresh(): Boolean = try {
        stopAndAwait()
        if (!resetCursor()) false else start()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }
}
