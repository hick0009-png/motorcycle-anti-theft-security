package com.example.motorcycleantitheftsensor.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatorRemovalContractTest {
    private val appDir = File("src/main")
    private val deletedFiles = listOf(
        "src/main/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticator.kt",
        "src/main/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoder.kt",
        "src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorTest.kt",
        "src/test/java/com/example/motorcycleantitheftsensor/security/TotpAuthenticatorSourceContractTest.kt",
        "src/test/java/com/example/motorcycleantitheftsensor/ui/settings/AuthenticatorQrCodeEncoderTest.kt",
    )

    private val forbiddenTokens = listOf(
        "TotpAuthenticator",
        "AuthenticatorQrCodeEncoder",
        "AuthenticatorSetupDetails",
        "authenticatorConfigured",
        "beginAuthenticatorSetup",
        "cancelAuthenticatorSetup",
        "verifyAuthenticator",
        "saveTotpSeed",
        "getTotpSeed",
        "otpauth://",
        "TOTP_",
        "OPEN_AUTHENTICATOR_SETTINGS",
        "totpSecret",
        "totpUri",
        "totpStatusMessage",
        "onSetupTotp",
        "onVerifyTotpCode",
        "hasTotpSeed",
        "Google Authenticator",
        "Setup Auth",
        "TOTP ED25519 READY",
    )

    @Test
    fun deletedFilesDoNotExist() {
        deletedFiles.forEach { relativePath ->
            val file = File(relativePath)
            assertFalse("File must be deleted: $relativePath", file.exists())
        }
    }

    @Test
    fun productionSourcesContainZeroForbiddenTokens() {
        val violations = mutableListOf<String>()
        appDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            forbiddenTokens.forEach { token ->
                if (content.contains(token)) {
                    violations += "${file.path}: contains '$token'"
                }
            }
        }
        assertTrue("Found forbidden authenticator tokens in production code:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
