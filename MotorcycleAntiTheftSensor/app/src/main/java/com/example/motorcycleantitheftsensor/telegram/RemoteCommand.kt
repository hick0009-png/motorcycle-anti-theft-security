package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile

sealed interface RemoteCommand {
    data object Help : RemoteCommand
    data object Status : RemoteCommand

    /**
     * `/status ประตู` — the report for a mode that is not the one running.
     *
     * The owner who keeps two modes set up switches between them in the app and then, hours
     * later, wants to know whether the other one is still calibrated and could be armed.
     * Until this existed the only answer available was the running mode's.
     *
     * @param profile null when [argument] matched no mode. Carried rather than collapsed to
     *   [Unknown] so that the reply can name the words that do work, which is the whole
     *   difference between a typo and a dead end.
     */
    data class StatusForMode(
        val profile: ProtectionProfile?,
        val argument: String,
    ) : RemoteCommand
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
                "/status" -> if (argument == null) {
                    Status
                } else {
                    StatusForMode(PresentationTextCatalog.profileFromOwnerWord(argument), argument)
                }
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
