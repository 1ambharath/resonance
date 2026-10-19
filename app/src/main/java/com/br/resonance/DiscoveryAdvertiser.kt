package com.br.resonance

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * DiscoveryAdvertiser:
 * Dual discovery advertising for Dual Audio Receiver:
 * 1. Standard Android Network Service Discovery (mDNS / DNS-SD _dualaudio._udp.)
 * 2. Lightweight UDP broadcast beacon on port 50006 (works instantly on all routers/hotspots)
 */
class DiscoveryAdvertiser(private val context: Context, private val playoutPort: Int = 50005) {

    companion object {
        private const val TAG = "DiscoveryAdvertiser"
        const val DISCOVERY_PORT = 50006
        const val SERVICE_TYPE = "_dualaudio._udp."
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var running = false
    private var beaconSocket: DatagramSocket? = null
    private var beaconThread: Thread? = null

    val deviceName: String = try {
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    } catch (_: Exception) {
        "Android Receiver"
    }

    fun start() {
        if (running) return
        running = true

        acquireMulticastLock()
        startNsdRegistration()
        startUdpBeacon()
    }

    fun stop() {
        running = false
        stopNsdRegistration()
        stopUdpBeacon()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("DualAudioReceiverBeacon")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun startNsdRegistration() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "DualAudio-${deviceName.replace(" ", "_")}"
                serviceType = SERVICE_TYPE
                port = playoutPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "NSD service registered: ${serviceInfo?.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "NSD registration failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.i(TAG, "NSD service unregistered")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD register exception: ${e.message}")
        }
    }

    private fun stopNsdRegistration() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (_: Exception) {}
        registrationListener = null
        nsdManager = null
    }

    private fun startUdpBeacon() {
        beaconThread = thread(name = "ReceiverBeaconThread") {
            try {
                beaconSocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 1500
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind discovery socket on port $DISCOVERY_PORT: ${e.message}")
                return@thread
            }

            val buffer = ByteArray(1024)
            var lastAnnounceMs = 0L

            while (running) {
                val now = System.currentTimeMillis()

                // 1. Periodically broadcast presence beacon (every 2.5 seconds)
                if (now - lastAnnounceMs >= 2500L) {
                    lastAnnounceMs = now
                    try {
                        val announceMsg = "DUAL_AUDIO_ANNOUNCE:name=$deviceName:port=$playoutPort"
                        val bytes = announceMsg.toByteArray(StandardCharsets.UTF_8)
                        val bcastAddr = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(bytes, bytes.size, bcastAddr, DISCOVERY_PORT)
                        beaconSocket?.send(packet)
                    } catch (_: Exception) {}
                }

                // 2. Listen for discovery ping from Senders
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    beaconSocket?.receive(packet)

                    val msg = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8).trim()
                    if (msg.startsWith("DUAL_AUDIO_PING")) {
                        // Reply directly to sender with PONG
                        val pongMsg = "DUAL_AUDIO_PONG:name=$deviceName:port=$playoutPort"
                        val replyBytes = pongMsg.toByteArray(StandardCharsets.UTF_8)
                        val replyPacket = DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port)
                        beaconSocket?.send(replyPacket)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout for heartbeat loop
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

    private fun stopUdpBeacon() {
        try {
            beaconSocket?.close()
        } catch (_: Exception) {}
        try {
            beaconThread?.interrupt()
        } catch (_: Exception) {}
        beaconSocket = null
        beaconThread = null
    }
}
