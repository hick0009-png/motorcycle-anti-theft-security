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
            "src/main/java/com/example/motorcycleantitheftsensor/ui/DashboardScreen.kt",
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
            val source = java.io.File(path).readText()
            forbidden.forEach { token ->
                assertFalse("$path still contains $token", source.contains(token))
            }
        }
    }
}
