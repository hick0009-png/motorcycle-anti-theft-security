package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 of the profile-aware Thai UX plan: bounded, locale-aware formatting for every
 * accepted quantity. Normal-layer copy never carries raw units; advanced formatting
 * functions return [FormattedMeasurement] with a one-sentence Thai interpretation.
 */
class ProtectionValueFormatterTest {

    @Test
    fun doorAngleUsesDegreeSymbolWithoutSpace() {
        assertEquals("15°", ProtectionValueFormatter.doorAngle(15))
        assertEquals("5°", ProtectionValueFormatter.doorAngle(5))
        assertEquals("90°", ProtectionValueFormatter.doorAngle(90))
    }

    @Test
    fun temperatureUsesSpaceBeforeCelsiusAndSuppressesTrailingZero() {
        assertEquals("35 °C", ProtectionValueFormatter.temperatureCelsius(35.0))
        assertEquals("36.5 °C", ProtectionValueFormatter.temperatureCelsius(36.5))
    }

    @Test
    fun durationSwitchesToSecondsAtOrAboveOneSecond() {
        assertEquals("1.5 วินาที", ProtectionValueFormatter.duration(1_500))
        assertEquals("2 วินาที", ProtectionValueFormatter.duration(2_000))
        assertEquals("10 วินาที", ProtectionValueFormatter.duration(10_000))
    }

    @Test
    fun durationBelowOneSecondStaysInMilliseconds() {
        assertEquals("750 มิลลิวินาที", ProtectionValueFormatter.duration(750))
        assertEquals("250 มิลลิวินาที", ProtectionValueFormatter.duration(250))
    }

    @Test
    fun luxFormatsAdvancedLightReadingWithInterpretation() {
        val formatted = ProtectionValueFormatter.lux(120.0)
        assertEquals("120 lux", formatted.value)
        assertFalse(formatted.interpretation.isBlank())
    }

    @Test
    fun accelerationUsesSquareMetersPerSecondUnit() {
        val formatted = ProtectionValueFormatter.measurement(2.4, SensorUnit.METERS_PER_SECOND_SQUARED)
        assertEquals("2.4 m/s²", formatted.value)
        assertTrue(formatted.interpretation.contains("ความเร่ง"))
    }

    @Test
    fun angularRateUsesRadiansPerSecondUnit() {
        val formatted = ProtectionValueFormatter.measurement(0.8, SensorUnit.RADIANS_PER_SECOND)
        assertEquals("0.8 rad/s", formatted.value)
        assertTrue(formatted.interpretation.contains("หมุน"))
    }

    @Test
    fun magneticChangeUsesMicroteslaWithInterferenceWarning() {
        val formatted = ProtectionValueFormatter.measurement(42.0, SensorUnit.MICROTESLA)
        assertEquals("42 µT", formatted.value)
        assertTrue(formatted.interpretation.contains("แม่เหล็ก"))
    }

    @Test
    fun degreesUsePlainDegreeSymbol() {
        assertEquals("18°", ProtectionValueFormatter.measurement(18.0, SensorUnit.DEGREES).value)
    }

    @Test
    fun audioLevelUsesDbfsAndExplainsQuieterMeansMoreNegative() {
        val formatted = ProtectionValueFormatter.audioDbfs(-36.0)
        assertEquals("-36 dBFS", formatted.value)
        assertTrue(formatted.interpretation.contains("เงียบ"))
    }

    @Test
    fun largeValuesKeepThaiLocaleDigitGroupingWithoutTrailingZero() {
        assertEquals("1,200 lux", ProtectionValueFormatter.lux(1200.0).value)
        assertEquals("1,000 วินาที", ProtectionValueFormatter.duration(1_000_000))
    }
}