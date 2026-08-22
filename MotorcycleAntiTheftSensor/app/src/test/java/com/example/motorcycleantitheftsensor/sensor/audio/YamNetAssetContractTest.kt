package com.example.motorcycleantitheftsensor.sensor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class YamNetAssetContractTest {

    private val expectedSizeBytes = 4_126_810L
    private val expectedSha256 = "4D8B4A53282DC83EF04E3E7DBC4FBC98082E34E44ED798E16C3A0CDD4C584FAF"

    @Test
    fun `yamnet model asset exists and matches pinned size and sha256`() {
        val assetFile = File("src/main/assets/yamnet.tflite")
        assertTrue("Asset file must exist at src/main/assets/yamnet.tflite", assetFile.exists())
        assertEquals(expectedSizeBytes, assetFile.length())

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(assetFile.readBytes())
        val hashHex = hashBytes.joinToString("") { "%02X".format(it) }
        assertEquals(expectedSha256, hashHex)
    }
}
