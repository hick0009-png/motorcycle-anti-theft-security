package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationForegroundPolicyTest {

    @Test
    fun preAndroid14AllowsLocationWhenPermissionGranted() {
        val policy = LocationForegroundPolicy(sdkInt = 33)
        val decision = policy.evaluate(
            hasFineLocation = true,
            hasCoarseLocation = true,
            isAppInForeground = false,
            hasBackgroundLocation = false,
        )
        assertTrue(decision.mayUseLocationType)
        assertNull(decision.degradationReason)
    }

    @Test
    fun android14BackgroundWithoutBackgroundLocationRejectsLocationType() {
        val policy = LocationForegroundPolicy(sdkInt = 34)
        val decision = policy.evaluate(
            hasFineLocation = true,
            hasCoarseLocation = true,
            isAppInForeground = false,
            hasBackgroundLocation = false,
        )
        assertFalse(decision.mayUseLocationType)
        assertEquals("Background location foreground start restricted", decision.degradationReason)
    }

    @Test
    fun android14ForegroundWithFineLocationAllowsLocationType() {
        val policy = LocationForegroundPolicy(sdkInt = 34)
        val decision = policy.evaluate(
            hasFineLocation = true,
            hasCoarseLocation = true,
            isAppInForeground = true,
            hasBackgroundLocation = false,
        )
        assertTrue(decision.mayUseLocationType)
        assertNull(decision.degradationReason)
    }

    @Test
    fun android14AllowsLocationWhenBackgroundLocationGranted() {
        val policy = LocationForegroundPolicy(sdkInt = 34)
        val decision = policy.evaluate(
            hasFineLocation = true,
            hasCoarseLocation = true,
            isAppInForeground = false,
            hasBackgroundLocation = true,
        )
        assertTrue(decision.mayUseLocationType)
        assertNull(decision.degradationReason)
    }

    @Test
    fun rejectsWhenNoPermissionsGranted() {
        val policy = LocationForegroundPolicy(sdkInt = 34)
        val decision = policy.evaluate(
            hasFineLocation = false,
            hasCoarseLocation = false,
            isAppInForeground = true,
            hasBackgroundLocation = false,
        )
        assertFalse(decision.mayUseLocationType)
        assertNotNull(decision.degradationReason)
    }
}
