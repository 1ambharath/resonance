package com.br.resonance

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * RemoteReceiver represents a detected or manually added receiver endpoint.
 */
data class RemoteReceiver(
    val ip: String,
    val name: String,
    val port: Int = 50005,
    var isSelected: Boolean = true,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var isManual: Boolean = false
) {
    val initials: String
        get() {
            val words = name.trim().split(" ").filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> "??"
                words.size == 1 -> words[0].take(2).uppercase()
                else -> "${words[0].first()}${words[1].first()}".uppercase()
            }
        }
}

/**
 * DeviceDiscoveryManager:
 * Discovers Dual Audio Receivers via:
 * 1. UDP Broadcast Beacon on port 50006 (works instantly on all routers/hotspots)
 * 2. Android Network Service Discovery (mDNS _dualaudio._udp.)
 * 3. Manual IP Fallback (persisted in SharedPreferences)
 */
class DeviceDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryManager"
        const val DISCOVERY_PORT = 50006
        const val SERVICE_TYPE = "_dualaudio._udp."
        const val MAX_SELECTED_DEVICES = 5
        private const val PREFS_NAME = "dual_audio_discovery_prefs"
        private const val KEY_SAVED_IPS = "saved_receiver_ips"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<String, RemoteReceiver>()
    private var listener: ((List<RemoteReceiver>) -> Unit)? = null

    private var running = false
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var beaconSocket: DatagramSocket? = null
    private var beaconThread: Thread? = null

    init {
        loadSavedManualIps()
    }

    fun setListener(listener: ((List<RemoteReceiver>) -> Unit)?) {
        this.listener = listener
        notifyListener()
    }

    fun getDevices(): List<RemoteReceiver> = devices.values.toList().sortedByDescending { it.isSelected }

    fun getSelectedIps(): List<String> {
        return devices.values
            .filter { it.isSelected }
            .take(MAX_SELECTED_DEVICES)
            .map { it.ip }
    }

    fun start() {
        if (running) return
        running = true

        acquireMulticastLock()
        startNsdDiscovery()
        startUdpBeaconListener()
        sendDiscoveryPing()
    }

    fun stop() {
        running = false
        stopNsdDiscovery()
        stopUdpBeaconListener()
        releaseMulticastLock()
    }

    fun addManualDevice(ip: String): Boolean {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank() || !isValidIp(cleanIp)) return false

        val existing = devices[cleanIp]
        val canSelect = devices.values.count { it.isSelected } < MAX_SELECTED_DEVICES

        devices[cleanIp] = RemoteReceiver(
            ip = cleanIp,
            name = existing?.name ?: "Receiver ($cleanIp)",
            port = 50005,
            isSelected = if (existing != null) existing.isSelected else canSelect,
            lastSeenMs = System.currentTimeMillis(),
            isManual = true
        )

        saveManualIps()
        notifyListener()
        sendDiscoveryPing()
        return true
    }

    fun removeManualDevice(ip: String) {
        val dev = devices[ip]
        if (dev != null && dev.isManual) {
            devices.remove(ip)
            saveManualIps()
            notifyListener()
        }
    }

    fun toggleDevice(ip: String) {
        val dev = devices[ip] ?: return
        if (!dev.isSelected) {
            val selectedCount = devices.values.count { it.isSelected }
            if (selectedCount >= MAX_SELECTED_DEVICES) {
                // Max 5 reached
                return
            }
            dev.isSelected = true
        } else {
            dev.isSelected = false
        }
        notifyListener()
    }

    private fun notifyListener() {
        mainHandler.post {
            listener?.invoke(getDevices())
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("DualAudioSenderDiscovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startNsdDiscovery() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "NSD discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.i(TAG, "NSD discovery started")
                }

                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo?.serviceType?.contains("dualaudio") == true) {
                        try {
                            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}

                                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                    val host = resolved?.host?.hostAddress ?: return
                                    val name = resolved.serviceName?.replace("DualAudio-", "")?.replace("_", " ") ?: "Receiver ($host)"
                                    registerDevice(host, name, resolved.port)
                                }
                            })
                        } catch (_: Exception) {}
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discovery exception: ${e.message}")
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (_: Exception) {}
        discoveryListener = null
        nsdManager = null
    }

    private fun startUdpBeaconListener() {
        beaconThread = thread(name = "SenderDiscoveryThread") {
            try {
                beaconSocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 2000
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind discovery socket on port $DISCOVERY_PORT: ${e.message}")
                return@thread
            }

            val buffer = ByteArray(1024)
            var lastPingMs = 0L

            while (running) {
                val now = System.currentTimeMillis()

                // Send ping every 3 seconds
                if (now - lastPingMs >= 3000L) {
                    lastPingMs = now
                    sendDiscoveryPingInternal()
                }

                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    beaconSocket?.receive(packet)

                    val senderIp = packet.address.hostAddress ?: continue
                    val msg = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8).trim()

                    if (msg.startsWith("DUAL_AUDIO_ANNOUNCE:") || msg.startsWith("DUAL_AUDIO_PONG:")) {
                        // Parse parameters
                        var name = "Receiver ($senderIp)"
                        var port = 50005

                        val parts = msg.substringAfter(":").split(":")
                        for (part in parts) {
                            if (part.startsWith("name=")) {
                                name = part.substringAfter("name=")
                            } else if (part.startsWith("port=")) {
                                port = part.substringAfter("port=").toIntOrNull() ?: 50005
                            }
                        }

                        registerDevice(senderIp, name, port)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (!running) break
                }
            }

            try {
                beaconSocket?.close()
            } catch (_: Exception) {}
            beaconSocket = null
        }
    }

    fun sendDiscoveryPing() {
        thread {
            sendDiscoveryPingInternal()
        }
    }

    private fun sendDiscoveryPingInternal() {
        try {
            val pingMsg = "DUAL_AUDIO_PING"
            val bytes = pingMsg.toByteArray(StandardCharsets.UTF_8)
            val bcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(bytes, bytes.size, bcastAddr, DISCOVERY_PORT)
            beaconSocket?.send(packet)
        } catch (_: Exception) {}
    }

    private fun registerDevice(ip: String, name: String, port: Int) {
        val existing = devices[ip]
        val canSelect = devices.values.count { it.isSelected } < MAX_SELECTED_DEVICES

        if (existing == null) {
            devices[ip] = RemoteReceiver(
                ip = ip,
                name = name,
                port = port,
                isSelected = canSelect,
                lastSeenMs = System.currentTimeMillis(),
                isManual = false
            )
            notifyListener()
        } else {
            existing.lastSeenMs = System.currentTimeMillis()
            if (existing.name != name) {
                devices[ip] = existing.copy(name = name)
                notifyListener()
            }
        }
    }

    private fun stopUdpBeaconListener() {
        try {
            beaconSocket?.close()
        } catch (_: Exception) {}
        try {
            beaconThread?.interrupt()
        } catch (_: Exception) {}
        beaconSocket = null
        beaconThread = null
    }

    private fun saveManualIps() {
        val manualIps = devices.values.filter { it.isManual }.map { "${it.ip}|${it.name}" }.toSet()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SAVED_IPS, manualIps)
            .apply()
    }

    private fun loadSavedManualIps() {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_SAVED_IPS, emptySet()) ?: emptySet()

        for (item in saved) {
            val parts = item.split("|")
            val ip = parts.getOrNull(0) ?: continue
            val name = parts.getOrNull(1) ?: "Receiver ($ip)"
            val canSelect = devices.values.count { it.isSelected } < MAX_SELECTED_DEVICES

            devices[ip] = RemoteReceiver(
                ip = ip,
                name = name,
                port = 50005,
                isSelected = canSelect,
                lastSeenMs = System.currentTimeMillis(),
                isManual = true
            )
        }
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }
}
