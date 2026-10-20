package com.br.resonance

/**
 * Explicit capture states for Sender:
 * Distinguishes permission granted, session active, samples received, and non-silent audio.
 */
enum class CaptureState(val displayName: String, val badgeColorHex: String) {
    IDLE("Idle", "#757575"),
    PERMISSIONS_REQUIRED("Permissions Required", "#F57C00"),
    PROJECTION_REQUESTED("Waiting for Consent", "#0288D1"),
    PERMISSION_GRANTED("Permission Granted", "#1976D2"),
    SESSION_STARTING("Session Starting", "#7B1FA2"),
    CAPTURE_ACTIVE("Capture Active (Waiting for Audio)", "#FBC02D"),
    SAMPLES_RECEIVED("Samples Received (Silence)", "#0097A7"),
    NON_SILENT_AUDIO("Non-Silent Audio Detected", "#388E3C"),
    REVOKED("Session Revoked by System", "#D32F2F"),
    STOPPED("Capture Stopped", "#616161"),
    ERROR("Capture Error", "#C2185B")
}

/**
 * Live capture metrics and diagnostics snapshot.
 */
data class CaptureDiagnostics(
    val state: CaptureState = CaptureState.IDLE,
    val totalFrames: Long = 0L,
    val totalSamples: Long = 0L,
    val totalBytes: Long = 0L,
    val nonSilentFrames: Long = 0L,
    val readErrors: Long = 0L,
    val currentRmsDbfs: Float = -100f,
    val peakAmplitude: Int = 0,
    val peakPercent: Int = 0,
    val isSilent: Boolean = true,
    val lastReadSamples: Int = 0,
    val sessionStartTimeMs: Long = 0L,
    val lastSampleTimeMs: Long = 0L,
    val statusDetail: String = "Ready to start capture and streaming.",
    val errorMessage: String? = null,
    // Network transmission metrics
    val receiverHost: String? = null,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val networkErrors: Long = 0L,
    val isStreaming: Boolean = false,
    val connectedWebClients: Int = 0
) {
    val durationSeconds: Long
        get() = if (sessionStartTimeMs > 0L) (System.currentTimeMillis() - sessionStartTimeMs) / 1000L else 0L

    val activeDurationFormatted: String
        get() {
            val secs = durationSeconds
            val m = secs / 60
            val s = secs % 60
            return String.format("%02d:%02d", m, s)
        }

    val totalMegabytesFormatted: String
        get() = String.format("%.2f MB", totalBytes.toDouble() / (1024.0 * 1024.0))

    val totalMegabytesSentFormatted: String
        get() = String.format("%.2f MB", bytesSent.toDouble() / (1024.0 * 1024.0))
}
