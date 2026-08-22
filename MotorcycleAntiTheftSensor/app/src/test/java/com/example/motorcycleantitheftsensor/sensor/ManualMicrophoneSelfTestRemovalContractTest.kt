package com.example.motorcycleantitheftsensor.sensor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMicrophoneSelfTestRemovalContractTest {
    @Test
    fun productionSourceContainsNoManualMicrophoneSelfTestSurface() {
        val forbidden = listOf(
            "MicrophoneSelfTest",
            "testMicrophone",
            "runSelfTest",
            "MICROPHONE_SELF_TEST",
            "SELF_TEST_TIMEOUT",
            "NO_AUDIO_OBSERVED",
            "Test Microphone",
            "Last Self-Test",
            "testingMicrophone",
        )
        val hits = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val content = file.readText()
                forbidden.asSequence()
                    .filter(content::contains)
                    .map { token -> "${file.invariantSeparatorsPath}: $token" }
            }
            .toList()

        assertTrue(
            "Manual microphone self-test production references remain:\n${hits.joinToString("\n")}",
            hits.isEmpty(),
        )
    }
}
