package com.example.motorcycleantitheftsensor.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.protection.DeliveryState
import com.example.motorcycleantitheftsensor.protection.IncidentLifecycle
import com.example.motorcycleantitheftsensor.protection.IncidentSeverity
import com.example.motorcycleantitheftsensor.protection.IncidentType
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorHealth
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorReadingSummary

class ProtectionAppScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shellHasExactlyThreePrimaryDestinationsAndNoDemoControls() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        compose.onNodeWithText("ปกป้อง").assertExists()
        compose.onNodeWithText("เหตุการณ์").assertExists()
        compose.onNodeWithText("ตั้งค่า").assertExists()
        compose.onAllNodes(hasTestTag("primary_destination")).assertCountEquals(3)
        compose.onAllNodes(hasText("Demo", substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    @Test
    fun selectedPrimaryDestinationRendersWithMonochromePixels() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        val pixels = compose.onAllNodes(hasTestTag("primary_destination"))[0]
            .captureToImage()
            .toPixelMap()
        var hasNearBlackPixel = false
        var hasNearWhitePixel = false

        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.alpha < OPAQUE_ALPHA) continue

                val minimumChannel = minOf(color.red, color.green, color.blue)
                val maximumChannel = maxOf(color.red, color.green, color.blue)
                assertTrue(
                    "Non-monochrome pixel at ($x, $y): $color",
                    maximumChannel - minimumChannel <= CHANNEL_TOLERANCE,
                )
                hasNearBlackPixel = hasNearBlackPixel || maximumChannel <= NEAR_BLACK
                hasNearWhitePixel = hasNearWhitePixel || minimumChannel >= NEAR_WHITE
            }
        }

        assertTrue("Selected destination must render a black surface", hasNearBlackPixel)
        assertTrue("Selected destination must render a white indicator/content", hasNearWhitePixel)
    }

    @Test
    fun protectionHasOneStateCorrectPrimaryAction() {
        compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }

        compose.onAllNodes(hasText("เปิดระบบป้องกัน")).assertCountEquals(1)
        compose.onAllNodes(hasText("ปิดระบบป้องกัน")).assertCountEquals(0)
        compose.onNodeWithText("เปิดระบบป้องกัน").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun heroShowsOutcomeFirstThaiCopyWithoutEnglishFragmentsForEachState() {
        listOf(
            ProtectionState.DISARMED_ONLINE to "การป้องกันปิดอยู่",
            ProtectionState.ARMING to "กำลังเปิดการป้องกัน",
            ProtectionState.ARMED_HEALTHY to "การป้องกันทำงานปกติ",
            ProtectionState.ARMED_DEGRADED to "การป้องกันทำงานแบบจำกัด",
            ProtectionState.ALERT_ACTIVE to "กำลังส่งสัญญาณเตือนภัย",
            ProtectionState.OFFLINE to "ระบบออฟไลน์",
        ).forEach { (protectionState, expectedHeroTitle) ->
            compose.setContent { ProtectionAppScreen(baseState(protectionState), fakeActions()) }

            compose.onNodeWithText(expectedHeroTitle, substring = true).assertExists()
            compose.onAllNodes(hasText("(Arm)", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("(Disarm)", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Armed in", substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun primaryNavigationReservesEnoughHeightForIconsAndLabels() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        compose.onNodeWithTag("primary_navigation").assertHeightIsAtLeast(80.dp)
        repeat(3) { index ->
            compose.onAllNodes(hasTestTag("primary_destination"))[index]
                .assertHeightIsAtLeast(80.dp)
        }
    }

    @Test
    fun everySelectedDestinationHasStableVisibleLabelAndIconBounds() {
        showWithLocalNavigation(healthyState())

        listOf(
            "PROTECTION" to "แท็บปกป้อง",
            "EVENTS" to "แท็บเหตุการณ์",
            "SETTINGS" to "แท็บตั้งค่า",
        ).forEach { (enumName, contentDescription) ->
            val labelTag = "primary_destination_label_$enumName"
            compose.onNodeWithTag(labelTag, useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription(contentDescription)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(80.dp)
            compose.onNodeWithTag(labelTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(16.dp)
            compose.onNodeWithTag(
                "primary_destination_icon_$enumName",
                useUnmergedTree = true,
            ).assertHeightIsEqualTo(24.dp)
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("การวินิจฉัยขั้นสูง"))
        listOf(
            "PROTECTION" to "แท็บปกป้อง",
            "EVENTS" to "แท็บเหตุการณ์",
            "SETTINGS" to "แท็บตั้งค่า",
        ).forEach { (enumName, contentDescription) ->
            compose.onNodeWithContentDescription(contentDescription)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag(
                "primary_destination_label_$enumName",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            compose.onNodeWithTag(
                "primary_destination_icon_$enumName",
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
    }

    @Test
    fun missingMicrophoneIsShownAsReducedCoverageWithSettingsAction() {
        var selectedDestination: ProtectionDestination? = null
        val state = baseState(ProtectionState.OFFLINE).copy(
            settings = baseState(ProtectionState.OFFLINE).settings.copy(
                missingPermissions = setOf("android.permission.RECORD_AUDIO"),
            ),
        )
        compose.setContent {
            ProtectionAppScreen(
                state,
                fakeActions().copy(selectDestination = { selectedDestination = it }),
            )
        }

        compose.onNodeWithText(
            "ยังไม่ได้ให้สิทธิ์ไมโครโฟน การตรวจจับเสียงผิดปกติจะใช้ไม่ได้",
        ).assertExists()
        compose.onNodeWithText("การตรวจจับที่ลดลง").assertExists()
        compose.onNodeWithText("สิ่งที่ยังขาดก่อนป้องกันได้").assertDoesNotExist()
        compose.onNodeWithText("ตรวจสอบสิทธิ์").performClick()
        compose.runOnIdle {
            assertEquals(ProtectionDestination.SETTINGS, selectedDestination)
        }
    }

    @Test
    fun settingsDistinguishesReducedCoverageFromBlockingPermissions() {
        val audioPermission = "android.permission.RECORD_AUDIO"
        val notificationPermission = "android.permission.POST_NOTIFICATIONS"
        val state = baseState(ProtectionState.SETUP_REQUIRED).copy(
            destination = ProtectionDestination.SETTINGS,
            protection = baseState(ProtectionState.SETUP_REQUIRED).protection.copy(
                permissionBlockers = setOf("POST_NOTIFICATIONS"),
            ),
            settings = baseState(ProtectionState.SETUP_REQUIRED).settings.copy(
                missingPermissions = setOf(audioPermission, notificationPermission),
            ),
        )
        compose.setContent { ProtectionAppScreen(state, fakeActions()) }

        compose.onNodeWithText("Reduced coverage: Microphone").assertExists()
        compose.onNodeWithText("Blocks protection: Notifications").assertExists()
    }

    @Test
    fun settingsNeverDisplaysStoredCredential() {
        showWithLocalNavigation(configuredSettingsState())

        openSettings()

        compose.onNodeWithText("Token configured").assertExists()
        compose.onNodeWithText(TEST_ONLY_TOKEN).assertDoesNotExist()
    }

    @Test
    fun settingsSecretsDoNotSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val tokenField = hasSetTextAction() and hasText("Bot token")
        compose.onNode(tokenField).performTextInput(UNSAVED_TOKEN)
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText("Replacement encryption key"),
        )
        val smsKeyField = hasSetTextAction() and hasText("Replacement encryption key")
        compose.onNode(smsKeyField).performTextInput(UNSAVED_SMS_KEY)

        restoration.emulateSavedInstanceStateRestore()

        compose.onAllNodes(hasText(UNSAVED_TOKEN, substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText(UNSAVED_SMS_KEY, substring = true)).assertCountEquals(0)
    }

    @Test
    fun savingBotTokenClearsTextFieldFocus() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val tokenField = hasSetTextAction() and hasText("Bot token")
        compose.onNode(tokenField).performTextInput(UNSAVED_TOKEN)
        compose.onNodeWithText("Save bot token").performClick()

        compose.onNode(tokenField).assertIsNotFocused()
    }

    @Test
    fun smsFallbackExplainsRealIncidentEligibility() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val copy =
            "SMS fallback is eligible only for real critical incidents after confirmed Telegram failure."
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(copy))
        compose.onNodeWithText(copy).assertExists()
    }

    @Test
    fun eventsClearRequiresConfirmation() {
        var clearCalls = 0
        val actions = fakeActions().copy(clearHistory = { clearCalls += 1 })
        showWithLocalNavigation(historyState(), actions)

        openEvents()
        compose.onNodeWithText("Clear history").performClick()
        assertEquals(0, clearCalls)
        compose.onNodeWithText("Confirm clear").performClick()
        assertEquals(1, clearCalls)
    }

    @Test
    fun degradedStateIsReadableWithoutColor() {
        compose.setContent {
            ProtectionAppScreen(degradedState("VIBRATION not healthy"), fakeActions())
        }

        compose.onNodeWithText("การป้องกันทำงานแบบจำกัด").assertExists()
        compose.onNodeWithText("VIBRATION not healthy").assertExists()
    }

    @Test
    fun eventsLoadingStateIsExplicit() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                    eventsLoading = true,
                ),
                fakeActions(),
            )
        }

        compose.onNodeWithText("Loading events").assertExists()
    }

    @Test
    fun eventsErrorIsRetryable() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                    eventsError = "History unavailable",
                ),
                fakeActions(),
            )
        }
        compose.onNodeWithText("History unavailable").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun settingsInitialLoadDoesNotShowUnconfiguredDefaults() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.DISARMED_ONLINE).copy(
                    destination = ProtectionDestination.SETTINGS,
                    settingsLoading = true,
                    settingsLoaded = false,
                ),
                fakeActions(),
            )
        }

        compose.onNodeWithText("Loading settings").assertExists()
        compose.onNodeWithText("Token not configured").assertDoesNotExist()
    }

    @Test
    fun settingsLoadFailureOffersRetry() {
        var retrySettingsCalls = 0
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.DISARMED_ONLINE).copy(
                    destination = ProtectionDestination.SETTINGS,
                    settingsLoaded = false,
                    settingsError = "Settings unavailable",
                ),
                fakeActions().copy(retrySettings = { retrySettingsCalls += 1 }),
            )
        }

        compose.onNodeWithText("Settings unavailable").assertExists()
        compose.onNodeWithText("Retry settings").performClick()
        compose.runOnIdle { assertEquals(1, retrySettingsCalls) }
    }

    @Test
    fun pairingResetRequiresConfirmation() {
        var resetPairingCalls = 0
        compose.setContent {
            ProtectionAppScreen(
                configuredSettingsState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions().copy(resetPairing = { resetPairingCalls += 1 }),
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset pairing"))
        compose.onNodeWithText("Reset pairing").performClick()
        compose.runOnIdle { assertEquals(0, resetPairingCalls) }
        compose.onNodeWithText("Confirm reset").performClick()
        compose.runOnIdle { assertEquals(1, resetPairingCalls) }
    }

    @Test
    fun shellShowsOneShotMessageOnSettingsAndConsumesItsId() {
        var consumedMessageId: Long? = null
        compose.setContent {
            ProtectionAppScreen(
                configuredSettingsState().copy(
                    destination = ProtectionDestination.SETTINGS,
                    message = ProtectionUiMessage(
                        id = 42L,
                        content = com.example.motorcycleantitheftsensor.protection.GuidanceContent(
                            titleTh = "Pairing reset",
                            bodyTh = "Pairing reset",
                            telegramTh = null,
                            severity = com.example.motorcycleantitheftsensor.protection.GuidanceSeverity.INFO,
                            action = com.example.motorcycleantitheftsensor.protection.GuidanceAction.NONE,
                            persistent = false,
                        ),
                    ),
                ),
                fakeActions().copy(consumeMessage = { consumedMessageId = it }),
            )
        }

        compose.onNodeWithText("Pairing reset").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000L) { consumedMessageId != null }
        compose.runOnIdle { assertEquals(42L, consumedMessageId) }
    }

    @Test
    fun eventsEmptyStateIsExplicit() {
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.ARMED_HEALTHY).copy(
                    destination = ProtectionDestination.EVENTS,
                ),
                fakeActions(),
            )
        }
        compose.onNode(hasText("No protection events") and isHeading()).assertExists()
    }

    @Test
    fun disarmedStateShowsLiveSamplesAfterArming() {
        compose.setContent { ProtectionAppScreen(disarmedState(), fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("การสั่นสะเทือน"))
        compose.onAllNodes(hasText("การวัดสดจะเริ่มหลังเปิดระบบ"))[0].assertHeightIsAtLeast(10.dp)
    }

    @Test
    fun armedStateShowsTelemetryValues() {
        val armedState = healthyState().copy(
            protection = healthyState().protection.copy(
                sensorHealth = mapOf(
                    SensorKind.VIBRATION to SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = TEST_TIMESTAMP_MS,
                        latestReading = SensorReadingSummary(9.8, "m/s²", "Acceleration")
                    )
                )
            )
        )
        compose.setContent { ProtectionAppScreen(armedState, fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Acceleration: 9.8 m/s²"))
        compose.onNodeWithText("Acceleration: 9.8 m/s²").assertExists()
    }

    @Test
    fun eventsUseReadableLocalTimestampInsteadOfRawEpochMillis() {
        val state = historyState().copy(destination = ProtectionDestination.EVENTS)
        compose.setContent { ProtectionAppScreen(state, fakeActions()) }

        compose.onNodeWithText(
            "Time: ${formatProtectionTimestamp(TEST_TIMESTAMP_MS)}",
        ).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun protectionUsesReadableLocalTimestampInsteadOfRawEpochMillis() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }
        openAdvancedDiagnostics()

        compose.onNodeWithText(formatProtectionTimestamp(TEST_TIMESTAMP_MS)).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun settingsUseReadableLocalTimestampInsteadOfRawEpochMillis() {
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions(),
            )
        }

        val timestamp = formatProtectionTimestamp(TEST_TIMESTAMP_MS)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(timestamp))
        compose.onNodeWithText(timestamp).assertExists()
        compose.onAllNodes(hasText(TEST_TIMESTAMP_MS.toString(), substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun sensitivityHasAccessibleNameStateAndMinimumTouchTarget() {
        var changedSensitivity: Int? = null
        compose.setContent {
            ProtectionAppScreen(
                healthyState().copy(destination = ProtectionDestination.SETTINGS),
                fakeActions().copy(changeSensitivity = { changedSensitivity = it }),
            )
        }

        val slider = hasTestTag("sensitivity_slider") and
            hasContentDescription("Protection sensitivity, 5 out of 10")
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("sensitivity_slider"))
        val sliderNode = compose.onNode(slider)
        val semanticsNode = sliderNode.fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        val visualHeightDp = semanticsNode.boundsInRoot.height / density
        val touchHeightDp = semanticsNode.touchBoundsInRoot.height / density
        assertTrue(
            "Visual height was $visualHeightDp dp; touch height was $touchHeightDp dp",
            visualHeightDp >= 48f && touchHeightDp >= 48f,
        )

        sliderNode.performTouchInput {
            click(Offset(center.x * 1.5f, 1f))
        }
        compose.runOnIdle {
            assertTrue("Expanded touch edge did not change sensitivity", changedSensitivity != null)
        }
    }

    @Test
    fun settingsRendersNoAuthenticatorOrQrControls() {
        showWithLocalNavigation(configuredSettingsState())

        openSettings()

        compose.onAllNodes(hasText("Authenticator", substring = true, ignoreCase = true))
            .assertCountEquals(0)
        compose.onAllNodes(hasText("Set up authenticator")).assertCountEquals(0)
        compose.onAllNodes(hasText("Replace authenticator")).assertCountEquals(0)
        compose.onAllNodes(hasText("Show QR code")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("authenticator_qr_code")).assertCountEquals(0)
    }

    @Test
    fun protectionScreenDisplaysTruthfulMicrophoneSensorHealth() {
        val state = baseState(ProtectionState.ARMED_HEALTHY).copy(
            protection = baseState(ProtectionState.ARMED_HEALTHY).protection.copy(
                sensorHealth = mapOf(
                    SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE),
                ),
            ),
        )
        showWithLocalNavigation(state)
        openAdvancedDiagnostics()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("ไมโครโฟนพร้อมใช้งาน"))
        compose.onNodeWithText("ไมโครโฟนพร้อมใช้งาน").assertExists()
    }

    @Test
    fun audioThreatRuntimeCardRendersWhenAudioIsActive() {
        val state = baseState(ProtectionState.ARMED_HEALTHY).copy(
            audio = AudioUiTelemetry(
                state = com.example.motorcycleantitheftsensor.protection.AudioRuntimeState.LISTENING,
                modelReady = true,
                approximateLevelDbfs = -32.5,
            ),
        )
        showWithLocalNavigation(state)
        openAdvancedDiagnostics()

        compose.onNodeWithTag(com.example.motorcycleantitheftsensor.ui.protection.AUDIO_RUNTIME_CARD_TAG).assertExists()
        compose.onNodeWithText("กำลังฟังเสียง").assertExists()
        compose.onAllNodes(hasText("listening", substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    @Test
    fun diagnosticsStayBehindSingleAdvancedDisclosureUntilExpanded() {
        compose.setContent { ProtectionAppScreen(baseState(ProtectionState.DISARMED_ONLINE), fakeActions()) }

        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performScrollTo()
        compose.onNodeWithText("สถานะระบบ").assertDoesNotExist()
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.AUDIO_RUNTIME_CARD_TAG,
        ).assertDoesNotExist()

        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performClick()

        compose.onNodeWithText("สถานะระบบ").assertExists()
    }

    @Test
    fun settingsHasAutomaticAudioDiagnosticsButNoManualMicrophoneTest() {
        showWithLocalNavigation(configuredSettingsState())
        openSettings()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Audio Threat Detection"))
        compose.onNodeWithText("Audio Threat Detection").assertExists()
        compose.onNodeWithText("Audio Runtime").assertExists()
        compose.onNodeWithText("Last Sample Age").assertExists()
        compose.onAllNodes(hasText("Test Microphone", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Last Self-Test", substring = true)).assertCountEquals(0)
    }

    private fun showWithLocalNavigation(
        initialState: ProtectionUiState,
        initialActions: ProtectionAppActions = fakeActions(),
    ) {
        compose.setContent {
            var state by remember { mutableStateOf(initialState) }
            ProtectionAppScreen(
                state = state,
                actions = initialActions.copy(
                    selectDestination = { destination ->
                        state = state.copy(destination = destination)
                        initialActions.selectDestination(destination)
                    },
                ),
            )
        }
    }

    private fun openEvents() {
        compose.onNodeWithText("เหตุการณ์").performClick()
    }

    private fun openSettings() {
        compose.onNodeWithText("ตั้งค่า").performClick()
    }

    private fun openAdvancedDiagnostics() {
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performScrollTo()
        compose.onNodeWithTag(
            com.example.motorcycleantitheftsensor.ui.protection.ADVANCED_DIAGNOSTICS_TOGGLE_TAG,
        ).performClick()
    }
}

private fun healthyState(): ProtectionUiState = baseState(ProtectionState.ARMED_HEALTHY)

private fun disarmedState(): ProtectionUiState = baseState(ProtectionState.DISARMED_ONLINE)

private fun degradedState(reason: String): ProtectionUiState =
    baseState(ProtectionState.ARMED_DEGRADED).copy(
        protection = baseState(ProtectionState.ARMED_DEGRADED).protection.copy(
            degradationReasons = setOf(reason),
        ),
    )

private fun configuredSettingsState(): ProtectionUiState =
    baseState(ProtectionState.ARMED_HEALTHY).copy(
        settings = baseState(ProtectionState.ARMED_HEALTHY).settings.copy(
            tokenConfigured = true,
            pairedOwnerCount = 1,
            pairingCode = null,
            smsFallbackConfigured = true,
        ),
    )

private fun historyState(): ProtectionUiState =
    baseState(ProtectionState.ARMED_HEALTHY).copy(
        events = listOf(
            ProtectionEventRow(
                id = "incident-1",
                type = IncidentType.VIBRATION,
                severity = IncidentSeverity.WARNING,
                lifecycle = IncidentLifecycle.CLOSED,
                evidenceSummary = "VIBRATION: movement",
                updatedAtMs = TEST_TIMESTAMP_MS,
                deliveryState = DeliveryState.SENT,
            ),
        ),
    )

private fun baseState(protectionState: ProtectionState): ProtectionUiState = ProtectionUiState(
    destination = ProtectionDestination.PROTECTION,
    protection = ProtectionStatusUiState(
        state = protectionState,
        lastTransitionAtMs = TEST_TIMESTAMP_MS,
        serviceRunning = true,
        telegramPolling = true,
        telegramReachable = true,
        lastTelegramContactAtMs = TEST_TIMESTAMP_MS,
        permissionBlockers = emptySet(),
        sensorHealth = emptyMap(),
        degradationReasons = emptySet(),
        batteryLevelPercent = 82,
        batteryTemperatureCelsius = 31.5f,
        lastIncident = null,
        lastDeliveryState = null,
    ),
    events = emptyList(),
    settings = ProtectionSettingsSummary(
        tokenConfigured = false,
        pairedOwnerCount = 0,
        pairingCode = "271828",
        sensitivity = 5,
        smsFallbackConfigured = false,
        missingPermissions = emptySet(),
    ),
    armingSecondsRemaining = null,
    eventsLoading = false,
    eventsError = null,
    operationInFlight = false,
    message = null,
)

private fun fakeActions(): ProtectionAppActions = ProtectionAppActions(
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

private const val TEST_ONLY_TOKEN = "123456:TEST_ONLY_NOT_A_REAL_TOKEN"
private const val UNSAVED_TOKEN = "123456:UNSAVED_TEST_TOKEN"
private const val UNSAVED_SMS_KEY = "UNSAVED_SMS_KEY"
private const val TEST_TIMESTAMP_MS = 1_725_000_000_000L
private const val OPAQUE_ALPHA = 0.95f
private const val CHANNEL_TOLERANCE = 0.02f
private const val NEAR_BLACK = 0.10f
private const val NEAR_WHITE = 0.90f
