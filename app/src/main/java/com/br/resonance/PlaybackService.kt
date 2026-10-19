package com.br.resonance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * PlaybackService: Dedicated Foreground Service for Receiver Playback.
 *
 * Ensures uninterrupted audio playback when the phone screen turns off:
 * - Declared with FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK (Android 14+ compatible)
 * - Holds PARTIAL_WAKE_LOCK to prevent CPU deep-sleep
 * - Holds WIFI_MODE_FULL_HIGH_PERF to keep Wi-Fi radio active without power-save packet drop
 * - Manages UDP socket reception, JitterDelayBuffer, and low-latency AudioTrack output
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.br.resonance.START_PLAYBACK"
        const val ACTION_STOP = "com.br.resonance.STOP_PLAYBACK"
        const val ACTION_DISMISS = "com.br.resonance.DISMISS_PLAYBACK"

        const val PORT = 50005
        const val SAMPLE_RATE = 24000
        const val NOTIFICATION_ID = 201
        private const val CHANNEL_ID = "dual_audio_playback_v2"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jitterBuffer = JitterDelayBuffer(baseCushionMs = 80)

    @Volatile private var running = false
    @Volatile private var offsetMs = 0

    private var socket: DatagramSocket? = null
    private var networkThread: Thread? = null
    private var playbackThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var discoveryAdvertiser: DiscoveryAdvertiser? = null

    @Volatile private var currentSnapshot = PlaybackDiagnostics()
    private var diagnosticListener: ((PlaybackDiagnostics) -> Unit)? = null
    private var lastUiUpdateTimeMs = 0L

    override fun onBind(intent: Intent?): IBinder = binder

    fun setDiagnosticListener(listener: ((PlaybackDiagnostics) -> Unit)?) {
        this.diagnosticListener = listener
        listener?.invoke(currentSnapshot)
    }

    fun getOffsetMs(): Int = offsetMs

    fun setOffsetMs(offset: Int) {
        this.offsetMs = offset
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_DISMISS) {
            stopPlayback(removeNotification = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            stopPlayback(removeNotification = false)
            return START_NOT_STICKY
        }

        startPlayback()
        return START_NOT_STICKY
    }

    private fun startPlayback() {
        if (running) return
        running = true

        // Step 1: Promote to foreground service first
        createNotificationChannel()
        val notification = buildNotification("Listening for incoming audio...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            updateDiagnostics(
                currentSnapshot.copy(
                    isRunning = false,
                    statusMessage = "Foreground service error: ${e.message}",
                    errorMessage = e.message
                )
            )
            stopSelf()
            return
        }

        // Step 2: Acquire WakeLock and WifiLock for screen-off resilience
        acquireLocks()

        // Step 2b: Start Discovery Advertiser so Senders can automatically detect this device
        try {
            discoveryAdvertiser = DiscoveryAdvertiser(this, PORT).apply { start() }
        } catch (_: Exception) {}

        jitterBuffer.clear()
        var totalPackets = 0L
        var totalBytes = 0L

        updateDiagnostics(
            currentSnapshot.copy(
                isRunning = true,
                statusMessage = "Listening on UDP port $PORT (Background active)",
                trackState = "INITIALIZING",
                errorMessage = null
            )
        )

        // Step 3: UDP Network Receiver Thread
        networkThread = thread(name = "ReceiverUdpThread", priority = Thread.MAX_PRIORITY) {
            try {
                socket = DatagramSocket(PORT)
                val buffer = ByteArray(1500)

                while (running) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket?.receive(datagram)

                    if (datagram.length > 0) {
                        val packet = ReceivedAudioPacket.decode(datagram.data, datagram.length)
                        if (packet != null) {
                            totalPackets++
                            totalBytes += packet.payload.size
                            jitterBuffer.push(packet, offsetMs)

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateTimeMs >= 50L) {
                                lastUiUpdateTimeMs = now
                                val analysis = AudioAnalyzer.analyze(packet.payload, packet.payload.size)
                                updateDiagnostics(
                                    currentSnapshot.copy(
                                        isRunning = true,
                                        totalPackets = totalPackets,
                                        totalBytes = totalBytes,
                                        bufferDepthMs = jitterBuffer.getBufferedDepthMs(),
                                        queuedFrames = jitterBuffer.getQueuedFrameCount(),
                                        droppedPackets = jitterBuffer.droppedPackets,
                                        underrunCount = jitterBuffer.underrunCount,
                                        peakPercent = analysis.peakPercent,
                                        rmsDbfs = analysis.rmsDbfs,
                                        isSilent = analysis.isSilent,
                                        statusMessage = if (!analysis.isSilent) "Playing audio through headset" else "Stream active (silent audio)"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    updateDiagnostics(
                        currentSnapshot.copy(
                            statusMessage = "UDP Socket error: ${e.message}",
                            errorMessage = e.message
                        )
                    )
                }
            }
        }

        // Step 4: AudioTrack Playback Thread
        playbackThread = thread(name = "ReceiverAudioTrackThread", priority = Thread.MAX_PRIORITY) {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBufferSize = maxOf(minBufferSize * 2, 480 * 2 * 6)

            val track = try {
                AudioTrack.Builder()
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
                    .setBufferSizeInBytes(trackBufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build().also {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            try {
                                val targetHardwareFrames = 480 * 4 // ~80 ms active queue headroom
                                val minFrames = minBufferSize / 2
                                it.bufferSizeInFrames = maxOf(minFrames, targetHardwareFrames)
                            } catch (_: Exception) {}
                        }
                    }
            } catch (e: Exception) {
                updateDiagnostics(
                    currentSnapshot.copy(
                        trackState = "ERROR",
                        statusMessage = "AudioTrack creation error: ${e.message}",
                        errorMessage = e.message
                    )
                )
                return@thread
            }

            try {
                track.play()
                updateDiagnostics(currentSnapshot.copy(trackState = "PLAYING (SMOOTH)"))

                while (running) {
                    val readyPcm = jitterBuffer.pollBlocking(30L)
                    if (readyPcm != null && readyPcm.isNotEmpty()) {
                        track.write(readyPcm, 0, readyPcm.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.stop()
                } catch (_: Exception) {}
                try {
                    track.release()
                } catch (_: Exception) {}
                updateDiagnostics(currentSnapshot.copy(trackState = "STOPPED"))
            }
        }
    }

    fun stopPlayback(removeNotification: Boolean = false) {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        networkThread?.interrupt()
        networkThread = null

        playbackThread?.interrupt()
        playbackThread = null

        jitterBuffer.clear()
        releaseLocks()

        try {
            discoveryAdvertiser?.stop()
        } catch (_: Exception) {}
        discoveryAdvertiser = null

        updateDiagnostics(
            PlaybackDiagnostics(
                isRunning = false,
                statusMessage = "Receiver stopped.",
                trackState = "STOPPED"
            )
        )

        if (removeNotification) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
        } else {
            updateNotification("Receiver stopped • Tap Start to listen")
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualAudioReceiver::PlaybackWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24h max safety timeout
                }
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wifiManager != null) {
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DualAudioReceiver::PlaybackWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null

            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun updateDiagnostics(snapshot: PlaybackDiagnostics) {
        currentSnapshot = snapshot
        mainHandler.post {
            diagnosticListener?.invoke(snapshot)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Resonance Receiver",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Foreground playback notification and controls for audio streaming"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, ResonanceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_MODE", "RECEIVER")
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            20,
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
            .setContentTitle(if (running) "Resonance — Receiver (Listening)" else "Resonance — Receiver (Stopped)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(running)
            .setContentIntent(openAppPendingIntent)

        if (running) {
            val stopIntent = Intent(this, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                21,
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
                openAppPendingIntent
            ).build()
            builder.addAction(openAction)
        } else {
            val startIntent = Intent(this, PlaybackService::class.java).apply {
                action = ACTION_START
            }
            val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    this,
                    22,
                    startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    this,
                    22,
                    startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val startAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Start",
                startPendingIntent
            ).build()
            builder.addAction(startAction)

            val dismissIntent = Intent(this, PlaybackService::class.java).apply {
                action = ACTION_DISMISS
            }
            val dismissPendingIntent = PendingIntent.getService(
                this,
                23,
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

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onDestroy() {
        stopPlayback(removeNotification = true)
        super.onDestroy()
    }
}
