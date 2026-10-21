package com.br.resonance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Foreground service managing AudioPlaybackCapture, live diagnostics,
 * and low-latency UDP network audio streaming to up to 5 receivers.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.br.resonance.START_CAPTURE"
        const val ACTION_STOP = "com.br.resonance.STOP_CAPTURE"
        const val ACTION_DISMISS = "com.br.resonance.DISMISS_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_HOSTS = "hosts"

        const val PORT = 50005
        const val SAMPLE_RATE = 24000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS) / 1000 // 480 samples per 20ms frame
        const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "dual_audio_capture_v3"
    }

    inner class LocalBinder : Binder() {
        fun getService(): CaptureService = this@CaptureService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var shouldRebindSubmix = false
    private var captureThread: Thread? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: AudioRecord? = null
    private var keepAliveTrack: AudioTrack? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var socket: DatagramSocket? = null
    private val targetAddresses = java.util.concurrent.CopyOnWriteArrayList<InetAddress>()
    private var webRelayServer: WebRelayServer? = null

    fun ensureWebRelayRunning(): WebRelayServer {
        var server = webRelayServer
        if (server == null) {
            server = WebRelayServer(this, 8080).apply {
                onClientCountChanged = { count ->
                    updateDiagnostics(currentSnapshot.copy(connectedWebClients = count))
                }
                start()
            }
            webRelayServer = server
        }
        return server
    }

    fun getConnectedWebClients(): Int = webRelayServer?.connectedClientCount ?: 0

    override fun onCreate() {
        super.onCreate()
        ensureWebRelayRunning()
    }

    fun setTargetHosts(hosts: List<String>) {
        thread(name = "ResolveHostsThread") {
            val resolved = mutableListOf<InetAddress>()
            for (h in hosts) {
                val clean = h.trim()
                if (clean.isNotBlank()) {
                    try {
                        resolved.add(InetAddress.getByName(clean))
                    } catch (_: Exception) {}
                }
            }
            targetAddresses.clear()
            targetAddresses.addAll(resolved)
            val count = targetAddresses.size
            if (running) {
                updateNotification(if (count > 0) "Streaming to $count receiver(s)" else "Waiting for receivers...")
            }
        }
    }

    // Real-time diagnostic state
    @Volatile private var currentSnapshot = CaptureDiagnostics()
    private var diagnosticListener: ((CaptureDiagnostics) -> Unit)? = null

    // UI update throttling (approx every 50ms = 20 fps)
    private var lastUiUpdateTimeMs = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    fun setDiagnosticListener(listener: ((CaptureDiagnostics) -> Unit)?) {
        this.diagnosticListener = listener
        listener?.invoke(currentSnapshot)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_DISMISS) {
            stopCaptureSession("Capture dismissed by user")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            stopByUser()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }
        val hostsList = intent?.getStringArrayListExtra(EXTRA_HOSTS)
        val initialHosts: List<String> = if (hostsList != null && hostsList.isNotEmpty()) {
            hostsList
        } else {
            val singleHost = intent?.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
            if (singleHost.isNotBlank()) listOf(singleHost) else emptyList()
        }

        if (resultCode == 0 || data == null) {
            updateDiagnostics(
                currentSnapshot.copy(
                    state = CaptureState.ERROR,
                    statusDetail = "Missing MediaProjection consent intent or result code.",
                    errorMessage = "Invalid consent data passed to service."
                )
            )
            stopSelf()
            return START_NOT_STICKY
        }

        startCaptureSession(resultCode, data, initialHosts)
        return START_NOT_STICKY
    }

    private fun startCaptureSession(resultCode: Int, data: Intent, initialHosts: List<String>) {
        running = true
        // Step 1: Promote to foreground service first (Strict Android 14+ requirement)
        createNotificationChannel()
        val notifText = if (initialHosts.isNotEmpty()) "Capturing & streaming to ${initialHosts.size} device(s)" else "Capturing permitted playback audio..."
        val initialNotification = buildNotification(notifText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            updateDiagnostics(
                currentSnapshot.copy(
                    state = CaptureState.ERROR,
                    statusDetail = "Failed to start foreground service: ${e.message}",
                    errorMessage = e.message
                )
            )
            stopSelf()
            return
        }

        updateDiagnostics(
            currentSnapshot.copy(
                state = CaptureState.SESSION_STARTING,
                statusDetail = "Configuring MediaProjection and AudioPlaybackCapture...",
                sessionStartTimeMs = System.currentTimeMillis(),
                receiverHost = if (initialHosts.isNotEmpty()) initialHosts.joinToString(", ") else null,
                isStreaming = initialHosts.isNotEmpty()
            )
        )

        // Step 2: Acquire MediaProjection and register Callback
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            handleError("MediaProjectionManager service is not available on this device.")
            return
        }

        try {
            projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                handleError("MediaProjectionManager returned null projection.")
                return
            }

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    mainHandler.post {
                        handleRevocation("MediaProjection was stopped or revoked by Android.")
                    }
                }
            }
            projection?.registerCallback(projectionCallback!!, mainHandler)
        } catch (e: SecurityException) {
            handleError("SecurityException obtaining MediaProjection: ${e.message}. Ensure foreground service is running.")
            return
        } catch (e: Exception) {
            handleError("Error obtaining MediaProjection: ${e.message}")
            return
        }

        // Step 2.5: Warm up system AudioPolicy submix with keep-alive track before building AudioRecord.
        // This ensures the submix routing is actively bound even if audio starts AFTER capture.
        startKeepAliveTrack()
        registerPlaybackCallback()

        // Step 3: Configure AudioPlaybackCaptureConfiguration
        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) {
            handleError("Failed to build AudioPlaybackCaptureConfiguration: ${e.message}")
            return
        }

        // Step 4: Configure AudioFormat & AudioRecord (24 kHz, 16-bit Mono)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, FRAME_SAMPLES * 2 * 8)

        try {
            recorder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: SecurityException) {
            handleError("SecurityException creating AudioRecord: ${e.message}. Ensure RECORD_AUDIO permission is granted.")
            return
        } catch (e: Exception) {
            handleError("Failed to create AudioRecord: ${e.message}")
            return
        }

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            handleError("AudioRecord failed to initialize (state != STATE_INITIALIZED). The source application policy or device OS may prohibit capture.")
            return
        }

        // Step 5: Initialize UDP Network Socket
        socket = try {
            DatagramSocket()
        } catch (_: Exception) {
            null
        }
        setTargetHosts(initialHosts)

        // Step 6: Start recording
        try {
            recorder?.startRecording()
            if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                handleError("AudioRecord could not transition to RECORDSTATE_RECORDING.")
                return
            }
        } catch (e: Exception) {
            handleError("Exception starting AudioRecord: ${e.message}")
            return
        }

        running = true
        val initialDetail = if (initialHosts.isNotEmpty()) {
            "Capture active & streaming to ${initialHosts.size} device(s). Play media in VLC / MX Player."
        } else {
            "Capture active. Select or add receiver devices to stream."
        }

        updateDiagnostics(
            currentSnapshot.copy(
                state = CaptureState.CAPTURE_ACTIVE,
                statusDetail = initialDetail,
                sessionStartTimeMs = System.currentTimeMillis()
            )
        )
        updateNotification(if (initialHosts.isNotEmpty()) "Streaming to ${initialHosts.size} receiver(s)" else "Waiting for playback...")

        // Step 7: Dedicated high-priority capture and network transmission thread
        captureThread = thread(name = "AudioCaptureStreamThread", priority = Thread.MAX_PRIORITY) {
            val shortBuffer = ShortArray(FRAME_SAMPLES)
            val pcmBytes = ByteArray(FRAME_SAMPLES * 2)
            val sessionId = System.currentTimeMillis()
            var sequenceNumber = 0L

            var frames = 0L
            var samples = 0L
            var bytes = 0L
            var nonSilentFramesCount = 0L
            var readErrorsCount = 0L
            var packetsSentCount = 0L
            var bytesSentCount = 0L
            var networkErrorsCount = 0L

            try {
                while (running) {
                    if (shouldRebindSubmix) {
                        shouldRebindSubmix = false
                        try {
                            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                recorder?.stop()
                            }
                            recorder?.startRecording()
                            Log.d(TAG, "Submix re-anchored successfully upon external playback start")
                        } catch (e: Exception) {
                            Log.w(TAG, "Submix re-anchor error: ${e.message}")
                        }
                    }

                    val readResult = recorder?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    val now = System.currentTimeMillis()

                    if (readResult == 0) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }

                    if (readResult > 0) {
                        frames++
                        samples += readResult
                        bytes += (readResult * 2)

                        // Analyze captured PCM samples
                        val analysis = AudioAnalyzer.analyze(shortBuffer, readResult)
                        val peak = analysis.peak
                        val peakPercent = analysis.peakPercent
                        val rmsDbfs = analysis.rmsDbfs
                        val isSilent = analysis.isSilent

                        if (!isSilent) {
                            nonSilentFramesCount++
                        }

                        // Convert short samples to little-endian byte array
                        var byteIdx = 0
                        for (i in 0 until readResult) {
                            val s = shortBuffer[i].toInt()
                            pcmBytes[byteIdx++] = (s and 0xFF).toByte()
                            pcmBytes[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                        }

                        // Broadcast raw PCM to connected iOS Safari / Web clients over WebSocket
                        webRelayServer?.broadcastAudio(pcmBytes, readResult * 2)

                        // Multi-unicast transmission to all active receiver targets (up to 5 devices)
                        if (socket != null && targetAddresses.isNotEmpty()) {
                            try {
                                val packetBytes = AudioPacket.encode(
                                    sessionId = sessionId,
                                    sequenceNumber = sequenceNumber++,
                                    timestampMs = now,
                                    sampleRate = SAMPLE_RATE,
                                    pcmBytes = pcmBytes,
                                    pcmLength = readResult * 2
                                )
                                for (addr in targetAddresses) {
                                    try {
                                        val datagram = DatagramPacket(packetBytes, packetBytes.size, addr, PORT)
                                        socket?.send(datagram)
                                        packetsSentCount++
                                        bytesSentCount += packetBytes.size
                                    } catch (_: Exception) {
                                        networkErrorsCount++
                                    }
                                }
                            } catch (_: Exception) {
                                networkErrorsCount++
                            }
                        }

                        val state = if (!isSilent) {
                            CaptureState.NON_SILENT_AUDIO
                        } else {
                            CaptureState.SAMPLES_RECEIVED
                        }

                        val activeCount = targetAddresses.size
                        val statusDetail = if (!isSilent) {
                            if (activeCount > 0) "Non-silent audio detected! Streaming to $activeCount device(s)"
                            else "Non-silent audio detected! (No active receiver)"
                        } else {
                            if (activeCount > 0) "Streaming to $activeCount device(s) (Silence)"
                            else "Receiving samples from capture API, but audio level is silent."
                        }

                        // Throttle UI updates to ~20 Hz
                        if (now - lastUiUpdateTimeMs >= 50L) {
                            lastUiUpdateTimeMs = now
                            val snapshot = currentSnapshot.copy(
                                state = state,
                                totalFrames = frames,
                                totalSamples = samples,
                                totalBytes = bytes,
                                nonSilentFrames = nonSilentFramesCount,
                                readErrors = readErrorsCount,
                                currentRmsDbfs = rmsDbfs,
                                peakAmplitude = peak,
                                peakPercent = peakPercent,
                                isSilent = isSilent,
                                lastReadSamples = readResult,
                                lastSampleTimeMs = now,
                                statusDetail = statusDetail,
                                packetsSent = packetsSentCount,
                                bytesSent = bytesSentCount,
                                networkErrors = networkErrorsCount
                            )
                            updateDiagnostics(snapshot)
                        }
                    } else if (readResult < 0) {
                        readErrorsCount++
                        val errorName = when (readResult) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE (-2)"
                            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT (-6): Audio server disconnected"
                            else -> "AudioRecord read error ($readResult)"
                        }

                        if (now - lastUiUpdateTimeMs >= 100L) {
                            lastUiUpdateTimeMs = now
                            updateDiagnostics(
                                currentSnapshot.copy(
                                    readErrors = readErrorsCount,
                                    errorMessage = errorName,
                                    statusDetail = "Audio read error encountered: $errorName"
                                )
                            )
                        }

                        if (readResult == AudioRecord.ERROR_DEAD_OBJECT) {
                            handleError("Audio server died ($errorName). Stopping capture.")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                handleError("Exception in capture loop: ${e.message}")
            } finally {
                val finalSnapshot = currentSnapshot.copy(
                    totalFrames = frames,
                    totalSamples = samples,
                    totalBytes = bytes,
                    nonSilentFrames = nonSilentFramesCount,
                    readErrors = readErrorsCount,
                    packetsSent = packetsSentCount,
                    bytesSent = bytesSentCount,
                    networkErrors = networkErrorsCount
                )
                updateDiagnostics(finalSnapshot)
            }
        }
    }

    private fun updateDiagnostics(snapshot: CaptureDiagnostics) {
        currentSnapshot = snapshot
        mainHandler.post {
            diagnosticListener?.invoke(snapshot)
        }
    }

    private fun handleRevocation(reason: String) {
        stopCaptureSession(reason)
        updateDiagnostics(
            currentSnapshot.copy(
                state = CaptureState.REVOKED,
                statusDetail = reason,
                errorMessage = reason
            )
        )
        updateNotification("Session Revoked")
        stopSelf()
    }

    private fun handleError(message: String) {
        stopCaptureSession(message)
        updateDiagnostics(
            currentSnapshot.copy(
                state = CaptureState.ERROR,
                statusDetail = message,
                errorMessage = message
            )
        )
        updateNotification("Capture Error")
        stopSelf()
    }

    private fun stopCaptureSession(reason: String) {
        running = false
        targetAddresses.clear()
        captureThread?.interrupt()
        captureThread = null

        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder?.stop()
            }
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Exception) {}
        projectionCallback = null

        try {
            projection?.stop()
        } catch (_: Exception) {}
        projection = null

        stopKeepAliveTrack()
        unregisterPlaybackCallback()
    }

    /**
     * Spins up a silent keep-alive AudioTrack matching AudioAttributes.USAGE_MEDIA.
     *
     * Problem Solved:
     * Android's AudioPlaybackCapture API (AudioPolicyManager / AudioFlinger) creates an audio submix.
     * If AudioRecord starts recording when NO app is playing audio, the remote submix is initialized
     * in a dormant/unrouted state on many devices (OnePlus, Samsung, Xiaomi, Pixel).
     * Subsequent playback started after capture will NOT route to the AudioRecord unless a USAGE_MEDIA
     * stream was already active at capture initialization.
     *
     * By looping a silent (zero-amplitude) static PCM buffer with USAGE_MEDIA and CONTENT_TYPE_MUSIC,
     * the system media submix is kept warm and actively routed from session start.
     * Any media started before OR after audio sharing begins is immediately captured and streamed seamlessly.
     */
    private fun startKeepAliveTrack() {
        try {
            stopKeepAliveTrack()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Allocate a generous 1-second buffer (48,000 bytes = 24,000 frames) to satisfy HAL loop requirements
            val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val zeroPcm = ByteArray(bufSize)
            track.write(zeroPcm, 0, zeroPcm.size)
            val frameCount = bufSize / 2
            val loopResult = track.setLoopPoints(0, frameCount, -1)
            track.play()
            keepAliveTrack = track
            Log.d(TAG, "AudioPolicy keep-alive track started ($bufSize bytes, loopResult=$loopResult)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start keep-alive AudioTrack: ${e.message}")
        }
    }

    private fun stopKeepAliveTrack() {
        try {
            if (keepAliveTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                keepAliveTrack?.stop()
            }
        } catch (_: Exception) {}
        try {
            keepAliveTrack?.release()
        } catch (_: Exception) {}
        keepAliveTrack = null
    }

    private fun registerPlaybackCallback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        val hasMediaPlayback = configs?.any {
                            val usage = it.audioAttributes?.usage ?: AudioAttributes.USAGE_UNKNOWN
                            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
                        } == true
                        if (hasMediaPlayback && currentSnapshot.isSilent) {
                            Log.d(TAG, "Media playback detected while capture was silent; triggering submix re-anchor")
                            shouldRebindSubmix = true
                            if (keepAliveTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                startKeepAliveTrack()
                            }
                        }
                    }
                }
                audioManager.registerAudioPlaybackCallback(playbackCallback!!, mainHandler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register AudioPlaybackCallback: ${e.message}")
        }
    }

    private fun unregisterPlaybackCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playbackCallback?.let {
                    (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.unregisterAudioPlaybackCallback(it)
                }
            }
        } catch (_: Exception) {}
        playbackCallback = null
    }

    fun stopByUser() {
        stopCaptureSession("Capture stopped by user")
        updateDiagnostics(
            currentSnapshot.copy(
                state = CaptureState.STOPPED,
                statusDetail = "Capture stopped by user.",
                currentRmsDbfs = -100f,
                peakAmplitude = 0,
                peakPercent = 0,
                isSilent = true
            )
        )
        updateNotification("Capture stopped • Tap Start to resume")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Resonance Sender",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ongoing foreground service and controls for audio capture and streaming"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, ResonanceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_MODE", "SENDER")
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(if (running) "Resonance — Sender (Streaming)" else "Resonance — Sender (Stopped)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(running)
            .setContentIntent(openPendingIntent)

        if (running) {
            val stopIntent = Intent(this, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                11,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Stop",
                stopPendingIntent
            ).build()
            builder.addAction(stopAction)

            val openAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                "Open App",
                openPendingIntent
            ).build()
            builder.addAction(openAction)
        } else {
            val startAppIntent = Intent(this, ResonanceActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_MODE", "SENDER")
                putExtra("EXTRA_START_SENDER", true)
            }
            val startPendingIntent = PendingIntent.getActivity(
                this,
                12,
                startAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val startAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Start",
                startPendingIntent
            ).build()
            builder.addAction(startAction)

            val dismissIntent = Intent(this, CaptureService::class.java).apply {
                action = ACTION_DISMISS
            }
            val dismissPendingIntent = PendingIntent.getService(
                this,
                13,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dismissAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Dismiss",
                dismissPendingIntent
            ).build()
            builder.addAction(dismissAction)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        webRelayServer?.stop()
        webRelayServer = null
        stopCaptureSession("Capture service destroyed")
        super.onDestroy()
    }
}
