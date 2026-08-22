package com.example.motorcycleantitheftsensor.sensor.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.motorcycleantitheftsensor.protection.YAMNET_INPUT_SAMPLES
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YamNetClassifierInstrumentedTest {

    @Test
    fun testClassifierLoadsAssetAndClassifiesSilenceWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val classifier = YamNetAudioThreatClassifier(context)
        try {
            val samples = FloatArray(YAMNET_INPUT_SAMPLES) { 0f }
            val results = classifier.classify(samples)
            assertNotNull(results)
            assertTrue("Expected classifications for 521 categories", results.isNotEmpty())
        } finally {
            classifier.close()
        }
    }
}
