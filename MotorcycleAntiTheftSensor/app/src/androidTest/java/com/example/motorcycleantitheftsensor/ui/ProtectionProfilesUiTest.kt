package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import com.example.motorcycleantitheftsensor.protection.ProtectionSnapshot
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProfileSetupState
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentSummary
import com.example.motorcycleantitheftsensor.ui.protection.PROTECTION_LIST_TAG
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Semantics for the visible three-profile picker and the armed change-use
 * confirmation. These run on device during Task 8 acceptance; host CI only
 * compiles them.
 */
class ProtectionProfilesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setState(
        profile: ProtectionProfileUiState,
        snapshot: ProtectionSnapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
            telegramPolling = true,
            telegramReachable = true,
        ),
    ) {
        composeRule.setContent {
            ProtectionAppScreen(
                state = ProtectionUiState.from(
                    snapshot = snapshot,
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
                    configureSmsFallback = { _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                ),
            )
        }
    }

    // The navy-header pixel assertion was dropped with the paper-light settings theme
    // migration (5b7f0b4); its helpers no longer exist on this branch.

    @Test
    fun profilePickerHasThreeNamedCardsAndNoFalseReadyClaim() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = null,
                showPicker = true,
            ),
        )

        composeRule.onNodeWithText("ดูแลยานพาหนะ").assertIsDisplayed()
        composeRule.onNodeWithText("ดูแลทางเข้า").assertIsDisplayed()
        composeRule.onNodeWithText("ดูแลไฟเลี้ยง").assertIsDisplayed()
        composeRule.onAllNodesWithText("ตรวจสอบการตั้งค่าก่อนใช้งาน").assertCountEquals(2)
    }

    /** Compile-only this slice; executed on device during Task 7 acceptance. */
    @Test
    fun powerSummaryShowsBothRowsIndependently() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.CHARGING,
                    witness = WitnessRowState.UNAVAILABLE,
                ),
            ),
        )

        composeRule.onNodeWithText("กำลังชาร์จ").assertIsDisplayed()
        composeRule.onNodeWithText("ไฟยืนยัน: ยังยืนยันไม่ได้").assertIsDisplayed()
    }

    @Test
    fun readyPowerHomePrioritizesStatusAndKeepsCommissioningOutOfNormalState() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.CHARGING,
                    witness = WitnessRowState.DETECTED,
                ),
            ),
        )

        composeRule.onNodeWithText("สถานะการปกป้อง").assertIsDisplayed()
        composeRule.onNodeWithText("การใช้งานปัจจุบัน").assertIsDisplayed()
        composeRule.onNodeWithText("สถานะไฟเลี้ยง").assertIsDisplayed()
        composeRule.onNodeWithText("อัปเดตล่าสุด", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("สถานะการทำงาน").assertCountEquals(0)
        // The per-arm lamp challenge stays on a ready home deliberately: without it the witness
        // registry can never be satisfied and every POWER arm stays degraded, which is why
        // PowerGuardSection offers it whenever the phone is not armed. What a ready home must
        // keep away is the setup flow itself.
        composeRule.onAllNodesWithText("สิ่งที่ต้องตั้งค่า").assertCountEquals(0)
        composeRule.onNodeWithText("ยืนยันตำแหน่งไฟ", substring = true).assertExists()
        composeRule.onNodeWithText("เปลี่ยนการใช้งาน").performClick()
        composeRule.onNodeWithText("พร้อมใช้งาน").assertExists()
    }

    @Test
    fun powerSignalsDoNotCreateAConfirmedFaultInCompose() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.DISCHARGING,
                    witness = WitnessRowState.DARK,
                ),
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("สถานะไฟเลี้ยง"))
        composeRule.onAllNodesWithText("ตรวจสอบแหล่งจ่ายไฟและไฟยืนยัน").assertCountEquals(0)
    }

    @Test
    fun confirmedPowerFaultShowsOneRecoveryInstruction() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.DISCHARGING,
                    witness = WitnessRowState.DARK,
                    confirmedFault = true,
                ),
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("สถานะไฟเลี้ยง"))
        composeRule.onAllNodesWithText("ตรวจสอบแหล่งจ่ายไฟและไฟยืนยัน").assertCountEquals(1)
    }

    @Test
    fun notChargingCopyDoesNotClaimThatACableIsConnected() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = ProfileSetupState.READY,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.NOT_CHARGING,
                    witness = WitnessRowState.DETECTED,
                ),
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("สถานะไฟเลี้ยง"))
        composeRule.onNodeWithText("ไม่ได้ชาร์จในขณะนี้").assertIsDisplayed()
        composeRule.onAllNodesWithText("เสียบสายอยู่", substring = true).assertCountEquals(0)
    }

    @Test
    fun vehicleHomeUsesSharedHierarchyAndThaiProfileCopy() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.VEHICLE,
                setupState = ProfileSetupState.READY,
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("สรุปการดูแลยานพาหนะ"))
        composeRule.onNodeWithText("สถานะการปกป้อง").assertExists()
        composeRule.onNodeWithText("การใช้งานปัจจุบัน").assertExists()
        composeRule.onNodeWithText("ดูแลยานพาหนะ").assertIsDisplayed()
        composeRule.onNodeWithText("สรุปการดูแลยานพาหนะ").assertIsDisplayed()
        composeRule.onAllNodesWithText("Vehicle Guard").assertCountEquals(0)
    }

    @Test
    fun latestEventUsesThaiDisplayValuesInsteadOfRawEnums() {
        val snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
            state = ProtectionState.DISARMED_ONLINE,
            serviceRunning = true,
            telegramPolling = true,
            telegramReachable = true,
            lastIncident = IncidentSummary(
                id = "event-1",
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.CLOSED,
                updatedAtMs = 1_000L,
                deliveryState = DeliveryState.SENT,
            ),
            lastDeliveryState = DeliveryState.SENT,
        )
        setState(
            profile = ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.VEHICLE,
                setupState = ProfileSetupState.READY,
            ),
            snapshot = snapshot,
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เหตุการณ์ล่าสุด"))
        composeRule.onNodeWithText("เฝ้าระวัง").assertExists()
        composeRule.onNodeWithText("สิ้นสุดแล้ว").assertExists()
        composeRule.onNodeWithText("ส่งแล้ว").assertExists()
        composeRule.onAllNodesWithText("Warning").assertCountEquals(0)
        composeRule.onAllNodesWithText("Closed").assertCountEquals(0)
        composeRule.onAllNodesWithText("Sent").assertCountEquals(0)
        composeRule.onAllNodesWithText("สถานะการส่งล่าสุด").assertCountEquals(0)
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
                    configureSmsFallback = { _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                    confirmProfileSwitch = { confirmed = true },
                    cancelProfileSwitch = { cancelled = true },
                ),
            )
        }

        composeRule.onNodeWithText("ใช้การป้องกันปัจจุบันต่อ")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { check(!confirmed) { "Cancel must not confirm" } }
        composeRule.runOnIdle { check(cancelled) { "Cancel must be invoked" } }

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("หยุดการป้องกันและเปลี่ยนการใช้งาน"))
        composeRule.onNodeWithText("หยุดการป้องกันและเปลี่ยนการใช้งาน").assertIsDisplayed()
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
                    configureSmsFallback = { _ -> },
                    retry = {},
                    retrySettings = {},
                    resetPairing = {},
                    consumeMessage = {},
                    entryStartCommissioning = { startedAtAngle = it },
                ),
            )
        }

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เริ่มปรับเทียบ"))
        composeRule.onNodeWithText("เข็มทิศประตู").assertExists()
        composeRule.onNodeWithText("ปรับเทียบตำแหน่งปิดของประตูก่อนเริ่มใช้งาน")
            .assertExists()
        composeRule.onNodeWithText("15°").assertExists()
        composeRule.onNodeWithText("แจ้งเมื่อประตูเปิดเกิน 15° จากตำแหน่งปิด")
            .assertExists()

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
        composeRule.onNodeWithText("พร้อมเฝ้าระวังทางเข้า").assertIsDisplayed()
        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("แจ้งเมื่อเกิน 15°", substring = true))
        composeRule.onNodeWithText("แจ้งเมื่อเกิน 15°", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "มุมแจ้งเตือนถูกเปลี่ยนขณะอาร์ม — ปิดระบบ ปรับเทียบ แล้วเปิดใหม่ เพื่อใช้มุมใหม่",
        ).assertIsDisplayed()
    }

    @Test
    fun readyEntrySummaryKeepsAngleControlsBehindSecondaryAction() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.ENTRY,
                setupState = ProfileSetupState.READY,
                entryAngleDegrees = 15,
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เข็มทิศประตู"))
        composeRule.onNodeWithText("พร้อมเฝ้าระวังทางเข้า").assertIsDisplayed()
        composeRule.onAllNodesWithText("ประตูปิด", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("บันทึกมุม", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("ปรับมุมแจ้งเตือน")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("บันทึกมุม 15°").assertExists()
    }

    @Test
    fun readyEntrySummaryOffersRecalibrationBackIntoTheSetupForm() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.ENTRY,
                setupState = ProfileSetupState.READY,
                entryAngleDegrees = 15,
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เข็มทิศประตู"))
        composeRule.onNodeWithText("พร้อมเฝ้าระวังทางเข้า").assertIsDisplayed()
        // From a working watch there is a way back into calibration itself, not just the angle.
        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("ปรับเทียบประตูใหม่"))
        composeRule.onNodeWithText("ปรับเทียบประตูใหม่")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เริ่มปรับเทียบ"))
        composeRule.onNodeWithText("เริ่มปรับเทียบ").assertIsDisplayed()
        composeRule.onNodeWithText("ปรับเทียบแนวประตูใหม่").assertExists()
    }

    @Test
    fun unresolvedEntrySetupDoesNotClaimReady() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.ENTRY,
                setupState = null,
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("เข็มทิศประตู"))
        composeRule.onNodeWithText("ตรวจสอบสถานะการตั้งค่าก่อนใช้งาน").assertIsDisplayed()
        composeRule.onAllNodesWithText("พร้อมเฝ้าระวังทางเข้า").assertCountEquals(0)
    }

    @Test
    fun unresolvedPowerSetupDoesNotShowNormalSummary() {
        setState(
            ProtectionProfileUiState(
                selectedProfile = ProtectionProfile.POWER,
                setupState = null,
                powerSummary = PowerSummaryRows(
                    charging = ChargingRowState.CHARGING,
                    witness = WitnessRowState.DETECTED,
                ),
            ),
        )

        composeRule.onNodeWithTag(PROTECTION_LIST_TAG)
            .performScrollToNode(hasText("สถานะไฟเลี้ยง"))
        composeRule.onNodeWithText("ตรวจสอบสถานะการตั้งค่าก่อนใช้งาน").assertIsDisplayed()
        composeRule.onAllNodesWithText("กำลังชาร์จ").assertCountEquals(0)
    }
}
