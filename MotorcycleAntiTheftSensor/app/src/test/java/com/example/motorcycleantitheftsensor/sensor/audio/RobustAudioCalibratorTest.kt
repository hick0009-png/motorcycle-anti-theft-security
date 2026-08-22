package com.example.motorcycleantitheftsensor.sensor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

class RobustAudioCalibratorTest {

    private lateinit var calibrator: RobustAudioCalibrator

    @Before
    fun setUp() {
        calibrator = RobustAudioCalibrator()
    }

    private fun generateSineFrame(frequencyHz: Double, amplitude: Double, sampleRate: Int = 16000, frameSize: Int = 1600): ShortArray {
        val samples = ShortArray(frameSize)
        for (i in 0 until frameSize) {
            val t = i.toDouble() / sampleRate
            val sampleVal = (sin(2.0 * Math.PI * frequencyHz * t) * amplitude * 32767.0).toInt()
            samples[i] = sampleVal.coerceIn(-32768, 32767).toShort()
        }
        return samples
    }

    private fun generateQuietFrame(amplitude: Double = 0.01): ShortArray {
        return generateSineFrame(440.0, amplitude)
    }

    @Test
    fun `extractFeatures calculates valid RMS dBFS, peak, and clipping ratio`() {
        val frame = generateSineFrame(440.0, 0.5) // ~ -6 dBFS peak, -9 dBFS RMS
        val features = calibrator.extractFeatures(frame, frame.size, atElapsedMs = 1000L)

        assertEquals(1000L, features.atElapsedMs)
        assertTrue("RMS dBFS should be around -9 dBFS", features.rmsDbfs in -12.0..-6.0)
        assertTrue("Peak should be around 0.5", features.peak in 0.45..0.55)
        assertEquals(0.0, features.clippingRatio, 0.001)
    }

    @Test
    fun `extractFeatures detects clipping ratio on maxed signals`() {
        val clippedFrame = ShortArray(1600) { 32767.toShort() }
        val features = calibrator.extractFeatures(clippedFrame, clippedFrame.size, atElapsedMs = 1000L)
        assertEquals(1.0, features.clippingRatio, 0.001)
        assertEquals(1.0, features.peak, 0.001)
        assertTrue(features.rmsDbfs >= -0.1)
    }

    @Test
    fun `calibrator accepts stable baseline at 10 seconds with quiet frames`() {
        // Feed 100 frames (100ms each = 10s) of quiet audio
        for (i in 0 until 100) {
            val frame = generateQuietFrame(0.01)
            val features = calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L)
            calibrator.feedFrame(features)
        }

        val baseline = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)
        assertNotNull(baseline)
        assertTrue("Baseline should be marked stable", baseline!!.stable)
        assertTrue("MAD should be <= 4 dB", baseline.madDb <= 4.0)
        assertTrue("P95 - median should be <= 12 dB", baseline.p95Dbfs - baseline.medianDbfs <= 12.0)
    }

    @Test
    fun `calibrator rejects stability at 10s if clipping ratio is above 1 percent`() {
        // 90 normal frames, 10 fully clipped frames
        for (i in 0 until 90) {
            val frame = generateQuietFrame(0.01)
            val features = calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L)
            calibrator.feedFrame(features)
        }
        for (i in 90 until 100) {
            val clippedFrame = ShortArray(1600) { 32767.toShort() }
            val features = calibrator.extractFeatures(clippedFrame, clippedFrame.size, atElapsedMs = i * 100L)
            calibrator.feedFrame(features)
        }

        val baseline = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)
        assertNotNull(baseline)
        assertFalse("Baseline with excessive clipping should not be stable at 10s", baseline!!.stable)
    }

    @Test
    fun `calibrator extends learning to 30s when noisy and marks degraded if still unstable`() {
        // Highly variable noise
        for (i in 0 until 300) {
            val amp = if (i % 2 == 0) 0.001 else 0.8 // Wild swings
            val frame = generateSineFrame(440.0, amp)
            val features = calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L)
            calibrator.feedFrame(features)
        }

        val baselineAt10s = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)
        assertNotNull(baselineAt10s)
        assertFalse("Should not be stable at 10s", baselineAt10s!!.stable)

        val baselineAt30s = calibrator.evaluateCalibration(nowElapsedMs = 30_000L)
        assertNotNull(baselineAt30s)
        assertFalse("Should still be unstable at 30s", baselineAt30s!!.stable)
        assertTrue(calibrator.isDegraded(nowElapsedMs = 30_000L))
    }

    @Test
    fun `calibrator clamps 30s adaptation to at most 1_5 dB`() {
        // Calibrate initially to quiet baseline
        for (i in 0 until 100) {
            val frame = generateQuietFrame(0.01) // around -40 dBFS
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }
        val initialBaseline = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)!!
        assertTrue(initialBaseline.stable)

        // Simulate 30s window with much louder ambient (e.g. +20 dB higher)
        for (i in 100 until 400) {
            val frame = generateSineFrame(440.0, 0.1)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }

        val adaptedBaseline = calibrator.adaptWindow(nowElapsedMs = 40_000L, isActivityFrozen = false)
        assertNotNull(adaptedBaseline)
        val delta = Math.abs(adaptedBaseline!!.medianDbfs - initialBaseline.medianDbfs)
        assertTrue("Adaptation must be clamped to <= 1.5 dB, but was $delta", delta <= 1.5001)
    }

    @Test
    fun `adaptation is frozen when activity flag is set`() {
        for (i in 0 until 100) {
            val frame = generateQuietFrame(0.01)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }
        val initialBaseline = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)!!

        for (i in 100 until 400) {
            val frame = generateSineFrame(440.0, 0.1)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }

        val adaptedBaseline = calibrator.adaptWindow(nowElapsedMs = 40_000L, isActivityFrozen = true)
        assertEquals(initialBaseline.medianDbfs, adaptedBaseline!!.medianDbfs, 0.001)
    }

    @Test
    fun `isolated spikes do not skew median baseline`() {
        for (i in 0 until 95) {
            val frame = generateQuietFrame(0.01)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }
        // 5 spike frames
        for (i in 95 until 100) {
            val frame = generateSineFrame(440.0, 0.9)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }

        val baseline = calibrator.evaluateCalibration(nowElapsedMs = 10_000L)!!
        assertTrue(baseline.stable)
        assertTrue("Median should remain close to quiet level", baseline.medianDbfs < -30.0)
    }

    @Test
    fun `boundary tests for silence, full-scale, and empty frames`() {
        // Silence
        val silence = ShortArray(1600) { 0 }
        val silenceFeatures = calibrator.extractFeatures(silence, silence.size, atElapsedMs = 100L)
        assertEquals(-120.0, silenceFeatures.rmsDbfs, 0.01)
        assertEquals(0.0, silenceFeatures.peak, 0.001)
        assertFalse(silenceFeatures.rmsDbfs.isNaN())

        // Empty frame
        val emptyFeatures = calibrator.extractFeatures(silence, 0, atElapsedMs = 200L)
        assertEquals(-120.0, emptyFeatures.rmsDbfs, 0.01)

        // Full scale
        val fullScale = ShortArray(1600) { 32767.toShort() }
        val fullScaleFeatures = calibrator.extractFeatures(fullScale, fullScale.size, atElapsedMs = 300L)
        assertFalse(fullScaleFeatures.rmsDbfs.isNaN())
        assertEquals(1.0, fullScaleFeatures.peak, 0.001)
    }

    @Test
    fun `fewer than 80 frames marks baseline unstable`() {
        for (i in 0 until 50) {
            val frame = generateQuietFrame(0.01)
            calibrator.feedFrame(calibrator.extractFeatures(frame, frame.size, atElapsedMs = i * 100L))
        }
        val baseline = calibrator.evaluateCalibration(nowElapsedMs = 5_000L)
        assertNotNull(baseline)
        assertFalse("Baseline with <80 frames should not be stable", baseline!!.stable)
    }
}
