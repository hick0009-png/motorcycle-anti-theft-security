package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.location.OnDemandLocationFinder
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
    /**
     * Absent on a build with no location layer wired, in which case the command says so
     * rather than the handler pretending the feature exists.
     */
    private val locationFinder: OnDemandLocationFinder? = null,
    private val commandTimeoutMs: Long = 20_000L,
) : TelegramCommandExecutor {
    override suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    ) {
        when (command) {
            RemoteCommand.Arm -> {
                var armCompleted = false
                try {
                    val result = withTimeoutOrNull(commandTimeoutMs) {
                        coordinator.arm(commandId, CommandOrigin.TELEGRAM)
                    }
                    if (result == null) {
                        coordinator.disarm("$commandId-timeout", CommandOrigin.TELEGRAM)
                        val rejectResult = ProtectionCommandResult(
                            commandId = "$commandId-timeout",
                            outcome = CommandOutcome.REJECTED,
                            resultingState = coordinator.snapshot.value.state,
                            reason = "Arming calibration timed out",
                        )
                        reply(rejectResult.toTelegramText())
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

            RemoteCommand.Where -> {
                val finder = locationFinder
                if (finder == null) {
                    reply("❌ เครื่องนี้ยังไม่ได้เปิดใช้งานระบบระบุตำแหน่ง")
                } else {
                    // Told before waited for. A cold fix takes tens of seconds, and silence
                    // from the phone is exactly what the owner is afraid of right now.
                    reply("🔎 กำลังหาตำแหน่ง รอสักครู่…")
                    reply(LocationAnswerFormatter.format(finder.find()))
                }
            }

            is RemoteCommand.Sensitivity -> {
                if (command.level != null) {
                    val r = coordinator.changeSensitivity(commandId, command.level)
                    if (r.outcome == CommandOutcome.APPLIED) {
                        reply(
                            com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
                                com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_SENSITIVITY_APPLIED,
                                com.example.motorcycleantitheftsensor.protection.GuidanceDetail.SensitivityLevel(command.level),
                            ).telegramTh!!
                        )
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
        val base = com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(code).telegramTh ?: ""
        val withReason = when (code) {
            com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_ARM_REJECTED -> "⚠️ เปิดการป้องกันไม่ได้: $reason"
            com.example.motorcycleantitheftsensor.protection.GuidanceCode.COMMAND_DISARM_REJECTED -> "⚠️ ปลดการป้องกันไม่ได้: $reason"
            else -> base
        }
        return withReason
            .replace("{level}", reason.filter { it.isDigit() })
            .replace(
                "{protectionStatus}",
                com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog.content(
                    resultingState.toGuidanceCode()
                ).titleTh,
            )
    }
}
