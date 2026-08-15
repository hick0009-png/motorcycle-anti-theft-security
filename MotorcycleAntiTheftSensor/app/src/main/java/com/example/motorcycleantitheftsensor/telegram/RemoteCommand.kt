package com.example.motorcycleantitheftsensor.telegram

sealed interface RemoteCommand {
    data object Help : RemoteCommand
    data object Status : RemoteCommand
    data object Arm : RemoteCommand
    data object Disarm : RemoteCommand
    data class Sensitivity(val level: Int?) : RemoteCommand
    data class Decode(val payload: String) : RemoteCommand
    data class Pair(val code: String?) : RemoteCommand
    data object Unknown : RemoteCommand

    companion object {
        fun parse(text: String): RemoteCommand {
            val parts = text.trim().split("\\s+".toRegex(), limit = 2)
            val argument = parts.getOrNull(1)
            return when (parts.firstOrNull()?.lowercase()) {
                "/start", "/help" -> if (argument == null) Help else Unknown
                "/status" -> if (argument == null) Status else Unknown
                "/arm" -> if (argument == null) Arm else Unknown
                "/disarm" -> if (argument == null) Disarm else Unknown
                "/sensitivity" -> Sensitivity(argument?.toIntOrNull())
                "/decode" -> Decode(argument.orEmpty())
                "/pair" -> Pair(argument)
                else -> Unknown
            }
        }
    }
}
