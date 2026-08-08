package com.example.motorcycleantitheftsensor.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
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
    val beginAuthenticatorSetup: () -> String?,
    val verifyAuthenticator: (String) -> Boolean,
    val retry: () -> Unit,
)

@Composable
fun ProtectionAppScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    modifier: Modifier = Modifier,
) {
    MaterialTheme(colorScheme = ProtectionMonochromeColorScheme) {
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar {
                    PrimaryDestination.entries.forEach { item ->
                        NavigationBarItem(
                            modifier = Modifier.testTag(PRIMARY_DESTINATION_TAG),
                            selected = state.destination == item.destination,
                            onClick = { actions.selectDestination(item.destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(item.iconResource),
                                    contentDescription = "${item.label} destination",
                                )
                            },
                            label = { Text(item.label) },
                            alwaysShowLabel = true,
                        )
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
