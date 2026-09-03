package com.example.motorcycleantitheftsensor.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's one colour scheme.
 *
 * Thai-first Moto Guard palette: navy communicates protection, white keeps dense controls
 * legible. It lived on the home screen as a private scheme applied over the real theme,
 * which meant every other screen rendered against a different one. It lives here now, and
 * nothing declares its own.
 */
val MotoGuardColorScheme = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = SurfaceNavySoft,
    onPrimaryContainer = Navy,
    inversePrimary = NavyHeader,
    secondary = NavyHeader,
    onSecondary = Color.White,
    secondaryContainer = SurfaceNavySoft,
    onSecondaryContainer = Navy,
    tertiary = StatusHealthy,
    onTertiary = Color.White,
    tertiaryContainer = StatusHealthyContainer,
    onTertiaryContainer = StatusHealthy,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceNavySoft,
    onSurfaceVariant = InkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Navy,
    inverseOnSurface = Color.White,
    error = StatusCritical,
    onError = Color.White,
    errorContainer = StatusCriticalContainer,
    onErrorContainer = StatusCritical,
    outline = OutlineStrong,
    outlineVariant = OutlineHairline,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Canvas,
    surfaceContainerHighest = SurfaceNavySoft,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceDim = Canvas,
)

/**
 * What the four states of this app look like.
 *
 * Material's scheme has one slot for "something is wrong" and none at all for "protecting,
 * but not with everything" or "not claiming anything yet" — and those are the states this
 * app spends most of its time in. Screens were inventing them locally, which is how the
 * same state ended up two different colours on two screens.
 *
 * Colour is never the only carrier: every place these are used also says the state in Thai.
 */
@Immutable
data class ProtectionStatusColors(
    val healthy: Color,
    val healthyContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val critical: Color,
    val criticalContainer: Color,
    val idle: Color,
    val idleContainer: Color,
)

val LightProtectionStatusColors = ProtectionStatusColors(
    healthy = StatusHealthy,
    healthyContainer = StatusHealthyContainer,
    warning = StatusWarning,
    warningContainer = StatusWarningContainer,
    critical = StatusCritical,
    criticalContainer = StatusCriticalContainer,
    idle = StatusIdle,
    idleContainer = StatusIdleContainer,
)

val LocalProtectionStatusColors = staticCompositionLocalOf { LightProtectionStatusColors }

/** `MaterialTheme.statusColors.healthy`, read the same way as `colorScheme`. */
val MaterialTheme.statusColors: ProtectionStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalProtectionStatusColors.current

@Composable
fun MotorcycleAntiTheftSensorTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalProtectionStatusColors provides LightProtectionStatusColors) {
        MaterialTheme(
            colorScheme = MotoGuardColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
