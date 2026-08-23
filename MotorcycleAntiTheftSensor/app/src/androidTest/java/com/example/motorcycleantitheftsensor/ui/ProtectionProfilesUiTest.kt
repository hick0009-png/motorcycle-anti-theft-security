package com.example.motorcycleantitheftsensor.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import org.junit.Rule
import org.junit.Test

/**
 * Semantics for the visible three-profile picker and the armed change-use
 * confirmation. These run on device during Task 8 acceptance; host CI only
 * compiles them.
 */
class ProtectionProfilesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setState(profile: ProtectionProfileUiState) {
        composeRule.setContent {
            ProtectionAppScreen(
                state = ProtectionUiState.from(
                    snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
                        state = ProtectionState.DISARMED_ONLINE,
                        serviceRunning = true,
                        telegramPolling = true,
                        telegramReachable = true,
                    ),
                    incidents = emptyList(),
                    settings = ProtectionSettingsSummary(
                        tokenConfigured = true,
                        pairedOwnerCount = 1,
                        pairingCode = null,
                        sensitivity = 5,
                        smsFallbackConfigured = false,
                        missingPermissions = emptySet(),
                    ),
                    nowMs = 1_000L,
                    profile = profile,
                ),
                actions = ProtectionAppActions(
                    selectDestination = {},
                    arm = {},
                    disarm = {},
                    clearHistory = {},
                    changeSensitivity = {},
                    requestPermissions = {},
                    replaceBotToken = {},
                    configureSmsFallback = { _, _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                ),
            )
        }
    }

    @Test
    fun profilePickerHasThreeNamedCardsAndNoFalseReadyClaim() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = null,
                showPicker = true,
            ),
        )

        composeRule.onNodeWithText("Vehicle Guard").assertIsDisplayed()
        composeRule.onNodeWithText("Entry Guard").assertIsDisplayed()
        composeRule.onNodeWithText("Power Guard").assertIsDisplayed()
        composeRule.onAllNodesWithText("Setup required").assertCountEquals(2)
    }

    @Test
    fun armedChangeUseKeepsProtectionUntilExplicitConfirmation() {
        var confirmed = false
        var cancelled = false
        composeRule.setContent {
            ProtectionAppScreen(
                state = ProtectionUiState.from(
                    snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
                        state = ProtectionState.ARMED_HEALTHY,
                        serviceRunning = true,
                        telegramPolling = true,
                        telegramReachable = true,
                    ),
                    incidents = emptyList(),
                    settings = ProtectionSettingsSummary(
                        tokenConfigured = true,
                        pairedOwnerCount = 1,
                        pairingCode = null,
                        sensitivity = 5,
                        smsFallbackConfigured = false,
                        missingPermissions = emptySet(),
                    ),
                    nowMs = 1_000L,
                    profile = ProtectionProfileUiState(
                        selectedProfile = ProtectionProfile.POWER,
                        armedProfile = ProtectionProfile.POWER,
                        setupState = ProfileSetupState.READY,
                        pendingSwitchTarget = ProtectionProfile.VEHICLE,
                    ),
                ),
                actions = ProtectionAppActions(
                    selectDestination = {},
                    arm = {},
                    disarm = {},
                    clearHistory = {},
                    changeSensitivity = {},
                    requestPermissions = {},
                    replaceBotToken = {},
                    configureSmsFallback = { _, _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                    confirmProfileSwitch = { confirmed = true },
                    cancelProfileSwitch = { cancelled = true },
                ),
            )
        }

        composeRule.onNodeWithText("Keep current protection")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { check(!confirmed) { "Cancel must not confirm" } }
        composeRule.runOnIdle { check(cancelled) { "Cancel must be invoked" } }

        composeRule.onNodeWithText("Stop protection and change use").assertIsDisplayed()
    }
}
