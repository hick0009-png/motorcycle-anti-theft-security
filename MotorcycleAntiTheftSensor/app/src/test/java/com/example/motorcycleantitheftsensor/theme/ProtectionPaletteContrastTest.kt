package com.example.motorcycleantitheftsensor.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every colour pair the app draws, measured.
 *
 * A palette is not a matter of taste where it carries meaning. "Protecting" and "alerting"
 * are read at arm's length, outdoors, by someone who is worried — and the app had shipped a
 * healthy green at 4.33:1 and a secondary text colour that met AA on white but not on the
 * tinted surface it was mostly drawn on. Neither was visible as a mistake; both are
 * arithmetic, so they are checked as arithmetic.
 *
 * Thresholds are WCAG 2.2: 4.5:1 for normal text, 3:1 for boundaries that carry meaning.
 */
class ProtectionPaletteContrastTest {

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(name: String, foreground: Color, background: Color, minimum: Double = 4.5) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$name measured %.2f:1, needs %.1f:1".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    private val white = Color.White

    @Test
    fun textOnWhiteIsReadable() {
        assertReadable("body ink", Ink, white)
        assertReadable("secondary ink", InkMuted, white)
        assertReadable("brand navy", Navy, white)
        assertReadable("header navy", NavyHeader, white)
    }

    @Test
    fun textOnTheCanvasIsReadable() {
        assertReadable("body ink on canvas", Ink, Canvas)
        assertReadable("secondary ink on canvas", InkMuted, Canvas)
    }

    @Test
    fun secondaryTextIsReadableOnTheTintedSurfaceItSitsOn() {
        // The failure this whole file exists for: the previous #667085 measured 4.97:1 on
        // white and 4.28:1 here, and every dense settings row draws it on exactly this.
        assertReadable("secondary ink on navy-soft", InkMuted, SurfaceNavySoft)
        assertReadable("secondary ink on muted surface", InkMuted, SurfaceMuted)
    }

    @Test
    fun everyStatusIsReadableOnWhiteAndOnItsOwnContainer() {
        assertReadable("healthy on white", StatusHealthy, white)
        assertReadable("healthy on its container", StatusHealthy, StatusHealthyContainer)
        assertReadable("warning on white", StatusWarning, white)
        assertReadable("warning on its container", StatusWarning, StatusWarningContainer)
        assertReadable("critical on white", StatusCritical, white)
        assertReadable("critical on its container", StatusCritical, StatusCriticalContainer)
        assertReadable("idle on white", StatusIdle, white)
        assertReadable("idle on its container", StatusIdle, StatusIdleContainer)
    }

    @Test
    fun statusTextStaysReadableOnTheTintedSurface() {
        assertReadable("healthy on navy-soft", StatusHealthy, SurfaceNavySoft)
        assertReadable("warning on navy-soft", StatusWarning, SurfaceNavySoft)
        assertReadable("critical on navy-soft", StatusCritical, SurfaceNavySoft)
    }

    @Test
    fun filledSurfacesCarryWhiteText() {
        assertReadable("white on navy", white, Navy)
        assertReadable("white on header navy", white, NavyHeader)
        assertReadable("white on healthy", white, StatusHealthy)
        assertReadable("white on critical", white, StatusCritical)
    }

    @Test
    fun bordersThatMeanSomethingAreVisible() {
        // Non-text contrast: an input outline or an inactive track has to be seen to be
        // understood. The previous #C8D3DF measured 1.5:1 — invisible on white.
        assertReadable("outline on white", OutlineStrong, white, minimum = 3.0)
        assertReadable("outline on canvas", OutlineStrong, Canvas, minimum = 3.0)
    }

    @Test
    fun theSchemeItselfUsesTheseColoursAndNotOthers() {
        // Stops a screen from being "fixed" by editing the scheme past the measured tokens.
        assertTrue(MotoGuardColorScheme.primary == Navy)
        assertTrue(MotoGuardColorScheme.onSurface == Ink)
        assertTrue(MotoGuardColorScheme.onSurfaceVariant == InkMuted)
        assertTrue(MotoGuardColorScheme.error == StatusCritical)
        assertTrue(MotoGuardColorScheme.outline == OutlineStrong)
        assertTrue(LightProtectionStatusColors.healthy == StatusHealthy)
        assertTrue(LightProtectionStatusColors.warning == StatusWarning)
        assertTrue(LightProtectionStatusColors.critical == StatusCritical)
        assertTrue(LightProtectionStatusColors.idle == StatusIdle)
    }
}
