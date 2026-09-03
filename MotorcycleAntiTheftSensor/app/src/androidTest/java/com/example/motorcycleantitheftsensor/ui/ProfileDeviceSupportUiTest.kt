package com.example.motorcycleantitheftsensor.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Choosing a protection use the phone cannot detect with is the most expensive mistake
 * on this screen: it looks like protection and stays silent. These run on device.
 */
class ProfileDeviceSupportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selectedProfile: ProtectionProfile? = null

    private fun inventory(
        missing: Set<SensorSource>,
    ): Map<SensorSource, SensorAvailabilityUiModel> = SensorSource.entries.associateWith { source ->
        SensorAvailabilityUiModel(
            source = source,
            availability = if (source in missing) {
                SensorAvailability.MISSING
            } else {
                SensorAvailability.AVAILABLE
            },
        )
    }

    private fun showPicker(missing: Set<SensorSource>) {
        selectedProfile = null
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
                    profile = ProtectionProfileUiState(selectedProfile = null, showPicker = true),
                    sensorAvailability = inventory(missing),
                ),
                actions = ProtectionAppActions(
                    selectDestination = {},
                    arm = {},
                    disarm = {},
                    clearHistory = {},
                    changeSensitivity = {},
                    requestPermissions = {},
                    replaceBotToken = {},
                    configureSmsFallback = { _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                    selectProfile = { selectedProfile = it },
                ),
            )
        }
    }

    private val noAngleSensors = setOf(
        SensorSource.GYROSCOPE,
        SensorSource.ROTATION_VECTOR,
        SensorSource.GAME_ROTATION_VECTOR,
        SensorSource.MAGNETIC_FIELD,
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
    )

    @Test
    fun aCompleteDeviceOffersEveryUseAsUsable() {
        showPicker(missing = emptySet())

        ProtectionProfile.entries.forEach { profile ->
            composeRule.onNodeWithTag("profile_support_${profile.name}", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag("profile_support_reason_ENTRY", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aDeviceWithoutAngleSensorsCannotChooseTheEntryWatch() {
        showPicker(missing = noAngleSensors)

        composeRule.onNodeWithTag("profile_support_reason_ENTRY", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("profile_card_ENTRY").assertIsNotEnabled()

        composeRule.onNodeWithTag("profile_card_ENTRY").performClick()
        assertNull("an unsupported use must never be selected", selectedProfile)
    }

    @Test
    fun theSameDeviceStillChoosesTheUsesItCanCarry() {
        showPicker(missing = noAngleSensors)

        composeRule.onNodeWithTag("profile_card_VEHICLE").performScrollTo().performClick()

        org.junit.Assert.assertEquals(ProtectionProfile.VEHICLE, selectedProfile)
    }

    @Test
    fun aDeviceWithoutALightSensorKeepsPowerGuardAndSaysWhatItLoses() {
        showPicker(missing = setOf(SensorSource.AMBIENT_LIGHT))

        composeRule.onNodeWithTag("profile_support_reason_POWER", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("ยืนยันสองทางไม่ได้", substring = true)

        composeRule.onNodeWithTag("profile_card_POWER").performClick()
        org.junit.Assert.assertEquals(ProtectionProfile.POWER, selectedProfile)
    }
}
