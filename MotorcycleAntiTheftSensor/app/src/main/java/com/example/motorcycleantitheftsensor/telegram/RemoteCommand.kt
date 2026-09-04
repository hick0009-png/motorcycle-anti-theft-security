package com.example.motorcycleantitheftsensor.telegram

sealed interface RemoteCommand {
    data object Help : RemoteCommand
    data object Status : RemoteCommand
    data object Arm : RemoteCommand
    data object Disarm : RemoteCommand
    data class Sensitivity(val level: Int?) : RemoteCommand
    data class Decode(val payload: String) : RemoteCommand
    data class Pair(val code: String?) : RemoteCommand

    /**
     * Where the vehicle is, asked for rather than waited for.
     *
     * Until this existed the only way a coordinate left the phone was an alert opening a
     * pursuit of its own accord, which lasts fifteen minutes and then stops. After that the
     * owner of a vehicle still missing had nothing to send and no way to ask.
     */
    data object Where : RemoteCommand

    data object Unknown : RemoteCommand

    companion object {
        fun parse(text: String): RemoteCommand {
            val parts = text.trim().split("\\s+".toRegex(), limit = 2)
            val argument = parts.getOrNull(1)
            val command = parts.firstOrNull()?.substringBefore('@')?.lowercase()
            return when (command) {
                "/start", "/help" -> if (argument == null) Help else Unknown
                "/status" -> if (argument == null) Status else Unknown
                "/arm" -> if (argument == null) Arm else Unknown
                "/disarm" -> if (argument == null) Disarm else Unknown
                // Both spellings: the owner reaching for this is not in a state to remember
                // which word the manual used.
                "/where", "/locate" -> if (argument == null) Where else Unknown
                "/sensitivity" -> Sensitivity(argument?.toIntOrNull())
                "/decode" -> Decode(argument.orEmpty())
                "/pair" -> Pair(argument)
                else -> Unknown
            }
        }
    }
}
