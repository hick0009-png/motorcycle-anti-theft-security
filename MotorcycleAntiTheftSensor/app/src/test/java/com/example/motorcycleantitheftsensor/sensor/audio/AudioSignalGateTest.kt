package com.example.motorcycleantitheftsensor.sensor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioSignalGateTest {

    private lateinit var gate: AudioSignalGate

    @Before
    fun setUp() {
        gate = AudioSignalGate()
    }

    @Test
    fun `suppresses frame when below noise relative threshold`() {
        // baseline median = -40 dBFS, P95 = -34 dBFS
        // gate threshold = max(-34 + 6, -40 + 12) = -28 dBFS
        val baseline = AudioBaseline(medianDbfs = -40.0, p95Dbfs = -34.0, madDb = 2.0, stable = true)
        val features = AudioFrameFeatures(rmsDbfs = -32.0, peak = 0.03, clippingRatio = 0.0, atElapsedMs = 15_000L)

        val decision = gate.evaluate(features, baseline)
        assertTrue(decision is AudioGateDecision.Suppress)
    }

    @Test
    fun `classifies frame when RMS exceeds noise relative threshold`() {
        // baseline median = -40 dBFS, P95 = -34 dBFS -> threshold = -28 dBFS
        val baseline = AudioBaseline(medianDbfs = -40.0, p95Dbfs = -34.0, madDb = 2.0, stable = true)
        val features = AudioFrameFeatures(rmsDbfs = -25.0, peak = 0.1, clippingRatio = 0.0, atElapsedMs = 15_000L)

        val decision = gate.evaluate(features, baseline)
        assertTrue(decision is AudioGateDecision.Classify)
        val classify = decision as AudioGateDecision.Classify
        assertEquals(15_000L, classify.onsetElapsedMs)
    }

    @Test
    fun `classifies frame when short peak exceeds noise relative threshold`() {
        // RMS might be low if impulse is short, but peak is high
        val baseline = AudioBaseline(medianDbfs = -50.0, p95Dbfs = -45.0, madDb = 1.5, stable = true)
        // threshold = max(-45 + 6 = -39, -50 + 12 = -38) = -38 dBFS
        // peak = 0.5 (~ -6 dBFS peak)
        val features = AudioFrameFeatures(rmsDbfs = -42.0, peak = 0.5, clippingRatio = 0.0, atElapsedMs = 16_000L)

        val decision = gate.evaluate(features, baseline)
        assertTrue(decision is AudioGateDecision.Classify)
    }

    @Test
    fun `suppresses frame when baseline is null`() {
        val features = AudioFrameFeatures(rmsDbfs = -10.0, peak = 0.8, clippingRatio = 0.0, atElapsedMs = 5_000L)
        val decision = gate.evaluate(features, null)
        assertTrue(decision is AudioGateDecision.Suppress)
    }
}
