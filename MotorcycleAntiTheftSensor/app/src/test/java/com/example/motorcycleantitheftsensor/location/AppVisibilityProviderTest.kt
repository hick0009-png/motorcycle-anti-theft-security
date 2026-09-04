package com.example.motorcycleantitheftsensor.location

import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            attempts = listOf(100, 10),
            gateway = { types ->
                attemptedTypes.add(types)
                if (types == 100) {
                    throw SecurityException("Starting FGS with type location caller App not in foreground")
                }
            }
        )

        assertEquals(10, result.usedForegroundTypes)
        assertNotNull(result.degradedReason)
        assertEquals(listOf(100, 10), attemptedTypes)
    }

    @Test
    fun givingUpTheMicrophoneDoesNotAlsoGiveUpTheLocation() {
        // The whole reason the ladder has a middle rung. A refused microphone claim used to
        // drop straight to special-use alone, and the vehicle watch lost its fixes because the
        // door watch had asked for a sensor.
        val controller = ForegroundStartController()
        val attemptedTypes = mutableListOf<Int>()

        val result = controller.start(
            attempts = listOf(0b111, 0b011, 0b001),
            gateway = { types ->
                attemptedTypes.add(types)
                if (types == 0b111) throw SecurityException("microphone type refused")
            }
        )

        assertEquals(0b011, result.usedForegroundTypes)
        assertEquals(listOf(0b111, 0b011), attemptedTypes)
    }

    @Test
    fun aClaimRefusedAllTheWayDownIsRaisedRatherThanReportedAsGranted() {
        val controller = ForegroundStartController()
        var thrown = false
        try {
            controller.start(attempts = listOf(4, 2)) { throw SecurityException("all refused") }
        } catch (_: SecurityException) {
            thrown = true
        }
        // Reporting a foreground start that never happened would leave the service believing
        // it is protected by a notification it does not have.
        assertTrue(thrown)
    }

    @Test
    fun theMicrophoneIsOnlyClaimedWhenItsPermissionIsHeld() {
        val withMic = ForegroundServiceTypePolicy.requestedTypes(
            sdkInt = 34,
            locationForeground = false,
            microphoneGranted = true,
        )
        val withoutMic = ForegroundServiceTypePolicy.requestedTypes(
            sdkInt = 34,
            locationForeground = false,
            microphoneGranted = false,
        )
        assertNotEquals(withMic, withoutMic)
        assertEquals(withoutMic, withMic and withoutMic)
    }

    @Test
    fun everyRungKeepsSpecialUseAndTheLastOneIsSpecialUseAlone() {
        val attempts = ForegroundServiceTypePolicy.attempts(
            sdkInt = 34,
            locationForeground = true,
            microphoneGranted = true,
        )
        assertEquals(3, attempts.size)
        assertEquals(
            ForegroundServiceTypePolicy.requestedTypes(34, locationForeground = false, microphoneGranted = false),
            attempts.last(),
        )
        // Special use is what the service is: it is never given up.
        val specialUse = attempts.last()
        assertTrue(attempts.all { it and specialUse == specialUse })
    }

    @Test
    fun aPhoneTooOldForServiceTypesClaimsNothingAtAll() {
        assertEquals(
            listOf(ForegroundServiceTypePolicy.NONE),
            ForegroundServiceTypePolicy.attempts(
                sdkInt = 28,
                locationForeground = true,
                microphoneGranted = true,
            ),
        )
    }

    @Test
    fun losingTheMicrophoneIsNotReportedAsLosingTheLocation() {
        val requested = ForegroundServiceTypePolicy.requestedTypes(34, locationForeground = true, microphoneGranted = true)
        val granted = ForegroundServiceTypePolicy.requestedTypes(34, locationForeground = true, microphoneGranted = false)
        assertFalse(ForegroundServiceTypePolicy.lostLocation(requested, granted))

        val specialUseOnly = ForegroundServiceTypePolicy.requestedTypes(34, locationForeground = false, microphoneGranted = false)
        assertTrue(ForegroundServiceTypePolicy.lostLocation(requested, specialUseOnly))
    }
}
