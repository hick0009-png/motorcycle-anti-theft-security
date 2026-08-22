package com.example.motorcycleantitheftsensor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * UI-02: AdaptiveScreenScaler
 * Inspired by GitHub open-source responsive design patterns in Jetpack Compose.
 * Dynamically computes a Scale Factor based on the current device's screen width & height in DP.
 * Scales all UI paddings, button sizes, spacer heights, and typography dynamically.
 */
class AdaptiveScreenScaler(
    val screenWidthDp: Int,
    val screenHeightDp: Int
) {
    // Base design reference width = 360dp, height = 640dp
    val scaleFactor: Float = run {
        val widthRatio = screenWidthDp / 360f
        val heightRatio = screenHeightDp / 640f
        min(widthRatio, heightRatio).coerceIn(0.85f, 1.35f)
    }

    /**
     * Scale a base Dp value according to device screen proportions.
     */
    fun scaleDp(baseDp: Dp): Dp {
        return (baseDp.value * scaleFactor).dp
    }

    /**
     * Scale a base TextUnit (sp) value according to device screen proportions.
     */
    fun scaleSp(baseSp: TextUnit): TextUnit {
        return (baseSp.value * scaleFactor).sp
    }
}

/**
 * Composable helper to get the current AdaptiveScreenScaler instance.
 */
@Composable
fun rememberAdaptiveScreenScaler(): AdaptiveScreenScaler {
    val configuration = LocalConfiguration.current
    return androidx.compose.runtime.remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        AdaptiveScreenScaler(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp
        )
    }
}
