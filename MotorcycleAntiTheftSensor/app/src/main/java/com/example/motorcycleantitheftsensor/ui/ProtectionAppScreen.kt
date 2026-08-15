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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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

    MaterialTheme(colorScheme = ProtectionMonochromeColorScheme) {
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
                    color = Color.Black,
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        PrimaryDestination.entries.forEach { item ->
                            val selected = state.destination == item.destination
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
                                        contentDescription = "${item.label} destination"
                                    }
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 2.dp,
                                    alignment = Alignment.Top,
                                ),
                            ) {
                                Surface(
                                    color = if (selected) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(64.dp)
                                            .height(26.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(item.iconResource),
                                            contentDescription = null,
                                            tint = if (selected) Color.Black else Color.White,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .testTag(
                                                    "primary_destination_icon_${item.name}",
                                                ),
                                        )
                                    }
                                }
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
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

private val NearBlack = Color(0xFF0D0D0D)
private val DarkGray = Color(0xFF1E1E1E)
private val MediumGray = Color(0xFF424242)
private val LightGray = Color(0xFFBDBDBD)

private val ProtectionMonochromeColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    inversePrimary = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    tertiary = Color.White,
    onTertiary = Color.Black,
    tertiaryContainer = Color.White,
    onTertiaryContainer = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = DarkGray,
    onSurfaceVariant = Color.White,
    surfaceTint = Color.White,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color.White,
    onErrorContainer = Color.Black,
    outline = LightGray,
    outlineVariant = MediumGray,
    scrim = Color.Black,
    surfaceBright = MediumGray,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = DarkGray,
    surfaceContainerHighest = MediumGray,
    surfaceContainerLow = NearBlack,
    surfaceContainerLowest = Color.Black,
    surfaceDim = Color.Black,
)

private enum class PrimaryDestination(
    val destination: ProtectionDestination,
    val label: String,
    val iconResource: Int,
) {
    PROTECTION(
        destination = ProtectionDestination.PROTECTION,
        label = "Protection",
        iconResource = android.R.drawable.ic_secure,
    ),
    EVENTS(
        destination = ProtectionDestination.EVENTS,
        label = "Events",
        iconResource = android.R.drawable.ic_menu_recent_history,
    ),
    SETTINGS(
        destination = ProtectionDestination.SETTINGS,
        label = "Settings",
        iconResource = android.R.drawable.ic_menu_preferences,
    ),
}

private const val PRIMARY_DESTINATION_TAG = "primary_destination"
private const val PRIMARY_NAVIGATION_TAG = "primary_navigation"
