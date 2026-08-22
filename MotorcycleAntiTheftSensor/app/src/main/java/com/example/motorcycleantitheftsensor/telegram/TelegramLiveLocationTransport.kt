package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.location.LiveLocationHandle
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface TelegramLiveLocationTransport {
    suspend fun startForOwners(fix: TrackedLocationFix, livePeriodSeconds: Int): List<LiveLocationHandle>
    suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit>
    suspend fun stop(handle: LiveLocationHandle): TelegramCallResult<Unit>
    suspend fun alertOwners(text: String): Boolean
}

class TelegramLiveLocationTransportImpl(
    httpClient: OkHttpClient,
    private val prefs: EncryptedPrefsManager,
    private val api: TelegramLiveLocationApi = TelegramLiveLocationApiImpl(
        httpClient.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    ),
) : TelegramLiveLocationTransport {

    override suspend fun startForOwners(fix: TrackedLocationFix, livePeriodSeconds: Int): List<LiveLocationHandle> = withContext(Dispatchers.IO) {
        val handles = mutableListOf<LiveLocationHandle>()
        val owners = prefs.getAllowedChatIds()
        if (owners.isEmpty()) return@withContext handles

        val botToken = prefs.getBotToken() ?: return@withContext handles

        for (chatId in owners) {
            val result = api.sendLocation(
                botToken = botToken,
                chatId = chatId,
                latitude = fix.latitude,
                longitude = fix.longitude,
                livePeriodSeconds = livePeriodSeconds,
                horizontalAccuracyMeters = fix.accuracyMeters,
            )
            if (result is TelegramCallResult.Success) {
                handles.add(LiveLocationHandle(chatId, result.value))
            }
        }
        handles
    }

    private val lastUpdateMsByHandle = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val MIN_UPDATE_INTERVAL_MS = 2_000L

    override suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit> = withContext(Dispatchers.IO) {
        val owners = prefs.getAllowedChatIds()
        if (!owners.contains(handle.chatId)) {
            return@withContext TelegramCallResult.Terminal(TelegramFailureCode.FORBIDDEN)
        }
        val botToken = prefs.getBotToken() ?: return@withContext TelegramCallResult.Terminal(TelegramFailureCode.UNAUTHORIZED)

        val key = "${handle.chatId}:${handle.messageId}"
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateMsByHandle[key] ?: 0L
        if (now - lastUpdate < MIN_UPDATE_INTERVAL_MS) {
            return@withContext TelegramCallResult.Success(Unit)
        }

        val result = api.editMessageLiveLocation(
            botToken = botToken,
            chatId = handle.chatId,
            messageId = handle.messageId,
            latitude = fix.latitude,
            longitude = fix.longitude,
            horizontalAccuracyMeters = fix.accuracyMeters,
        )
        if (result is TelegramCallResult.Success) {
            lastUpdateMsByHandle[key] = now
        }
        result
    }

    override suspend fun stop(handle: LiveLocationHandle): TelegramCallResult<Unit> = withContext(Dispatchers.IO) {
        val botToken = prefs.getBotToken() ?: return@withContext TelegramCallResult.Terminal(TelegramFailureCode.UNAUTHORIZED)
        api.stopMessageLiveLocation(
            botToken = botToken,
            chatId = handle.chatId,
            messageId = handle.messageId,
        )
    }

    override suspend fun alertOwners(text: String): Boolean = withContext(Dispatchers.IO) {
        val owners = prefs.getAllowedChatIds()
        if (owners.isEmpty()) return@withContext false

        val botToken = prefs.getBotToken() ?: return@withContext false
        var anySuccess = false

        for (chatId in owners) {
            val result = api.sendMessage(botToken, chatId, text)
            if (result is TelegramCallResult.Success) {
                anySuccess = true
            }
        }
        anySuccess
    }
}
