package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_LOCKED_GROUP_TOGGLE_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_LOCK_BANNER_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_PRESET_LOCKED_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_ROLE_LIST_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SettingsScreen
import com.example.motorcycleantitheftsensor.ui.settings.sensorCapabilitySliderTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceRoleTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A dimmed control that still accepts a tap is a trap, and a screen that silently drops
 * the tap is worse. These run on device: they pin that the sensors a profile pins OFF are
 * genuinely disabled, that the reason is on screen, and that a fully customisable profile
 * loses nothing.
 */
class SensorLockUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var savedConfiguration: SensorFusionConfiguration? = null
    private var selectedDestination: ProtectionDestination? = null

    private fun openAdvancedSensors(profile: ProtectionProfile) {
        savedConfiguration = null
        selectedDestination = null
        composeRule.setContent {
            SettingsScreen(
                state = ProtectionUiState.from(
                    snapshot = ProtectionSnapshot.offline(1_000L).copy(
                        state = ProtectionState.DISARMED_ONLINE,
                        serviceRunning = true,
                    ),
                    incidents = emptyList(),
                    settings = ProtectionSettingsSummary(
                        tokenConfigured = true,
                        pairedOwnerCount = 1,
                        pairingCode = "A1B2C3",
                        sensitivity = 5,
                        smsFallbackConfigured = false,
                        missingPermissions = emptySet(),
                        sensorConfiguration = SensorConfigurationPolicy()
                            .forPreset(SensorPreset.BALANCED),
                    ),
                    nowMs = 2_000L,
                    profile = ProtectionProfileUiState(selectedProfile = profile),
                ),
                actions = ProtectionAppActions(
                    selectDestination = { selectedDestination = it },
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
                    updateSensorConfiguration = { savedConfiguration = it },
                ),
                contentPadding = PaddingValues(0.dp),
            )
        }
        composeRule.onNodeWithText("การวินิจฉัยขั้นสูง").performScrollTo().performClick()
        composeRule.onNodeWithText("แสดงการวินิจฉัยขั้นสูง").performScrollTo().performClick()
    }

    @Test
    fun powerProfileDisablesTheSlidersOfCapabilitiesItPinsOff() {
        openAdvancedSensors(ProtectionProfile.POWER)

        composeRule.onNodeWithTag(sensorCapabilitySliderTag(SensorCapability.MOVEMENT))
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(sensorCapabilitySliderTag(SensorCapability.LIGHT))
            .performScrollTo()
            .assertIsEnabled()
        assertNull("a locked slider must not write a configuration", savedConfiguration)
    }

    @Test
    fun vehicleProfileKeepsEverySliderEditable() {
        openAdvancedSensors(ProtectionProfile.VEHICLE)

        SensorCapability.entries.forEach { capability ->
            composeRule.onNodeWithTag(sensorCapabilitySliderTag(capability))
                .performScrollTo()
                .assertIsEnabled()
        }
    }

    @Test
    fun powerProfileShowsTheLockNoticeAndOffersTheWayOut() {
        openAdvancedSensors(ProtectionProfile.POWER)

        composeRule.onNodeWithTag(SENSOR_LOCK_BANNER_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText("เปลี่ยนการใช้งาน").performScrollTo().performClick()

        assertEquals(ProtectionDestination.PROTECTION, selectedDestination)
    }

    @Test
    fun vehicleProfileShowsNoLockNoticeAndKeepsThePresetButtons() {
        openAdvancedSensors(ProtectionProfile.VEHICLE)

        composeRule.onNodeWithTag(SENSOR_LOCK_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SENSOR_PRESET_LOCKED_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("สมดุล (แนะนำ)").performScrollTo().assertIsEnabled()
    }

    @Test
    fun powerProfileReplacesThePresetButtonsWithAReadOnlyChip() {
        openAdvancedSensors(ProtectionProfile.POWER)

        composeRule.onNodeWithTag(SENSOR_PRESET_LOCKED_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText("สมดุล (แนะนำ)").assertDoesNotExist()
    }

    @Test
    fun lockedRoleButtonsAreDisabledAndCollapsedBehindTheirOwnGroup() {
        openAdvancedSensors(ProtectionProfile.POWER)
        composeRule.onNodeWithText("กำหนดบทบาทเซ็นเซอร์ขั้นสูง").performScrollTo().performClick()

        // The one source this profile uses stays fully editable.
        composeRule.onNodeWithTag(
            sensorSourceRoleTag(SensorSource.AMBIENT_LIGHT, SensorRole.SUPPORTING),
        ).assertIsEnabled()
        // The locked ones are not even rendered until the owner expands the group.
        composeRule.onNodeWithTag(
            sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.PRIMARY),
        ).assertDoesNotExist()

        composeRule.onNodeWithTag(SENSOR_LOCKED_GROUP_TOGGLE_TAG).performScrollTo().performClick()

        listOf(SensorRole.PRIMARY, SensorRole.SUPPORTING, SensorRole.OFF).forEach { role ->
            val tag = sensorSourceRoleTag(SensorSource.ACCELEROMETER, role)
            composeRule.onNodeWithTag(SENSOR_ROLE_LIST_TAG).performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }
        assertNull("a locked role button must not write a configuration", savedConfiguration)
    }

    @Test
    fun everySavedConfigurationKeepsLockedSourcesOff() {
        openAdvancedSensors(ProtectionProfile.POWER)
        composeRule.onNodeWithText("กำหนดบทบาทเซ็นเซอร์ขั้นสูง").performScrollTo().performClick()

        val editableTag = sensorSourceRoleTag(SensorSource.AMBIENT_LIGHT, SensorRole.SUPPORTING)
        composeRule.onNodeWithTag(SENSOR_ROLE_LIST_TAG).performScrollToNode(hasTestTag(editableTag))
        composeRule.onNodeWithTag(editableTag).performClick()

        val saved = requireNotNull(savedConfiguration) { "editable source must still save" }
        val raised = SensorSource.entries
            .filter { it != SensorSource.AMBIENT_LIGHT }
            .filter { saved.source(it).role != SensorRole.OFF }
        assertTrue("locked sources leaked a raised role: $raised", raised.isEmpty())
    }
}
