package com.example.motorcycleantitheftsensor.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    /** Compile-only this slice; executed on device during Task 7 acceptance. */
    @Test
    fun powerSummaryShowsBothRowsIndependently() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.CONNECTED,
                    witness = WitnessRowState.UNAVAILABLE,
                ),
            ),
        )

        composeRule.onNodeWithText("กำลังชาร์จ").assertIsDisplayed()
        composeRule.onNodeWithText("ไฟยืนยัน: ใช้งานไม่ได้").assertIsDisplayed()
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

        composeRule.onNodeWithText("ใช้รูปแบบเดิมต่อ")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { check(!confirmed) { "Cancel must not confirm" } }
        composeRule.runOnIdle { check(cancelled) { "Cancel must be invoked" } }

        composeRule.onNodeWithText("หยุดการปกป้องแล้วเปลี่ยน").assertIsDisplayed()
    }

    @Test
    fun changeUseEntryUsesApprovedThaiLabelWithoutMixedLanguage() {
        composeRule.setContent {
            ProtectionAppScreen(
                state = ProtectionUiState.from(
                    snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
                        state = ProtectionState.DISARMED_ONLINE,
                        serviceRunning = true,
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
                        armedProfile = null,
                        setupState = ProfileSetupState.READY,
                        pendingSwitchTarget = null,
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
                ),
            )
        }

        composeRule.onNodeWithText("เปลี่ยนการใช้งาน").assertIsDisplayed()
        composeRule.onNodeWithText("เปลี่ยนการใช้งาน (Change use)").assertDoesNotExist()
    }

    private fun settingsUiState(
        profile: ProtectionProfile,
        entryAngleDegrees: Int? = null,
    ) = ProtectionUiState.from(
        snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
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
            selectedProfile = profile,
            armedProfile = null,
            setupState = ProfileSetupState.READY,
            pendingSwitchTarget = null,
            entryAngleDegrees = entryAngleDegrees,
        ),
    )

    private fun settingsActions() = ProtectionAppActions(
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
    )

    private fun assertHeadingsAppearInApprovedOrder() {
        listOf(
            "การใช้งานปัจจุบัน",
            "การตรวจจับของรูปแบบนี้",
            "การแจ้งเตือน",
            "ความต่อเนื่องของระบบ",
            "การวินิจฉัยขั้นสูง",
        ).forEach { heading ->
            composeRule.onNodeWithText(heading).performScrollTo()
            composeRule.onNodeWithText(heading).assertIsDisplayed()
        }
    }

    @Test
    fun vehicleSettingsShowFiveSectionsAndMovementControlWithoutOldSensitivityLabel() {
        composeRule.setContent { ProtectionAppScreen(settingsUiState(ProtectionProfile.VEHICLE), settingsActions()) }

        assertHeadingsAppearInApprovedOrder()
        composeRule.onNodeWithText("การขยับที่ต้องการให้แจ้งเตือน").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(hasText("ระดับความไว", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun entrySettingsShowAngleOutcomeQuickChoicesAndNoMagneticControl() {
        composeRule.setContent {
            ProtectionAppScreen(settingsUiState(ProtectionProfile.ENTRY, entryAngleDegrees = 15), settingsActions())
        }

        composeRule.onNodeWithText("แจ้งเมื่อประตูเปิดเกิน 15° จากตำแหน่งปิด").assertIsDisplayed()
        composeRule.onNodeWithText("5°").assertIsDisplayed()
        composeRule.onNodeWithText("15°").assertIsDisplayed()
        composeRule.onNodeWithText("30°").assertIsDisplayed()
        composeRule.onAllNodes(hasText("µT", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun powerSettingsShowIndependentChargingAndWitnessRows() {
        composeRule.setContent { ProtectionAppScreen(settingsUiState(ProtectionProfile.POWER), settingsActions()) }

        composeRule.onNodeWithText("การชาร์จโทรศัพท์").assertIsDisplayed()
        composeRule.onNodeWithText("ไฟยืนยันจุดติดตั้ง").assertIsDisplayed()
    }

    @Test
    fun advancedTechnicalControlsStayHiddenUntilDisclosureExpanded() {
        composeRule.setContent { ProtectionAppScreen(settingsUiState(ProtectionProfile.VEHICLE), settingsActions()) }

        composeRule.onNodeWithText("การวินิจฉัยขั้นสูง").performScrollTo()
        composeRule.onNodeWithText("แสดงการวินิจฉัยขั้นสูง").performClick()
        composeRule.onNodeWithText("รูปแบบการทำงาน (Preset):").assertIsDisplayed()
    }

    @Test
    fun entryGuardSetupSectionShowsCompassQuickChoicesAndStartsCommissioning() {
        var startedAtAngle: Int? = null
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
                    profile = ProtectionProfileUiState(
                        selectedProfile = ProtectionProfile.ENTRY,
                        setupState = ProfileSetupState.SETUP_REQUIRED,
                        entryAngleDegrees = 15,
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
                    entryStartCommissioning = { startedAtAngle = it },
                ),
            )
        }

        composeRule.onNodeWithText("เข็มทิศประตู").assertIsDisplayed()
        composeRule.onNodeWithText("ปรับเทียบตำแหน่งปิดของประตูก่อนเริ่มใช้งาน")
            .assertIsDisplayed()
        composeRule.onNodeWithText("15°").assertIsDisplayed()
        composeRule.onNodeWithText("แจ้งเมื่อประตูเปิดเกิน 15° จากตำแหน่งปิด")
            .assertIsDisplayed()

        composeRule.onNodeWithText("เริ่มปรับเทียบ")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { check(startedAtAngle == 15) { "Start must pass selected angle" } }
    }

    @Test
    fun entryGuardReadySummaryShowsClosedStateAndControlledRearmBanner() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.ENTRY,
                armedProfile = ProtectionProfile.ENTRY,
                setupState = ProfileSetupState.READY,
                entryAngleDegrees = 15,
                entryRequiresControlledRearm = true,
            ),
        )

        composeRule.onNodeWithText("เข็มทิศประตู").assertIsDisplayed()
        composeRule.onNodeWithText("ประตูปิด · 0°").assertIsDisplayed()
        composeRule.onNodeWithText("แจ้งเมื่อเกิน 15°").assertIsDisplayed()
        composeRule.onNodeWithText(
            "มุมแจ้งเตือนถูกเปลี่ยนขณะอาร์ม — ปิดระบบ ปรับเทียบ แล้วเปิดใหม่ เพื่อใช้มุมใหม่",
        ).assertIsDisplayed()
    }
}
