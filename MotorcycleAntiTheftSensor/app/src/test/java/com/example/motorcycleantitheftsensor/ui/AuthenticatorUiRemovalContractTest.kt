package com.example.motorcycleantitheftsensor.ui

import org.junit.Assert.assertFalse
import org.junit.Test

class AuthenticatorUiRemovalContractTest {
    @Test
    fun uiAndSettingsSourcesContainNoAuthenticatorFlow() {
        val paths = listOf(
            "src/main/java/com/example/motorcycleantitheftsensor/Navigation.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/AndroidProtectionSettingsGateway.kt",
            "src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt",
        )
        val forbidden = listOf(
            "AuthenticatorSetupDetails",
            "authenticatorConfigured",
            "beginAuthenticatorSetup",
            "cancelAuthenticatorSetup",
            "verifyAuthenticator",
            "AuthenticatorQrCodeEncoder",
            "authenticator_qr_code",
            "otpauth://",
            "totpSecret",
            "totpUri",
            "totpStatusMessage",
            "onSetupTotp",
            "onVerifyTotpCode",
            "Google Authenticator",
            "Setup Auth",
            "TOTP ED25519 READY",
        )

        paths.forEach { path ->
            // A source that has been deleted outright cannot carry the flow this contract
            // forbids, and must not fail the check by its absence.
            val file = java.io.File(path)
            if (!file.exists()) return@forEach
            val source = file.readText()
            forbidden.forEach { token ->
                assertFalse("$path still contains $token", source.contains(token))
            }
        }
    }
}
