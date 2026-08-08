package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.security.TotpAuthenticator.VerificationResult

sealed interface RemoteCommand {
    data object Help : RemoteCommand
    data object Status : RemoteCommand
    data object Arm : RemoteCommand
    data class Disarm(val code: String?) : RemoteCommand
    data class Sensitivity(val level: Int?) : RemoteCommand
    data class Decode(val payload: String) : RemoteCommand
    data class Pair(val code: String?) : RemoteCommand
    data object Unknown : RemoteCommand

    companion object {
        fun parse(text: String): RemoteCommand {
            val parts = text.trim().split("\\s+".toRegex(), limit = 2)
            val argument = parts.getOrNull(1)
            return when (parts.firstOrNull()?.lowercase()) {
                "/start", "/help" -> Help
                "/status" -> Status
                "/arm" -> Arm
                "/disarm" -> Disarm(argument)
                "/sensitivity" -> Sensitivity(argument?.toIntOrNull())
                "/decode" -> Decode(argument.orEmpty())
                "/pair" -> Pair(argument)
                else -> Unknown
            }
        }

        fun isDisarmAuthorized(result: VerificationResult): Boolean =
            result == VerificationResult.SUCCESS
    }
}
