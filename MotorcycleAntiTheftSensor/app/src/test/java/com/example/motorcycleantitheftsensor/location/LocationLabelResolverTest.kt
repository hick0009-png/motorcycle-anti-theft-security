package com.example.motorcycleantitheftsensor.location

import android.location.Address
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LocationLabelResolverTest {

    private fun mockAddress(
        featureName: String? = null,
        thoroughfare: String? = null,
        subLocality: String? = null,
        locality: String? = null,
        adminArea: String? = null,
    ): Address {
        val address = mock(Address::class.java)
        `when`(address.featureName).thenReturn(featureName)
        `when`(address.thoroughfare).thenReturn(thoroughfare)
        `when`(address.subLocality).thenReturn(subLocality)
        `when`(address.locality).thenReturn(locality)
        `when`(address.adminArea).thenReturn(adminArea)
        return address
    }

    @Test
    fun formatsAddressComponentsCorrectly() {
        val address = mockAddress(
            featureName = "CentralWorld",
            thoroughfare = "Rama I Rd",
            subLocality = "Pathum Wan",
            locality = "Bangkok",
            adminArea = "Bangkok",
        )

        val formatted = AndroidLocationLabelResolver.formatAddress(address)
        assertEquals("CentralWorld, Rama I Rd, Pathum Wan, Bangkok", formatted)
    }

    @Test
    fun truncatesLongLabelsAt160Chars() {
        val address = mockAddress(
            featureName = "A".repeat(100),
            thoroughfare = "B".repeat(100),
        )

        val formatted = AndroidLocationLabelResolver.formatAddress(address)
        assertTrue(formatted != null && formatted.length <= 160)
    }

    @Test
    fun resolvesAddressFromGateway() = runTest {
        val gateway = GeocoderGateway { _, _ ->
            listOf(
                mockAddress(
                    featureName = "Siam Paragon",
                    locality = "Bangkok",
                )
            )
        }

        val resolver = AndroidLocationLabelResolver(gateway, timeoutMs = 1500L)
        val fix = TrackedLocationFix(13.75, 100.5, 1000L, 1000L, 5f)
        val label = resolver.resolve(fix)
        assertEquals("Siam Paragon, Bangkok", label)
    }

    @Test
    fun returnsNullOnTimeout() = runTest {
        val slowGateway = GeocoderGateway { _, _ ->
            delay(2000L)
            listOf(
                mockAddress(
                    featureName = "Siam Paragon",
                )
            )
        }

        val resolver = AndroidLocationLabelResolver(slowGateway, timeoutMs = 50L)
        val fix = TrackedLocationFix(13.75, 100.5, 1000L, 1000L, 5f)
        val label = resolver.resolve(fix)
        assertNull(label)
    }

    @Test
    fun returnsNullOnException() = runTest {
        val errorGateway = GeocoderGateway { _, _ ->
            throw RuntimeException("Network timeout")
        }

        val resolver = AndroidLocationLabelResolver(errorGateway, timeoutMs = 1500L)
        val fix = TrackedLocationFix(13.75, 100.5, 1000L, 1000L, 5f)
        val label = resolver.resolve(fix)
        assertNull(label)
    }
}
