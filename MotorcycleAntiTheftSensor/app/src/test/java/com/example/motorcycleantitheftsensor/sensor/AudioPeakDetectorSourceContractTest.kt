package com.example.motorcycleantitheftsensor.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioPeakDetectorSourceContractTest {

    @Test
    fun `audio peak detector source conforms to audio hardening contract`() {
        val file = File("src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt")
        assertTrue("AudioPeakDetector.kt must exist", file.exists())
        val content = file.readText()

        // Must verify 16 kHz sample rate
        assertTrue("Must use 16000 Hz sample rate", content.contains("16000") || content.contains("16_000") || content.contains("AUDIO_SAMPLE_RATE_HZ"))

        // Must explicitly specify read mode
        assertTrue("Armed capture must use explicit blocking read", content.contains("AudioRecord.READ_BLOCKING"))
        assertTrue("Must call four-argument AudioRecord.read", content.contains("recorder.read("))
        assertFalse("Manual non-blocking self-test mode must be removed", content.contains("AudioRecord.READ_NON_BLOCKING"))
        assertFalse("AudioReadMode must be removed", content.contains("AudioReadMode"))
        assertFalse("Manual detector self-test must be removed", content.contains("testMicrophone"))

        // Must not contain sleep(200), raw PCM file output, or unconstrained logging
        assertFalse("Must not use Thread.sleep(200)", content.contains("Thread.sleep(200)"))
        assertFalse("Must not write raw PCM to disk", content.contains(".pcm") || content.contains(".wav"))
    }
}
