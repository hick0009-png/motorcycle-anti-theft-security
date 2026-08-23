package com.example.motorcycleantitheftsensor.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paper-light monochrome scheme: white surfaces, black ink.
val OledSecurityColorScheme = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF171717),
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color(0xFF171717),
    tertiary = ArmedGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4F4EB),
    onTertiaryContainer = Color(0xFF146C43),
    background = Color.White,
    onBackground = Color(0xFF171717),
    surface = Color.White,
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFEEEEEC),
    onSurfaceVariant = Color(0xFF4B4B4B),
    outline = Color(0xFF767676),
    error = Color(0xFFA81818),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFFA81818),
)

@Composable
fun MotorcycleAntiTheftSensorTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OledSecurityColorScheme,
        typography = Typography,
        content = content,
    )
}
