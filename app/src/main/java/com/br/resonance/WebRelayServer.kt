package com.br.resonance

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Embedded Zero-Install HTTP & WebSocket Relay Server.
 * Serves the web-based Resonance receiver to iOS Safari (and companion desktop browsers)
 * and streams low-latency 24kHz PCM audio frames over local WebSockets.
 */
class WebRelayServer(
    private val context: Context,
    val port: Int = 8080
) {
    companion object {
        private const val TAG = "WebRelayServer"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()

    private val wsClients = CopyOnWriteArrayList<WebSocketClient>()

    var onClientCountChanged: ((Int) -> Unit)? = null
    var onAudioReceivedFromClient: ((ByteArray, Int) -> Unit)? = null

    val connectedClientCount: Int
        get() = wsClients.size

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "WebRelayHttpServerThread") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Log.i(TAG, "WebRelayServer listening on port $port")

                while (isRunning && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 0 // Keep-alive for websockets
                        threadPool.execute {
                            handleConnection(client)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error on port $port", e)
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning && serverSocket == null) return
        isRunning = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (client in wsClients) {
            client.close()
        }
        wsClients.clear()
        onClientCountChanged?.invoke(0)
        Log.i(TAG, "WebRelayServer stopped")
    }

    /**
     * Broadcasts raw 16-bit PCM audio bytes to all active iOS / Web receiver sessions.
     */
    fun broadcastAudio(pcmBytes: ByteArray, length: Int) {
        if (!isRunning || wsClients.isEmpty() || length <= 0) return

        val deadClients = mutableListOf<WebSocketClient>()
        for (client in wsClients) {
            val ok = client.sendBinary(pcmBytes, length)
            if (!ok) {
                deadClients.add(client)
            }
        }

        if (deadClients.isNotEmpty()) {
            wsClients.removeAll(deadClients)
            onClientCountChanged?.invoke(wsClients.size)
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return socket.close()
            val parts = requestLine.split(" ")
            if (parts.size < 2) return socket.close()

            val method = parts[0]
            val path = parts[1].split("?")[0]

            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
                line = reader.readLine()
            }

            // Check for WebSocket upgrade
            val isUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            if (isUpgrade) {
                handleWebSocketHandshake(socket, headers, output, input)
                return
            }

            // Standard HTTP file serving
            when (path) {
                "/", "/index.html" -> serveAsset(output, "web/index.html", "text/html; charset=utf-8")
                "/style.css" -> serveAsset(output, "web/style.css", "text/css; charset=utf-8")
                "/app.js" -> serveAsset(output, "web/app.js", "application/javascript; charset=utf-8")
                "/api/status" -> {
                    val json = "{\"status\":\"active\",\"sampleRate\":24000,\"channels\":1,\"clients\":${wsClients.size}}"
                    sendHttpResponse(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
                }
                else -> {
                    sendHttpResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
            socket.close()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveAsset(output: OutputStream, assetPath: String, contentType: String) {
        try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            sendHttpResponse(output, 200, "OK", contentType, bytes)
        } catch (_: Exception) {
            sendHttpResponse(output, 404, "Not Found", "text/plain", "Asset not found".toByteArray())
        }
    }

    private fun sendHttpResponse(output: OutputStream, code: Int, statusText: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun handleWebSocketHandshake(
        socket: Socket,
        headers: Map<String, String>,
        output: OutputStream,
        input: InputStream
    ) {
        val key = headers["sec-websocket-key"] ?: return socket.close()
        val acceptKey = computeWebSocketAccept(key)

        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

        output.write(handshake.toByteArray(Charsets.UTF_8))
        output.flush()

        val wsClient = WebSocketClient(socket, output, input)
        wsClients.add(wsClient)
        onClientCountChanged?.invoke(wsClients.size)

        // Read incoming messages loop (e.g. ping/pong, client audio)
        try {
            wsClient.readLoop { clientData, length ->
                onAudioReceivedFromClient?.invoke(clientData, length)
            }
        } finally {
            wsClients.remove(wsClient)
            wsClient.close()
            onClientCountChanged?.invoke(wsClients.size)
        }
    }

    private fun computeWebSocketAccept(key: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val input = (key.trim() + WS_GUID).toByteArray(Charsets.UTF_8)
        val hash = md.digest(input)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    inner class WebSocketClient(
        private val socket: Socket,
        private val output: OutputStream,
        private val input: InputStream
    ) {
        private val bufferedOut = BufferedOutputStream(output, 16384)
        private val lock = Any()

        fun sendBinary(data: ByteArray, length: Int): Boolean {
            return synchronized(lock) {
                try {
                    if (socket.isClosed || !socket.isConnected) return false

                    // RFC 6455 Frame Header: FIN (0x80) | Opcode Binary (0x02) = 0x82
                    bufferedOut.write(0x82)

                    if (length <= 125) {
                        bufferedOut.write(length)
                    } else if (length <= 65535) {
                        bufferedOut.write(126)
                        bufferedOut.write((length shr 8) and 0xFF)
                        bufferedOut.write(length and 0xFF)
                    } else {
                        bufferedOut.write(127)
                        for (i in 7 downTo 0) {
                            bufferedOut.write(((length.toLong() shr (i * 8)) and 0xFF).toInt())
                        }
                    }

                    bufferedOut.write(data, 0, length)
                    bufferedOut.flush()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun readLoop(onData: (ByteArray, Int) -> Unit) {
            try {
                while (isRunning && !socket.isClosed) {
                    val b0 = input.read()
                    if (b0 == -1) break
                    val opcode = b0 and 0x0F

                    val b1 = input.read()
                    if (b1 == -1) break
                    val isMasked = (b1 and 0x80) != 0
                    var payloadLen = (b1 and 0x7F).toLong()

                    if (payloadLen == 126L) {
                        val p1 = input.read()
                        val p2 = input.read()
                        if (p1 == -1 || p2 == -1) break
                        payloadLen = ((p1 and 0xFF) shl 8 or (p2 and 0xFF)).toLong()
                    } else if (payloadLen == 127L) {
                        var len = 0L
                        for (i in 0 until 8) {
                            val p = input.read()
                            if (p == -1) return
                            len = (len shl 8) or (p.toLong() and 0xFF)
                        }
                        payloadLen = len
                    }

                    val mask = ByteArray(4)
                    if (isMasked) {
                        var readMask = 0
                        while (readMask < 4) {
                            val r = input.read(mask, readMask, 4 - readMask)
                            if (r == -1) return
                            readMask += r
                        }
                    }

                    if (payloadLen > 1024 * 1024) break // Safety cap 1MB

                    val payload = ByteArray(payloadLen.toInt())
                    var totalRead = 0
                    while (totalRead < payload.size) {
                        val r = input.read(payload, totalRead, payload.size - totalRead)
                        if (r == -1) return
                        totalRead += r
                    }

                    if (isMasked) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        0x08 -> break // Close frame
                        0x09 -> { // Ping -> Pong
                            synchronized(lock) {
                                bufferedOut.write(0x8A) // Pong opcode
                                bufferedOut.write(payload.size)
                                bufferedOut.write(payload)
                                bufferedOut.flush()
                            }
                        }
                        0x02 -> { // Binary payload (incoming audio from iOS in Sender mode)
                            onData(payload, payload.size)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
