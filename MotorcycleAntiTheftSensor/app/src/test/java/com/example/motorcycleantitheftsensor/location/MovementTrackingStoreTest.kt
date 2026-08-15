package com.example.motorcycleantitheftsensor.location

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class MovementTrackingStoreTest {
    private lateinit var mockPrefs: EncryptedPrefsManager
    private lateinit var store: EncryptedMovementTrackingStore

    @Before
    fun setup() {
        mockPrefs = mock(EncryptedPrefsManager::class.java)
        store = EncryptedMovementTrackingStore(mockPrefs)
    }

    @Test
    fun saveAndLoadStateRoundTrip() = runTest {
        val anchor = ParkingAnchor(TrackedLocationFix(13.7563, 100.5018, 100L, 200L, 5f), "session1")
        val handles = listOf(LiveLocationHandle("chat1", 123L), LiveLocationHandle("chat2", 456L))
        val session = PersistedLivePursuitSession("session1", 1000L, 2000L, handles)
        val state = MovementTrackingState(schemaVersion = 2, anchor = anchor, session = session)

        var savedJson: String? = null
        `when`(mockPrefs.saveMovementTrackingState(anyString())).thenAnswer {
            savedJson = it.getArgument(0)
            true
        }

        assertTrue(store.save(state))
        assertNotNull(savedJson)

        `when`(mockPrefs.getMovementTrackingState()).thenReturn(savedJson)

        val loaded = store.load()
        assertEquals(2, loaded.schemaVersion)
        assertEquals("session1", loaded.anchor?.armedSessionId)
        assertEquals(13.7563, loaded.anchor?.fix?.latitude ?: 0.0, 0.0001)
        assertEquals(2, loaded.session?.handles?.size)
        assertEquals("chat1", loaded.session?.handles?.get(0)?.chatId)
    }

    @Test
    fun legacyMigrationTransfersLegacyKeysToSingleRecord() = runTest {
        val legacyAnchorJson = """
            {"armedSessionId":"legacy-s1","fix":{"latitude":13.75,"longitude":100.5,"elapsedRealtimeMs":100,"wallClockMs":200,"accuracyMeters":5.0}}
        """.trimIndent()
        val legacySessionJson = """
            {"armedSessionId":"legacy-s1","attemptedAtMs":1000,"expiresAtMs":2000,"handles":[{"chatId":"c1","messageId":101}]}
        """.trimIndent()

        `when`(mockPrefs.getMovementTrackingState()).thenReturn(null)
        `when`(mockPrefs.getParkingAnchor()).thenReturn(legacyAnchorJson)
        `when`(mockPrefs.getLivePursuitSession()).thenReturn(legacySessionJson)
        `when`(mockPrefs.saveMovementTrackingState(anyString())).thenReturn(true)
        `when`(mockPrefs.clearLegacyMovementTrackingKeys()).thenReturn(true)

        val loaded = store.load()
        assertEquals("legacy-s1", loaded.anchor?.armedSessionId)
        assertEquals("legacy-s1", loaded.session?.armedSessionId)
        assertEquals(1, loaded.session?.handles?.size)

        verify(mockPrefs).saveMovementTrackingState(anyString())
        verify(mockPrefs).clearLegacyMovementTrackingKeys()
    }

    @Test
    fun mismatchedLegacySessionIdsLoadsFailClosedAttemptMarker() = runTest {
        val legacyAnchorJson = """
            {"armedSessionId":"session-A","fix":{"latitude":13.75,"longitude":100.5,"elapsedRealtimeMs":100,"wallClockMs":200,"accuracyMeters":5.0}}
        """.trimIndent()
        val legacySessionJson = """
            {"armedSessionId":"session-B","attemptedAtMs":1000,"expiresAtMs":2000,"handles":[{"chatId":"c1","messageId":101}]}
        """.trimIndent()

        `when`(mockPrefs.getMovementTrackingState()).thenReturn(null)
        `when`(mockPrefs.getParkingAnchor()).thenReturn(legacyAnchorJson)
        `when`(mockPrefs.getLivePursuitSession()).thenReturn(legacySessionJson)
        `when`(mockPrefs.saveMovementTrackingState(anyString())).thenReturn(true)

        val loaded = store.load()
        assertNull(loaded.anchor)
        assertEquals("session-B", loaded.session?.armedSessionId)
        assertTrue(loaded.session?.handles?.isEmpty() == true)
    }

    @Test
    fun malformedJsonReturnsEmptyState() = runTest {
        `when`(mockPrefs.getMovementTrackingState()).thenReturn("malformed json")
        val loaded = store.load()
        assertNull(loaded.anchor)
        assertNull(loaded.session)
    }

    @Test
    fun invalidCoordinatesInStateLoadsNullAnchor() = runTest {
        val invalidAnchorJson = """
            {"schemaVersion":2,"anchor":{"armedSessionId":"s1","fix":{"latitude":999.0,"longitude":100.5,"elapsedRealtimeMs":100,"wallClockMs":200,"accuracyMeters":5.0}}}
        """.trimIndent()

        `when`(mockPrefs.getMovementTrackingState()).thenReturn(invalidAnchorJson)
        val loaded = store.load()
        assertNull(loaded.anchor)
    }

    @Test
    fun clearDelegatesToPrefs() = runTest {
        `when`(mockPrefs.saveMovementTrackingState(null)).thenReturn(true)
        `when`(mockPrefs.clearLegacyMovementTrackingKeys()).thenReturn(true)

        assertTrue(store.clear())

        verify(mockPrefs).saveMovementTrackingState(null)
        verify(mockPrefs).clearLegacyMovementTrackingKeys()
    }
}
