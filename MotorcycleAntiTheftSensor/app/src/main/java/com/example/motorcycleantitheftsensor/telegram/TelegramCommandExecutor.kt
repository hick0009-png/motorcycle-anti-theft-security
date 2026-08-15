package com.example.motorcycleantitheftsensor.telegram

interface TelegramCommandExecutor {
    suspend fun handle(
        commandId: String,
        command: RemoteCommand,
        reply: suspend (String) -> Unit,
    )
}
