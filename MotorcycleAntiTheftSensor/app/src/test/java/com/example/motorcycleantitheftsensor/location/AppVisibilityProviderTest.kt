package com.example.motorcycleantitheftsensor.location

import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityProviderTest {

    @Test
    fun foregroundProcessImportanceMapsToVisible() {
        val provider = AndroidAppVisibilityProvider {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
        assertTrue(provider.isAppProcessForeground())
    }

    @Test
    fun serviceForegroundFlagDoesNotImplyProcessForeground() {
        val provider = AndroidAppVisibilityProvider {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
        }
        assertFalse(provider.isAppProcessForeground())
    }

    @Test
    fun securityExceptionFallsBackToSpecialUseWithoutCrash() {
        val controller = ForegroundStartController()
        val attemptedTypes = mutableListOf<Int>()

        val result = controller.start(
            requestedTypes = 100,
            specialUseOnlyTypes = 10,
            gateway = { types ->
                attemptedTypes.add(types)
                if (types == 100) {
                    throw SecurityException("Starting FGS with type location caller App not in foreground")
                }
            }
        )

        assertEquals(10, result.usedForegroundTypes)
        assertEquals("Background location foreground start restricted", result.degradedReason)
        assertEquals(listOf(100, 10), attemptedTypes)
    }
}
