package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.ui.events.EventsScreen
import com.example.motorcycleantitheftsensor.ui.protection.ProtectionScreen
import com.example.motorcycleantitheftsensor.ui.settings.SettingsScreen

data class ProtectionAppActions(
    val selectDestination: (ProtectionDestination) -> Unit,
    val entrySetAngle: (Int) -> Unit = {},
    val entryStartCommissioning: (Int) -> Unit = {},
    val entryCancelCommissioning: () -> Unit = {},
    val powerStartCommissioning: () -> Unit = {},
    val powerCancelCommissioning: () -> Unit = {},
    val powerResetCalibration: () -> Unit = {},
    val powerMarkChallengePassed: () -> Unit = {},
    val arm: () -> Unit,
    val disarm: () -> Unit,
    val clearHistory: () -> Unit,
    val changeSensitivity: (Int) -> Unit,
    val requestPermissions: () -> Unit,
    val replaceBotToken: (String) -> Unit,
    val configureSmsFallback: (String, String) -> Unit,
    val retry: () -> Unit,
    val retrySettings: () -> Unit,
    val resetPairing: () -> Unit,
    val consumeMessage: (Long) -> Unit,
    val updateSensorConfiguration: (com.example.motorcycleantitheftsensor.protection.SensorFusionConfiguration) -> Unit = {},
    val applySensorPreset: (com.example.motorcycleantitheftsensor.protection.SensorPreset) -> Unit = {},
    val onSecureFlagChange: (Boolean) -> Unit = {},
    val selectProfile: (com.example.motorcycleantitheftsensor.protection.ProtectionProfile) -> Unit = {},
    val confirmProfileSwitch: () -> Unit = {},
    val cancelProfileSwitch: () -> Unit = {},
    val restoreRecommendedProfile: () -> Unit = {},
    /** Diagnostic: starts or stops the overnight orientation-drift recording. */
    val setDriftRecording: (Boolean) -> Unit = {},
)

@Composable
fun ProtectionAppScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            snackbarHostState.showSnackbar(
                message = message.content.titleTh,
                duration = SnackbarDuration.Short,
            )
            actions.consumeMessage(message.id)
        }
    }

    MaterialTheme(colorScheme = MotoGuardNavyColorScheme) {
        Scaffold(
            modifier = modifier.safeDrawingPadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .height(80.dp)
                        .testTag(PRIMARY_NAVIGATION_TAG),
                    color = CanvasWhite,
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        PrimaryDestination.entries.forEach { item ->
                            val selected = state.destination == item.destination
                            val destinationLabel = stringResource(item.labelRes)
                            val destinationContentDescription = stringResource(item.contentDescriptionRes)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .selectable(
                                        selected = selected,
                                        onClick = {
                                            actions.selectDestination(item.destination)
                                        },
                                        role = Role.Tab,
                                    )
                                    .testTag(PRIMARY_DESTINATION_TAG)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = destinationContentDescription
                                    }
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 2.dp,
                                    alignment = Alignment.Top,
                                ),
                            ) {
                                Surface(
                                    color = if (selected) Navy else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(68.dp)
                                            .height(32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(item.iconResource),
                                            contentDescription = null,
                                            tint = if (selected) Color.White else Navy,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .testTag(
                                                    "primary_destination_icon_${item.name}",
                                                ),
                                        )
                                    }
                                }
                                Text(
                                    text = destinationLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Navy else MutedInk,
                                    modifier = Modifier.testTag(
                                        "primary_destination_label_${item.name}",
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { contentPadding ->
            when (state.destination) {
                ProtectionDestination.PROTECTION -> ProtectionScreen(
                    state = state,
                    actions = actions,
                    contentPadding = contentPadding,
                )

                ProtectionDestination.EVENTS -> EventsScreen(
                    state = state,
                    actions = actions,
                    contentPadding = contentPadding,
                )

                ProtectionDestination.SETTINGS -> SettingsScreen(
                    state = state,
                    actions = actions,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

private val Navy = Color(0xFF16324F)
private val HeaderNavy = Color(0xFF245B85)
private val Canvas = Color(0xFFF8FAFC)
private val CanvasWhite = Color.White
private val SoftNavySurface = Color(0xFFE6EFF8)
private val MutedInk = Color(0xFF667085)
private val Ink = Color(0xFF1C2530)
private val Outline = Color(0xFFC8D3DF)
private val HealthyGreen = Color(0xFF168A63)

// Thai-first Moto Guard palette: navy communicates protection, white keeps dense controls legible.
private val MotoGuardNavyColorScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = SoftNavySurface,
    onPrimaryContainer = Navy,
    inversePrimary = HeaderNavy,
    secondary = HeaderNavy,
    onSecondary = Color.White,
    secondaryContainer = SoftNavySurface,
    onSecondaryContainer = Navy,
    tertiary = HealthyGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F5EF),
    onTertiaryContainer = HealthyGreen,
    background = Canvas,
    onBackground = Ink,
    surface = CanvasWhite,
    onSurface = Ink,
    surfaceVariant = SoftNavySurface,
    onSurfaceVariant = MutedInk,
    surfaceTint = Color.Transparent,
    inverseSurface = Navy,
    inverseOnSurface = Color.White,
    error = Color(0xFFA81818),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFFA81818),
    outline = Outline,
    outlineVariant = SoftNavySurface,
    scrim = Color.Black,
    surfaceBright = CanvasWhite,
    surfaceContainer = CanvasWhite,
    surfaceContainerHigh = Canvas,
    surfaceContainerHighest = SoftNavySurface,
    surfaceContainerLow = CanvasWhite,
    surfaceContainerLowest = CanvasWhite,
    surfaceDim = Canvas,
)

private enum class PrimaryDestination(
    val destination: ProtectionDestination,
    val labelRes: Int,
    val contentDescriptionRes: Int,
    val iconResource: Int,
) {
    PROTECTION(
        destination = ProtectionDestination.PROTECTION,
        labelRes = com.example.motorcycleantitheftsensor.R.string.destination_protection_label,
        contentDescriptionRes = com.example.motorcycleantitheftsensor.R.string.destination_protection_content_description,
        iconResource = com.example.motorcycleantitheftsensor.R.drawable.ic_moto_guard_protection,
    ),
    EVENTS(
        destination = ProtectionDestination.EVENTS,
        labelRes = com.example.motorcycleantitheftsensor.R.string.destination_events_label,
        contentDescriptionRes = com.example.motorcycleantitheftsensor.R.string.destination_events_content_description,
        iconResource = com.example.motorcycleantitheftsensor.R.drawable.ic_moto_guard_events,
    ),
    SETTINGS(
        destination = ProtectionDestination.SETTINGS,
        labelRes = com.example.motorcycleantitheftsensor.R.string.destination_settings_label,
        contentDescriptionRes = com.example.motorcycleantitheftsensor.R.string.destination_settings_content_description,
        iconResource = com.example.motorcycleantitheftsensor.R.drawable.ic_moto_guard_settings,
    ),
}

private const val PRIMARY_DESTINATION_TAG = "primary_destination"
private const val PRIMARY_NAVIGATION_TAG = "primary_navigation"
