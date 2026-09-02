package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_LOCKED_GROUP_TOGGLE_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_RESTORE_RECOMMENDED_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_ROLE_LIST_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SettingsScreen
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceDivergesTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceRoleTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The star is the only place on this screen where the owner can see that two protection
 * uses want different sensors. These run on device: they pin that it sits on the role the
 * selected profile asks for, that a row off the recommendation says so, and that the way
 * back writes the recommended roles rather than merely claiming to.
 */
class SensorRecommendationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val configPolicy = SensorConfigurationPolicy()
    private var savedConfiguration: SensorFusionConfiguration? = null
    private var profileRestored = false

    private fun inventory(): Map<SensorSource, SensorAvailabilityUiModel> =
        SensorSource.entries.associateWith { source ->
            SensorAvailabilityUiModel(
                source = source,
                availability = SensorAvailability.AVAILABLE,
                vendor = "test-vendor",
                powerMa = 0.5f,
            )
        }

    private fun openRoleDialog(
        profile: ProtectionProfile,
        configuration: SensorFusionConfiguration = configPolicy.forPreset(SensorPreset.BALANCED),
    ) {
        savedConfiguration = null
        profileRestored = false
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
                        sensorConfiguration = configuration,
                    ),
                    nowMs = 2_000L,
                    profile = ProtectionProfileUiState(selectedProfile = profile),
                    sensorAvailability = inventory(),
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
                    updateSensorConfiguration = { savedConfiguration = it },
                    restoreRecommendedProfile = { profileRestored = true },
                ),
                contentPadding = PaddingValues(0.dp),
            )
        }
        composeRule.onNodeWithText("การวินิจฉัยขั้นสูง").performScrollTo().performClick()
        composeRule.onNodeWithText("แสดงการวินิจฉัยขั้นสูง").performScrollTo().performClick()
        composeRule.onNodeWithText("กำหนดบทบาทเซ็นเซอร์ขั้นสูง").performScrollTo().performClick()
    }

    /** Rows live in a LazyColumn, so an unscrolled row is not merely off screen — it does not exist. */
    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(SENSOR_ROLE_LIST_TAG)
            .performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun theVehicleWatchStarsMovementAndLeavesRotationUnstarred() {
        openRoleDialog(ProtectionProfile.VEHICLE)

        val accelerometer = sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.PRIMARY)
        scrollTo(accelerometer)
        composeRule.onNodeWithTag(accelerometer)
            .assertContentDescriptionContains("ค่าที่แนะนำ", substring = true)

        val gyroPrimary = sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.PRIMARY)
        scrollTo(gyroPrimary)
        composeRule.onNodeWithTag(gyroPrimary).assertContentDescriptionEquals()
    }

    @Test
    fun theEntryWatchMovesTheStarOntoRotation() {
        // Same dialog, same rows, different profile: the owner-visible proof that the two
        // uses do not detect with the same sensors.
        openRoleDialog(ProtectionProfile.ENTRY)

        val gyroSupporting = sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.SUPPORTING)
        scrollTo(gyroSupporting)
        composeRule.onNodeWithTag(gyroSupporting).assertContentDescriptionEquals()

        val gyroPrimary = sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.PRIMARY)
        scrollTo(gyroPrimary)
        composeRule.onNodeWithTag(gyroPrimary)
            .assertContentDescriptionContains("ค่าที่แนะนำ", substring = true)
    }

    @Test
    fun aRowOnTheRecommendationSaysNothingAndOffersNoWayBack() {
        // The balanced preset is what the vehicle watch recommends, so an untouched screen
        // must be quiet: a warning here would be a complaint about its own default.
        openRoleDialog(ProtectionProfile.VEHICLE)

        composeRule.onNodeWithTag(sensorSourceDivergesTag(SensorSource.GYROSCOPE))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(SENSOR_RESTORE_RECOMMENDED_TAG).assertDoesNotExist()
    }

    @Test
    fun aRowOffTheRecommendationIsNamedAndOnlyThatRow() {
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            configPolicy.withSourceRole(
                configPolicy.forPreset(SensorPreset.BALANCED),
                SensorSource.GYROSCOPE,
                SensorRole.PRIMARY,
            ),
        )

        val diverging = sensorSourceDivergesTag(SensorSource.GYROSCOPE)
        scrollTo(diverging)
        composeRule.onNodeWithTag(diverging).assertExists()
        composeRule.onNodeWithTag(sensorSourceDivergesTag(SensorSource.ACCELEROMETER))
            .assertDoesNotExist()
    }

    @Test
    fun theWayBackWritesTheRecommendedRolesAndClearsTheProfileOverrides() {
        openRoleDialog(
            ProtectionProfile.ENTRY,
            configPolicy.withSourceRole(
                configPolicy.forPreset(SensorPreset.BALANCED),
                SensorSource.GYROSCOPE,
                SensorRole.OFF,
            ),
        )

        composeRule.onNodeWithTag(SENSOR_ROLE_LIST_TAG)
            .performScrollToNode(hasTestTag(SENSOR_RESTORE_RECOMMENDED_TAG))
        composeRule.onNodeWithTag(SENSOR_RESTORE_RECOMMENDED_TAG).performClick()

        val written = savedConfiguration
        assertNotNull("restoring must write a configuration", written)
        requireNotNull(written)
        // The rows the entry watch leads with come back as primary...
        assertEquals(SensorRole.PRIMARY, written.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.PRIMARY, written.source(SensorSource.ROTATION_VECTOR).role)
        // ...and the ones it only corroborates with stay corroborating, rather than the
        // whole screen collapsing back to the balanced preset.
        assertEquals(SensorRole.SUPPORTING, written.source(SensorSource.ACCELEROMETER).role)
        assertTrue(
            "the profile overrides must be cleared too, or the runtime keeps the old roles",
            profileRestored,
        )
    }

    @Test
    fun aLockedRowCarriesNoStarAndNoComplaint() {
        // Power Guard pins every movement source OFF. A star over a button that cannot be
        // pressed, or a warning the owner has no way to answer, would both be noise.
        openRoleDialog(ProtectionProfile.POWER)

        composeRule.onNodeWithTag(SENSOR_RESTORE_RECOMMENDED_TAG).assertDoesNotExist()
        val light = sensorSourceRoleTag(SensorSource.AMBIENT_LIGHT, SensorRole.PRIMARY)
        scrollTo(light)
        composeRule.onNodeWithTag(light)
            .assertContentDescriptionContains("ค่าที่แนะนำ", substring = true)

        composeRule.onNodeWithTag(SENSOR_LOCKED_GROUP_TOGGLE_TAG).performScrollTo().performClick()
        val lockedOff = sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.OFF)
        scrollTo(lockedOff)
        // OFF is what this profile recommends for it, and the row still must not be starred:
        // the lock already explains itself, and TalkBack keeps hearing why instead.
        composeRule.onNodeWithTag(lockedOff)
            .assertContentDescriptionContains("ตั้งค่าไม่ได้", substring = true)
        composeRule.onNodeWithTag(sensorSourceDivergesTag(SensorSource.ACCELEROMETER))
            .assertDoesNotExist()

        assertNull("opening the dialog must not write anything", savedConfiguration)
        assertFalse(profileRestored)
    }
}
