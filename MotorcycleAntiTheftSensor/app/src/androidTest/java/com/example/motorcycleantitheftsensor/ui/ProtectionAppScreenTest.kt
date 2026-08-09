package com.example.motorcycleantitheftsensor.ui

import android.view.WindowManager
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

class ProtectionAppScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shellHasExactlyThreePrimaryDestinationsAndNoDemoControls() {
        compose.setContent { ProtectionAppScreen(healthyState(), fakeActions()) }

        compose.onNodeWithText("Protection").assertExists()
        compose.onNodeWithText("Events").assertExists()
        compose.onNodeWithText("Settings").assertExists()
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

        compose.onAllNodes(hasText("Arm protection")).assertCountEquals(1)
        compose.onAllNodes(hasText("Disarm protection")).assertCountEquals(0)
        compose.onNodeWithText("Arm protection").assertHeightIsAtLeast(48.dp)
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

        listOf("Protection", "Events", "Settings").forEach { label ->
            val labelTag = "primary_destination_label_${label.uppercase()}"
            compose.onNodeWithTag(labelTag, useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("$label destination")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(80.dp)
            compose.onNodeWithTag(labelTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(16.dp)
            compose.onNodeWithTag(
                "primary_destination_icon_${label.uppercase()}",
                useUnmergedTree = true,
            ).assertHeightIsEqualTo(24.dp)
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Diagnostics"))
        listOf("Protection", "Events", "Settings").forEach { label ->
            compose.onNodeWithContentDescription("$label destination")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag(
                "primary_destination_label_${label.uppercase()}",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            compose.onNodeWithTag(
                "primary_destination_icon_${label.uppercase()}",
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
            "Microphone access is missing. Noise detection will be unavailable.",
        ).assertExists()
        compose.onNodeWithText("Reduced sensor coverage").assertExists()
        compose.onNodeWithText("Protection blockers").assertDoesNotExist()
        compose.onNodeWithText("Review permissions").performClick()
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

        compose.onNodeWithText("Protection degraded").assertExists()
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
    fun authenticatorSecretAppearsOnlyAfterAsyncCompletionAndClearsOnCancel() {
        val secret = "TRANSIENT-SETUP-SECRET"
        var setupCompletion: ((AuthenticatorSetupDetails?) -> Unit)? = null
        val actions = fakeActions().copy(
            beginAuthenticatorSetup = { onComplete ->
                setupCompletion = onComplete
                {}
            },
        )
        compose.setContent {
            ProtectionAppScreen(
                baseState(ProtectionState.DISARMED_ONLINE).copy(
                    destination = ProtectionDestination.SETTINGS,
                ),
                actions,
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Set up authenticator"))
        compose.onNodeWithText("Set up authenticator").performClick()
        compose.onAllNodes(hasText(secret)).assertCountEquals(0)
        compose.runOnIdle {
            requireNotNull(setupCompletion)(
                AuthenticatorSetupDetails(secret = secret, uri = "otpauth://transient"),
            )
        }
        compose.onNode(hasTestTag("authenticator_secret")).assertExists()
        compose.onAllNodes(hasText(secret)).assertCountEquals(0)
        compose.onNodeWithText("Reveal secret").performClick()
        compose.onNodeWithContentDescription("Authenticator secret: $secret").assertExists()
        compose.runOnIdle {
            assertTrue(
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodes(hasTestTag("authenticator_secret")).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
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
        compose.onNodeWithText("Events").performClick()
    }

    private fun openSettings() {
        compose.onNodeWithText("Settings").performClick()
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
            authenticatorConfigured = true,
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
        authenticatorConfigured = false,
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
    beginAuthenticatorSetup = { _ -> {} },
    cancelAuthenticatorSetup = {},
    verifyAuthenticator = { _, _ -> {} },
    retry = {},
)

private const val TEST_ONLY_TOKEN = "123456:TEST_ONLY_NOT_A_REAL_TOKEN"
private const val UNSAVED_TOKEN = "123456:UNSAVED_TEST_TOKEN"
private const val UNSAVED_SMS_KEY = "UNSAVED_SMS_KEY"
private const val TEST_TIMESTAMP_MS = 1_725_000_000_000L
private const val OPAQUE_ALPHA = 0.95f
private const val CHANNEL_TOLERANCE = 0.02f
private const val NEAR_BLACK = 0.10f
private const val NEAR_WHITE = 0.90f
