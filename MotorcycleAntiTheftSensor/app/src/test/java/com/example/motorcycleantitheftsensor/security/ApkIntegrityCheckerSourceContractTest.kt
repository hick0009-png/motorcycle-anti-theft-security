package com.example.motorcycleantitheftsensor.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ApkIntegrityCheckerSourceContractTest {
    @Test
    fun signatureReadFailureDoesNotPrintRawException() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/security/ApkIntegrityChecker.kt",
        ).readText()

        assertFalse(source.contains("printStackTrace"))
    }
}
