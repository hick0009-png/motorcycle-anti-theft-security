package com.example.motorcycleantitheftsensor.location

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LiveLocationHandle(val chatId: String, val messageId: Long)

data class PersistedLivePursuitSession(
    val armedSessionId: String,
    val attemptedAtMs: Long,
    val expiresAtMs: Long,
    val handles: List<LiveLocationHandle>,
)

data class MovementTrackingState(
    val schemaVersion: Int = 2,
    val anchor: ParkingAnchor? = null,
    val session: PersistedLivePursuitSession? = null,
)

interface MovementTrackingStore {
    suspend fun load(): MovementTrackingState
    suspend fun save(state: MovementTrackingState): Boolean
    suspend fun clear(): Boolean
}

class EncryptedMovementTrackingStore(private val prefs: EncryptedPrefsManager) : MovementTrackingStore {

    override suspend fun load(): MovementTrackingState = withContext(Dispatchers.IO) {
        val jsonStr = prefs.getMovementTrackingState()
        if (jsonStr != null) {
            return@withContext parseStateJson(jsonStr) ?: MovementTrackingState()
        }

        // Migrate legacy keys if present
        val legacyAnchor = loadLegacyAnchor()
        val legacySession = loadLegacySession()

        if (legacyAnchor != null || legacySession != null) {
            val migrated = if (legacyAnchor != null && legacySession != null && legacyAnchor.armedSessionId != legacySession.armedSessionId) {
                // Mismatched session IDs: fail-closed attempt marker
                MovementTrackingState(
                    schemaVersion = 2,
                    anchor = null,
                    session = PersistedLivePursuitSession(
                        armedSessionId = legacySession.armedSessionId,
                        attemptedAtMs = legacySession.attemptedAtMs,
                        expiresAtMs = legacySession.expiresAtMs,
                        handles = emptyList(),
                    ),
                )
            } else {
                MovementTrackingState(
                    schemaVersion = 2,
                    anchor = legacyAnchor,
                    session = legacySession,
                )
            }

            if (saveInternal(migrated)) {
                prefs.clearLegacyMovementTrackingKeys()
            }
            return@withContext migrated
        }

        MovementTrackingState()
    }

    override suspend fun save(state: MovementTrackingState): Boolean = withContext(Dispatchers.IO) {
        saveInternal(state)
    }

    private fun saveInternal(state: MovementTrackingState): Boolean {
        return try {
            val root = JSONObject()
            root.put("schemaVersion", state.schemaVersion)

            state.anchor?.let { anchor ->
                val aJson = JSONObject()
                aJson.put("armedSessionId", anchor.armedSessionId)
                val fixJson = JSONObject()
                fixJson.put("latitude", anchor.fix.latitude)
                fixJson.put("longitude", anchor.fix.longitude)
                fixJson.put("elapsedRealtimeMs", anchor.fix.elapsedRealtimeMs)
                fixJson.put("wallClockMs", anchor.fix.wallClockMs)
                fixJson.put("accuracyMeters", anchor.fix.accuracyMeters.toDouble())
                aJson.put("fix", fixJson)
                root.put("anchor", aJson)
            }

            state.session?.let { session ->
                val sJson = JSONObject()
                sJson.put("armedSessionId", session.armedSessionId)
                sJson.put("attemptedAtMs", session.attemptedAtMs)
                sJson.put("expiresAtMs", session.expiresAtMs)
                val handlesArr = JSONArray()
                session.handles.forEach { h ->
                    val hObj = JSONObject()
                    hObj.put("chatId", h.chatId)
                    hObj.put("messageId", h.messageId)
                    handlesArr.put(hObj)
                }
                sJson.put("handles", handlesArr)
                root.put("session", sJson)
            }

            prefs.saveMovementTrackingState(root.toString())
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        val a = prefs.saveMovementTrackingState(null)
        val b = prefs.clearLegacyMovementTrackingKeys()
        a && b
    }

    private fun parseStateJson(jsonStr: String): MovementTrackingState? {
        return try {
            val root = JSONObject(jsonStr)
            val schemaVersion = root.optInt("schemaVersion", 2)
            val anchor = root.optJSONObject("anchor")?.let(::parseAnchor)
            val session = root.optJSONObject("session")?.let(::parseSession)

            if (anchor != null && session != null && anchor.armedSessionId != session.armedSessionId) {
                // Fail-closed attempt marker on mismatch
                MovementTrackingState(
                    schemaVersion = schemaVersion,
                    anchor = null,
                    session = PersistedLivePursuitSession(
                        armedSessionId = session.armedSessionId,
                        attemptedAtMs = session.attemptedAtMs,
                        expiresAtMs = session.expiresAtMs,
                        handles = emptyList(),
                    ),
                )
            } else {
                MovementTrackingState(
                    schemaVersion = schemaVersion,
                    anchor = anchor,
                    session = session,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAnchor(json: JSONObject): ParkingAnchor? {
        return try {
            val armedSessionId = json.getString("armedSessionId").takeIf { it.isNotBlank() } ?: return null
            val fixJson = json.getJSONObject("fix")
            val lat = fixJson.getDouble("latitude")
            val lon = fixJson.getDouble("longitude")
            val elapsed = fixJson.getLong("elapsedRealtimeMs")
            val wall = fixJson.getLong("wallClockMs")
            val acc = fixJson.getDouble("accuracyMeters").toFloat()

            if (!lat.isFinite() || lat !in -90.0..90.0) return null
            if (!lon.isFinite() || lon !in -180.0..180.0) return null
            if (!acc.isFinite() || acc < 0f || acc > 1500f) return null
            if (elapsed < 0L || wall < 0L) return null

            ParkingAnchor(
                fix = TrackedLocationFix(lat, lon, elapsed, wall, acc),
                armedSessionId = armedSessionId,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSession(json: JSONObject): PersistedLivePursuitSession? {
        return try {
            val armedSessionId = json.getString("armedSessionId").takeIf { it.isNotBlank() } ?: return null
            val attemptedAtMs = json.optLong("attemptedAtMs", json.optLong("startedAtMs", 0L))
            val expiresAtMs = json.getLong("expiresAtMs")
            val handlesJson = json.optJSONArray("handles") ?: JSONArray()
            val handles = mutableListOf<LiveLocationHandle>()
            val maxHandles = minOf(handlesJson.length(), 100)
            for (i in 0 until maxHandles) {
                val h = handlesJson.getJSONObject(i)
                val chatId = h.getString("chatId")
                val messageId = h.getLong("messageId")
                if (chatId.isNotBlank() && messageId > 0) {
                    handles.add(LiveLocationHandle(chatId, messageId))
                }
            }
            PersistedLivePursuitSession(armedSessionId, attemptedAtMs, expiresAtMs, handles)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadLegacyAnchor(): ParkingAnchor? {
        val jsonStr = prefs.getParkingAnchor() ?: return null
        return try {
            parseAnchor(JSONObject(jsonStr))
        } catch (_: Exception) {
            null
        }
    }

    private fun loadLegacySession(): PersistedLivePursuitSession? {
        val jsonStr = prefs.getLivePursuitSession() ?: return null
        return try {
            parseSession(JSONObject(jsonStr))
        } catch (_: Exception) {
            null
        }
    }
}
