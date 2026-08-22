package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.IncidentProgressTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TelegramIncidentProgressTransport(
    httpClient: OkHttpClient,
    private val prefs: EncryptedPrefsManager,
    private val onTelegramContact: (Long) -> Unit,
    private val api: TelegramLiveLocationApi = TelegramLiveLocationApiImpl(
        httpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(),
    ),
) : IncidentProgressTransport {
    private val messageIds = ConcurrentHashMap<String, Long>()

    override suspend fun open(incidentId: String, message: String): Boolean = withContext(Dispatchers.IO) {
        val owners = prefs.getAllowedChatIds()
        val botToken = prefs.getBotToken() ?: return@withContext false
        if (owners.isEmpty()) return@withContext false

        owners.map { chatId ->
            when (val result = api.sendMessage(botToken, chatId, message)) {
                is TelegramCallResult.Success -> {
                    messageIds[key(incidentId, chatId)] = result.value
                    onTelegramContact(System.currentTimeMillis())
                    true
                }
                is TelegramCallResult.Retryable,
                is TelegramCallResult.Terminal,
                -> false
            }
        }.all { it }
    }

    override suspend fun update(incidentId: String, message: String): Boolean = withContext(Dispatchers.IO) {
        val owners = prefs.getAllowedChatIds()
        val botToken = prefs.getBotToken() ?: return@withContext false
        val handles = owners.mapNotNull { chatId ->
            messageIds[key(incidentId, chatId)]?.let { messageId -> chatId to messageId }
        }
        if (handles.isEmpty()) return@withContext false

        handles.map { (chatId, messageId) ->
            when (api.editMessageText(botToken, chatId, messageId, message)) {
                is TelegramCallResult.Success -> {
                    onTelegramContact(System.currentTimeMillis())
                    true
                }
                is TelegramCallResult.Retryable,
                is TelegramCallResult.Terminal,
                -> false
            }
        }.all { it }
    }

    override suspend fun clear(incidentId: String) {
        messageIds.keys.removeIf { key -> key.startsWith("$incidentId:") }
    }

    private fun key(incidentId: String, chatId: String): String = "$incidentId:$chatId"
}
