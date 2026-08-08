package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.CommandOrigin
import com.example.motorcycleantitheftsensor.protection.CommandOutcome
import com.example.motorcycleantitheftsensor.protection.ProtectionCommandResult
import com.example.motorcycleantitheftsensor.protection.ProtectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TelegramCommandHandler(
    private val coordinator: ProtectionCoordinator,
    private val statusFormatter: ProtectionStatusFormatter,
    private val commandTimeoutMs: Long = 20_000L,
) {
    suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    ) {
        when (command) {
            RemoteCommand.Arm -> {
                reply("ARM RECEIVED — checking readiness")
                var armCompleted = false
                try {
                    val result = withTimeoutOrNull(commandTimeoutMs) {
                        coordinator.arm(commandId, CommandOrigin.TELEGRAM)
                    }
                    if (result == null) {
                        coordinator.disarm("$commandId-timeout", CommandOrigin.TELEGRAM)
                        reply("UNKNOWN — command timed out; protection disarmed; request /status")
                    } else {
                        armCompleted = true
                        reply(result.toTelegramText())
                    }
                } catch (cancelled: CancellationException) {
                    if (!armCompleted) {
                        withContext(NonCancellable) {
                            coordinator.disarm("$commandId-cancelled", CommandOrigin.TELEGRAM)
                        }
                    }
                    throw cancelled
                }
            }

            is RemoteCommand.Disarm -> reply(
                coordinator.disarm(commandId, CommandOrigin.TELEGRAM).toTelegramText(),
            )

            RemoteCommand.Status -> reply(statusFormatter.format(coordinator.snapshot.value))

            is RemoteCommand.Sensitivity -> {
                val result = command.level?.let { level ->
                    coordinator.changeSensitivity(commandId, level)
                } ?: ProtectionCommandResult(
                    commandId = commandId,
                    outcome = CommandOutcome.REJECTED,
                    resultingState = coordinator.snapshot.value.state,
                    reason = "Usage: /sensitivity 1-10",
                )
                reply(result.toTelegramText())
            }

            RemoteCommand.Help -> reply(HELP_TEXT)

            is RemoteCommand.Decode,
            is RemoteCommand.Pair,
            RemoteCommand.Unknown,
            -> Unit
        }
    }

    private fun ProtectionCommandResult.toTelegramText(): String =
        "$outcome — $resultingState — $reason"

    private companion object {
        const val HELP_TEXT = "Commands: /status, /arm, /disarm <TOTP>, /sensitivity 1-10, /decode <payload>"
    }
}
