package com.example.motorcycleantitheftsensor.location

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGeocoderGatewayCompatibilityTest {

    @Test
    fun preApi33ReverseReturnsEmptyForInvalidCoordinates() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = AndroidGeocoderGateway(context).reverse(Double.NaN, longitude = 0.0)

        assertTrue(result.isEmpty())
    }
}
