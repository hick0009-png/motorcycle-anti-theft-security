package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_LOCKED_GROUP_TOGGLE_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_RESTORE_RECOMMENDED_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_NO_PRIMARY_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_ROLE_LIST_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SENSOR_ROLE_TALLY_TAG
import com.example.motorcycleantitheftsensor.ui.settings.SettingsScreen
import com.example.motorcycleantitheftsensor.ui.settings.sensorCapabilityCaveatTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceArmingRuleTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceCaveatTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceCostTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceDetectsTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceEffectTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceEffectsToggleTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceDivergesTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceRoleTag
import com.example.motorcycleantitheftsensor.ui.settings.sensorSourceUnavailableTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The dialog is the only place where the owner can see that two protection uses want
 * different sensors, and what each sensor is there for. These run on device: they pin
 * that the star sits on the role the selected profile asks for, that a row off the
 * recommendation says so, that the way back writes the recommended roles rather than
 * merely claiming to, and that every editable row explains what it contributes.
 */
class SensorRecommendationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val configPolicy = SensorConfigurationPolicy()
    private var savedConfiguration: SensorFusionConfiguration? = null
    private var profileRestored = false

    private fun inventory(
        overrides: Map<SensorSource, SensorAvailability> = emptyMap(),
    ): Map<SensorSource, SensorAvailabilityUiModel> =
        SensorSource.entries.associateWith { source ->
            SensorAvailabilityUiModel(
                source = source,
                availability = overrides[source] ?: SensorAvailability.AVAILABLE,
                vendor = "test-vendor",
                powerMa = 0.5f,
            )
        }

    private fun openRoleDialog(
        profile: ProtectionProfile,
        configuration: SensorFusionConfiguration = configPolicy.forPreset(SensorPreset.BALANCED),
        availability: Map<SensorSource, SensorAvailabilityUiModel> = inventory(),
        openDialog: Boolean = true,
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
                    sensorAvailability = availability,
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
        if (openDialog) {
            composeRule.onNodeWithText("กำหนดบทบาทเซ็นเซอร์ขั้นสูง").performScrollTo().performClick()
        }
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
    fun everyVehicleRowSaysWhatItContributes() {
        assertEveryRowExplainsItself(ProtectionProfile.VEHICLE)
    }

    @Test
    fun everyEntryRowSaysWhatItContributes() {
        assertEveryRowExplainsItself(ProtectionProfile.ENTRY)
    }

    /** One profile per test: the compose rule accepts a single setContent per test. */
    private fun assertEveryRowExplainsItself(profile: ProtectionProfile) {
        openRoleDialog(profile)

        SensorSource.entries.forEach { source ->
            val tag = sensorSourceDetectsTag(source)
            scrollTo(tag)
            composeRule.onNodeWithTag(tag).assertExists()
            composeRule.onNodeWithText(
                "${PresentationTextCatalog.SENSOR_DETECTS_PREFIX}: " +
                    PresentationTextCatalog.contribution(profile, source).detectsTh,
            ).assertExists()
        }
    }

    @Test
    fun theSameSensorReadsDifferentlyUnderADifferentUse() {
        // The owner-visible payoff: the light sensor is the tamper witness for the vehicle
        // watch and the lamp witness for Power Guard, and the row says so in each.
        val vehicleLight = PresentationTextCatalog
            .contribution(ProtectionProfile.VEHICLE, SensorSource.AMBIENT_LIGHT).detectsTh
        val powerLight = PresentationTextCatalog
            .contribution(ProtectionProfile.POWER, SensorSource.AMBIENT_LIGHT).detectsTh

        openRoleDialog(ProtectionProfile.VEHICLE)
        scrollTo(sensorSourceDetectsTag(SensorSource.AMBIENT_LIGHT))
        composeRule.onNodeWithText(
            "${PresentationTextCatalog.SENSOR_DETECTS_PREFIX}: $vehicleLight",
        ).assertExists()
        composeRule.onNodeWithText(
            "${PresentationTextCatalog.SENSOR_DETECTS_PREFIX}: $powerLight",
        ).assertDoesNotExist()
    }

    @Test
    fun theEffectsPanelComparesAllThreeRolesAndNamesTheCost() {
        openRoleDialog(ProtectionProfile.VEHICLE)
        val contribution = PresentationTextCatalog
            .contribution(ProtectionProfile.VEHICLE, SensorSource.GYROSCOPE)

        // Collapsed by default: thirty explanations open at once is not a dialog.
        composeRule.onNodeWithTag(sensorSourceEffectTag(SensorSource.GYROSCOPE, SensorRole.PRIMARY))
            .assertDoesNotExist()

        val toggle = sensorSourceEffectsToggleTag(SensorSource.GYROSCOPE)
        scrollTo(toggle)
        composeRule.onNodeWithTag(toggle).performClick()

        SensorRole.entries.forEach { role ->
            val tag = sensorSourceEffectTag(SensorSource.GYROSCOPE, role)
            scrollTo(tag)
            composeRule.onNodeWithTag(tag).assertExists()
        }
        composeRule.onNodeWithText(contribution.asPrimaryTh).assertExists()
        composeRule.onNodeWithText(contribution.asSupportingTh).assertExists()
        composeRule.onNodeWithText(contribution.ifOffTh).assertExists()

        val cost = sensorSourceCostTag(SensorSource.GYROSCOPE)
        scrollTo(cost)
        composeRule.onNodeWithTag(cost).assertExists()
        composeRule.onNodeWithText(
            "${PresentationTextCatalog.SENSOR_COST_PREFIX}: ${contribution.costTh}",
        ).assertExists()
    }

    @Test
    fun theEffectsPanelStatesTheRealArmingRuleForPrimaries() {
        // The rule that "more primaries means a slower arm" is not true of this engine, so
        // the panel states the one that is: arming needs one calibrated primary.
        openRoleDialog(ProtectionProfile.VEHICLE)

        val toggle = sensorSourceEffectsToggleTag(SensorSource.ACCELEROMETER)
        scrollTo(toggle)
        composeRule.onNodeWithTag(toggle).performClick()

        // By tag, not by text: the card summary behind this dialog states the same rule.
        val rule = sensorSourceArmingRuleTag(SensorSource.ACCELEROMETER)
        scrollTo(rule)
        composeRule.onNodeWithTag(rule).assertExists()
    }

    @Test
    fun theLightSupportingLineExplainsTheEscalationItActuallyCauses() {
        openRoleDialog(ProtectionProfile.VEHICLE)

        val toggle = sensorSourceEffectsToggleTag(SensorSource.AMBIENT_LIGHT)
        scrollTo(toggle)
        composeRule.onNodeWithTag(toggle).performClick()

        val supporting = PresentationTextCatalog
            .contribution(ProtectionProfile.VEHICLE, SensorSource.AMBIENT_LIGHT).asSupportingTh
        assertTrue(
            "the supporting line must name the incident the escalation produces",
            supporting.contains("พบการงัดแงะหรือเปิดเบาะ"),
        )
        composeRule.onNodeWithText(supporting).assertExists()
    }

    @Test
    fun severalRowsCanBeOpenAtOnceAndEachKeepsItsOwnState() {
        openRoleDialog(ProtectionProfile.VEHICLE)

        listOf(SensorSource.ACCELEROMETER, SensorSource.GYROSCOPE).forEach { source ->
            val toggle = sensorSourceEffectsToggleTag(source)
            scrollTo(toggle)
            composeRule.onNodeWithTag(toggle).performClick()
        }

        listOf(SensorSource.ACCELEROMETER, SensorSource.GYROSCOPE).forEach { source ->
            val tag = sensorSourceEffectTag(source, SensorRole.OFF)
            scrollTo(tag)
            composeRule.onNodeWithTag(tag).assertExists()
        }
        // An untouched row stays closed.
        composeRule.onNodeWithTag(sensorSourceEffectTag(SensorSource.PROXIMITY, SensorRole.OFF))
            .assertDoesNotExist()
    }

    @Test
    fun aSensorThisPhoneLacksRefusesPrimaryAndKeepsTheOtherTwo() {
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            availability = inventory(mapOf(SensorSource.GYROSCOPE to SensorAvailability.MISSING)),
        )

        val primary = sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.PRIMARY)
        scrollTo(primary)
        composeRule.onNodeWithTag(primary).assertIsNotEnabled()
        composeRule.onNodeWithTag(sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.SUPPORTING))
            .assertIsEnabled()
        composeRule.onNodeWithTag(sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.OFF))
            .assertIsEnabled()
        composeRule.onNodeWithTag(sensorSourceUnavailableTag(SensorSource.GYROSCOPE)).assertExists()
        assertNull("a refused button must not write a configuration", savedConfiguration)
    }

    @Test
    fun aPresentSensorKeepsAllThreeRolesAndCarriesNoWarning() {
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            availability = inventory(mapOf(SensorSource.GYROSCOPE to SensorAvailability.MISSING)),
        )

        val primary = sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.PRIMARY)
        scrollTo(primary)
        composeRule.onNodeWithTag(primary).assertIsEnabled()
        composeRule.onNodeWithTag(sensorSourceUnavailableTag(SensorSource.ACCELEROMETER))
            .assertDoesNotExist()
    }

    @Test
    fun theOtherTwoRolesStillSaveOnAMissingSensor() {
        // The point of refusing only primary: a phone without the sensor must still be able
        // to keep the row as supporting, which the controller merely marks degraded.
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            availability = inventory(mapOf(SensorSource.GYROSCOPE to SensorAvailability.MISSING)),
        )

        val off = sensorSourceRoleTag(SensorSource.GYROSCOPE, SensorRole.OFF)
        scrollTo(off)
        composeRule.onNodeWithTag(off).performClick()

        val saved = requireNotNull(savedConfiguration) { "an allowed role must still save" }
        assertEquals(SensorRole.OFF, saved.source(SensorSource.GYROSCOPE).role)
    }

    @Test
    fun aStoredPrimaryOnAMissingSensorIsNamedAndRepairedOnTheNextSave() {
        // A configuration restored from another phone can hold the combination this screen
        // now refuses to create. The row says so, and the next save leaves the device with
        // a configuration it can actually apply instead of one the controller rejects whole.
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            configuration = configPolicy.withSourceRole(
                configPolicy.forPreset(SensorPreset.BALANCED),
                SensorSource.GYROSCOPE,
                SensorRole.PRIMARY,
            ),
            availability = inventory(mapOf(SensorSource.GYROSCOPE to SensorAvailability.MISSING)),
        )

        val warning = sensorSourceUnavailableTag(SensorSource.GYROSCOPE)
        scrollTo(warning)
        composeRule.onNodeWithTag(warning).assertExists()
        composeRule.onNodeWithText(
            "⚠️ ${PresentationTextCatalog.SENSOR_PRIMARY_UNAVAILABLE_NOW}",
        ).assertExists()

        val proximityOff = sensorSourceRoleTag(SensorSource.PROXIMITY, SensorRole.OFF)
        scrollTo(proximityOff)
        composeRule.onNodeWithTag(proximityOff).performClick()

        val saved = requireNotNull(savedConfiguration) { "an unrelated edit must still save" }
        assertEquals(
            "a primary the phone lacks must never be written back",
            SensorRole.SUPPORTING,
            saved.source(SensorSource.GYROSCOPE).role,
        )
    }

    @Test
    fun theCardSaysHowManySensorsThisUseLeadsWithAndHowManyCorroborate() {
        openRoleDialog(ProtectionProfile.VEHICLE, openDialog = false)

        composeRule.onNodeWithTag(SENSOR_ROLE_TALLY_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText(
            PresentationTextCatalog.sensorRoleTallyLine(ProtectionProfile.VEHICLE, 2, 8, 0),
        ).assertExists()
        composeRule.onNodeWithTag(SENSOR_NO_PRIMARY_TAG).assertDoesNotExist()
    }

    @Test
    fun theCountCountsWhatTheUseWillRunRatherThanWhatTheScreenIsHolding() {
        // Power Guard runs one lamp. The shared configuration this screen still reads says
        // otherwise, and crediting it with eight corroborating sensors would contradict the
        // lock banner directly above.
        openRoleDialog(ProtectionProfile.POWER, openDialog = false)

        composeRule.onNodeWithTag(SENSOR_ROLE_TALLY_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText(
            PresentationTextCatalog.sensorRoleTallyLine(ProtectionProfile.POWER, 1, 0, 9),
        ).assertExists()
    }

    @Test
    fun aConfigurationWithNoLeadSensorSaysItCannotProtect() {
        openRoleDialog(
            ProtectionProfile.VEHICLE,
            configuration = SensorSource.entries.fold(
                configPolicy.forPreset(SensorPreset.BALANCED),
            ) { config, source ->
                configPolicy.withSourceRole(config, source, SensorRole.OFF)
            },
            openDialog = false,
        )

        composeRule.onNodeWithTag(SENSOR_NO_PRIMARY_TAG).performScrollTo().assertExists()
        composeRule.onNodeWithText(
            "⚠️ ${PresentationTextCatalog.SENSOR_NO_PRIMARY_WARNING}",
        ).assertExists()
    }

    @Test
    fun theDoorWatchAdmitsOnItsRotationCardThatTheRoleDoesNotGovernTheDetection() {
        openRoleDialog(ProtectionProfile.ENTRY, openDialog = false)

        val note = sensorCapabilityCaveatTag(SensorCapability.ROTATION)
        composeRule.onNodeWithTag(note).performScrollTo().assertExists()
        composeRule.onNodeWithText(
            "${PresentationTextCatalog.SENSOR_CAVEAT_PREFIX}: " +
                requireNotNull(
                    PresentationTextCatalog.capabilityCaveat(
                        ProtectionProfile.ENTRY,
                        SensorCapability.ROTATION,
                    ),
                ),
        ).assertExists()
    }

    @Test
    fun theVehicleWatchCarriesNoSuchNoteBecauseItReallyActsOnEveryRole() {
        openRoleDialog(ProtectionProfile.VEHICLE, openDialog = false)

        SensorCapability.entries.forEach { capability ->
            composeRule.onNodeWithTag(sensorCapabilityCaveatTag(capability)).assertDoesNotExist()
        }
    }

    @Test
    fun powerGuardAdmitsTheSameThingAboutTheWitnessLamp() {
        // Finding #4's twin: the power watch registers the light sensor itself, so lowering
        // the role here would not stop it either.
        openRoleDialog(ProtectionProfile.POWER, openDialog = false)

        composeRule.onNodeWithTag(sensorCapabilityCaveatTag(SensorCapability.LIGHT))
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag(sensorCapabilityCaveatTag(SensorCapability.MOVEMENT))
            .assertDoesNotExist()
    }

    @Test
    fun theNoteRepeatsWhereTheRoleIsActuallyChosen() {
        // The card explains the group; the panel is where the owner is about to move a role,
        // which is exactly where believing the role governs the detection would cost them.
        openRoleDialog(ProtectionProfile.ENTRY)

        val toggle = sensorSourceEffectsToggleTag(SensorSource.GYROSCOPE)
        scrollTo(toggle)
        composeRule.onNodeWithTag(toggle).performClick()

        val caveat = sensorSourceCaveatTag(SensorSource.GYROSCOPE)
        scrollTo(caveat)
        composeRule.onNodeWithTag(caveat).assertExists()
    }

    @Test
    fun thePanelOfAUseThatActsOnTheRoleStaysFreeOfNotes() {
        openRoleDialog(ProtectionProfile.VEHICLE)

        val toggle = sensorSourceEffectsToggleTag(SensorSource.GYROSCOPE)
        scrollTo(toggle)
        composeRule.onNodeWithTag(toggle).performClick()

        composeRule.onNodeWithTag(sensorSourceCaveatTag(SensorSource.GYROSCOPE))
            .assertDoesNotExist()
    }

    @Test
    fun aLockedRowOffersNoEffectsPanelBecauseNoRoleIsAvailableToCompare() {
        openRoleDialog(ProtectionProfile.POWER)

        composeRule.onNodeWithTag(SENSOR_LOCKED_GROUP_TOGGLE_TAG).performScrollTo().performClick()
        val lockedRow = sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.OFF)
        scrollTo(lockedRow)
        composeRule.onNodeWithTag(sensorSourceEffectsToggleTag(SensorSource.ACCELEROMETER))
            .assertDoesNotExist()
    }

    @Test
    fun aLockedRowExplainsTheLockInsteadOfClaimingAContribution() {
        // Saying what a sensor detects directly under "this mode does not use it" would
        // contradict the line above it.
        openRoleDialog(ProtectionProfile.POWER)

        scrollTo(sensorSourceDetectsTag(SensorSource.AMBIENT_LIGHT))
        composeRule.onNodeWithTag(sensorSourceDetectsTag(SensorSource.AMBIENT_LIGHT)).assertExists()

        composeRule.onNodeWithTag(SENSOR_LOCKED_GROUP_TOGGLE_TAG).performScrollTo().performClick()
        val lockedRow = sensorSourceRoleTag(SensorSource.ACCELEROMETER, SensorRole.OFF)
        scrollTo(lockedRow)
        composeRule.onNodeWithTag(sensorSourceDetectsTag(SensorSource.ACCELEROMETER))
            .assertDoesNotExist()
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
