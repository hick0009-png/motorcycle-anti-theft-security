package com.example.motorcycleantitheftsensor.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OledSecurityColorScheme = darkColorScheme(
    primary = TrustBlue,
    onPrimary = Color.White,
    primaryContainer = TrustBlueDark,
    onPrimaryContainer = Color.White,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF083344),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = ArmedGreen,
    onTertiary = Color.Black,
    tertiaryContainer = ArmedGreenDark,
    onTertiaryContainer = Color.White,
    background = DarkBackground,
    onBackground = TextHighEmphasis,
    surface = DarkSurface,
    onSurface = TextHighEmphasis,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextMediumEmphasis,
    outline = DarkBorder,
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedDark,
    onErrorContainer = Color.White,
)

@Composable
fun MotorcycleAntiTheftSensorTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OledSecurityColorScheme,
        typography = Typography,
        content = content,
    )
}
