package com.br.resonance

/**
 * Live receiver playback metrics and diagnostics snapshot.
 */
data class PlaybackDiagnostics(
    val isRunning: Boolean = false,
    val totalPackets: Long = 0L,
    val totalBytes: Long = 0L,
    val bufferDepthMs: Int = 0,
    val queuedFrames: Int = 0,
    val droppedPackets: Long = 0L,
    val underrunCount: Long = 0L,
    val peakPercent: Int = 0,
    val rmsDbfs: Float = -100f,
    val isSilent: Boolean = true,
    val trackState: String = "STOPPED",
    val statusMessage: String = "Receiver stopped.",
    val errorMessage: String? = null
)
