package com.br.resonance

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Result of analyzing a buffer of 16-bit PCM samples.
 */
data class AudioAnalysisResult(
    val peak: Int,
    val peakPercent: Int,
    val rmsDbfs: Float,
    val isSilent: Boolean
)

/**
 * Audio analysis utility for calculating peak amplitude, RMS in dBFS,
 * and distinguishing silence from active media playback.
 * Supports both ShortArray (sender recorder) and ByteArray (receiver datagrams).
 */
object AudioAnalyzer {
    const val SILENCE_RMS_DBFS_THRESHOLD = -50.0f
    const val SILENCE_PEAK_THRESHOLD = 250

    fun analyze(samples: ShortArray, length: Int): AudioAnalysisResult {
        if (length <= 0) {
            return AudioAnalysisResult(0, 0, -100f, true)
        }

        var peak = 0
        var sumSquares = 0.0
        for (i in 0 until length) {
            val s = samples[i].toInt()
            val a = abs(s)
            if (a > peak) peak = a
            sumSquares += (s.toDouble() * s.toDouble())
        }

        val rms = sqrt(sumSquares / length)
        val rmsDbfs = if (rms > 0.0) {
            val db = 20.0 * log10(rms / 32767.0)
            maxOf(db.toFloat(), -100f)
        } else {
            -100f
        }

        val peakPercent = ((peak.toDouble() / 32767.0) * 100.0).toInt().coerceIn(0, 100)
        val isSilent = (rmsDbfs < SILENCE_RMS_DBFS_THRESHOLD) && (peak < SILENCE_PEAK_THRESHOLD)

        return AudioAnalysisResult(peak, peakPercent, rmsDbfs, isSilent)
    }

    fun analyze(pcmBytes: ByteArray, length: Int): AudioAnalysisResult {
        val sampleCount = length / 2
        if (sampleCount <= 0) {
            return AudioAnalysisResult(0, 0, -100f, true)
        }

        var peak = 0
        var sumSquares = 0.0
        var idx = 0
        for (i in 0 until sampleCount) {
            val low = pcmBytes[idx++].toInt() and 0xFF
            val high = pcmBytes[idx++].toInt()
            val sample = (high shl 8) or low
            val a = abs(sample)
            if (a > peak) peak = a
            sumSquares += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sumSquares / sampleCount)
        val rmsDbfs = if (rms > 0.0) {
            val db = 20.0 * log10(rms / 32767.0)
            maxOf(db.toFloat(), -100f)
        } else {
            -100f
        }

        val peakPercent = ((peak.toDouble() / 32767.0) * 100.0).toInt().coerceIn(0, 100)
        val isSilent = (rmsDbfs < SILENCE_RMS_DBFS_THRESHOLD) && (peak < SILENCE_PEAK_THRESHOLD)

        return AudioAnalysisResult(peak, peakPercent, rmsDbfs, isSilent)
    }
}
