package com.example.motorcycleantitheftsensor.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
    fun settingsNeverDisplaysStoredCredential() {
        showWithLocalNavigation(configuredSettingsState())

        openSettings()

        compose.onNodeWithText("Token configured").assertExists()
        compose.onNodeWithText(TEST_ONLY_TOKEN).assertDoesNotExist()
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
        compose.onNodeWithText("No protection events").assertExists()
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
                updatedAtMs = 1_725_000_000_000L,
                deliveryState = DeliveryState.SENT,
            ),
        ),
    )

private fun baseState(protectionState: ProtectionState): ProtectionUiState = ProtectionUiState(
    destination = ProtectionDestination.PROTECTION,
    protection = ProtectionStatusUiState(
        state = protectionState,
        lastTransitionAtMs = 1_725_000_000_000L,
        serviceRunning = true,
        telegramPolling = true,
        telegramReachable = true,
        lastTelegramContactAtMs = 1_725_000_000_000L,
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
private const val OPAQUE_ALPHA = 0.95f
private const val CHANNEL_TOLERANCE = 0.02f
private const val NEAR_BLACK = 0.10f
private const val NEAR_WHITE = 0.90f
