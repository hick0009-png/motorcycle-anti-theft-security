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
) : TelegramCommandExecutor {
    override suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    ) {
        when (command) {
            RemoteCommand.Arm -> {
                reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_APPLIED).telegramTh!!)
                var armCompleted = false
                try {
                    val result = withTimeoutOrNull(commandTimeoutMs) {
                        coordinator.arm(commandId, CommandOrigin.TELEGRAM)
                    }
                    if (result == null) {
                        coordinator.disarm("$commandId-timeout", CommandOrigin.TELEGRAM)
                        reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.TELEGRAM_UNREACHABLE).telegramTh!!)
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

            RemoteCommand.Disarm -> reply(
                coordinator.disarm(commandId, CommandOrigin.TELEGRAM).toTelegramText(),
            )

            RemoteCommand.Status -> reply(statusFormatter.format(coordinator.snapshot.value))

            is RemoteCommand.Sensitivity -> {
                if (command.level != null) {
                    val r = coordinator.changeSensitivity(commandId, command.level)
                    if (r.outcome == CommandOutcome.APPLIED) {
                        reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_APPLIED).telegramTh!!.replace("{level}", command.level.toString()))
                    } else {
                        reply(r.toTelegramText())
                    }
                } else {
                    reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_INVALID).telegramTh!!)
                }
            }

            RemoteCommand.Help -> reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_HELP).telegramTh!!)

            is RemoteCommand.Decode,
            is RemoteCommand.Pair -> Unit

            RemoteCommand.Unknown -> reply(com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN).telegramTh!!)
        }
    }

    private fun ProtectionCommandResult.toTelegramText(): String {
        val code = if (outcome == CommandOutcome.APPLIED) {
            when (resultingState) {
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ARMING -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_APPLIED
                com.example.motorcycleantitheftsensor.protection.ProtectionState.DISARMED_ONLINE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_APPLIED
                com.example.motorcycleantitheftsensor.protection.ProtectionState.ALERT_ACTIVE -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.ALERT_ACTIVE
                else -> com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_STATUS_SUCCESS
            }
        } else {
            if (reason.contains("Arm", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED
            else if (reason.contains("Disarm", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_REJECTED
            else if (reason.contains("Sensitivity", ignoreCase = true)) com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_INVALID
            else com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_UNKNOWN
        }
        val template = com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(code).telegramTh ?: ""
        return template.replace("{safeReason}", reason).replace("{level}", reason.filter { it.isDigit() }).replace("{protectionStatus}", com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
            resultingState.toGuidanceCode()
        ).titleTh)
    }
}
