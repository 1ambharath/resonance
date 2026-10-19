package com.br.resonance

/**
 * Thread-safe jitter buffer and smooth playout scheduler with 1-millisecond fine-tuning precision.
 *
 * Implements byte-level linear PCM FIFO:
 * - 24,000 Hz, 16-bit Mono = 48 bytes per millisecond (24 samples/ms).
 * - Delivers exact 1 ms resolution for fine-tuning sync between headsets.
 * - Trims or accumulates exact millisecond byte deltas instantly without audible artifacts.
 * - Protects against burst drops while maintaining tight latency control.
 */
class JitterDelayBuffer(
    val baseCushionMs: Int = 80 // 80 ms baseline stability cushion
) {
    companion object {
        const val SAMPLE_RATE = 24000
        const val BYTES_PER_MS = 48 // 24 kHz * 2 bytes / 1000 ms
        const val FRAME_BYTES = 960 // 20 ms frame
        const val MIN_TARGET_MS = 20 // 20 ms minimum safe hardware cushion
        const val RING_CAPACITY = BYTES_PER_MS * 2000 // 2000 ms capacity (96 KB)
    }

    private val lock = Object()
    private val pcmBuffer = ByteArray(RING_CAPACITY)
    private var bufferSize = 0

    @Volatile var droppedPackets: Long = 0L
        private set

    @Volatile var totalReceived: Long = 0L
        private set

    @Volatile var underrunCount: Long = 0L
        private set

    @Volatile var isPreRolling = true
        private set

    var currentTargetMs: Int = baseCushionMs
        private set

    private var consecutiveTimeouts = 0

    fun push(packet: ReceivedAudioPacket, offsetMs: Int) {
        synchronized(lock) {
            val newTargetMs = (baseCushionMs + offsetMs).coerceAtLeast(MIN_TARGET_MS)

            // Dynamic 1-millisecond delay adjustments
            if (newTargetMs > currentTargetMs) {
                // Target increased: buffer additional audio
                currentTargetMs = newTargetMs
                isPreRolling = true
            } else if (newTargetMs < currentTargetMs) {
                // Target decreased (negative delay): trim exact millisecond byte delta immediately
                val diffMs = currentTargetMs - newTargetMs
                val trimBytes = (diffMs * BYTES_PER_MS).coerceAtMost(bufferSize)
                if (trimBytes > 0) {
                    val remainingBytes = bufferSize - trimBytes
                    System.arraycopy(pcmBuffer, trimBytes, pcmBuffer, 0, remainingBytes)
                    bufferSize = remainingBytes
                    droppedPackets += (trimBytes / FRAME_BYTES)
                }
                currentTargetMs = newTargetMs
            }

            val maxAllowedBytes = (currentTargetMs + 100) * BYTES_PER_MS // +100 ms burst headroom

            // Append new packet payload
            val incomingSize = packet.payload.size
            if (bufferSize + incomingSize <= RING_CAPACITY) {
                System.arraycopy(packet.payload, 0, pcmBuffer, bufferSize, incomingSize)
                bufferSize += incomingSize
                totalReceived++
            }

            // Drop excess oldest bytes if burst capacity exceeded
            if (bufferSize > maxAllowedBytes) {
                val excess = bufferSize - maxAllowedBytes
                val remainingBytes = bufferSize - excess
                System.arraycopy(pcmBuffer, excess, pcmBuffer, 0, remainingBytes)
                bufferSize = remainingBytes
                droppedPackets++
            }

            // When target cushion is buffered, start smooth playout
            val targetBytes = currentTargetMs * BYTES_PER_MS
            if (isPreRolling && bufferSize >= targetBytes) {
                isPreRolling = false
            }

            lock.notifyAll()
        }
    }

    /**
     * Polls the next 20 ms frame (960 bytes) for AudioTrack playback.
     */
    fun pollBlocking(timeoutMs: Long): ByteArray? {
        val start = System.currentTimeMillis()
        synchronized(lock) {
            while (true) {
                if (!isPreRolling && bufferSize >= FRAME_BYTES) {
                    consecutiveTimeouts = 0
                    val out = ByteArray(FRAME_BYTES)
                    System.arraycopy(pcmBuffer, 0, out, 0, FRAME_BYTES)
                    val remaining = bufferSize - FRAME_BYTES
                    System.arraycopy(pcmBuffer, FRAME_BYTES, pcmBuffer, 0, remaining)
                    bufferSize = remaining
                    return out
                }

                val elapsed = System.currentTimeMillis() - start
                val remaining = timeoutMs - elapsed
                if (remaining <= 0) {
                    if (!isPreRolling) {
                        consecutiveTimeouts++
                        underrunCount++
                        if (consecutiveTimeouts >= 5) {
                            isPreRolling = true
                        }
                    }
                    return null
                }

                try {
                    lock.wait(minOf(remaining, 20L))
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
    }

    fun getBufferedDepthMs(frameDurationMs: Int = 20): Int {
        synchronized(lock) {
            return bufferSize / BYTES_PER_MS
        }
    }

    fun getQueuedFrameCount(): Int {
        synchronized(lock) {
            return bufferSize / FRAME_BYTES
        }
    }

    fun clear() {
        synchronized(lock) {
            bufferSize = 0
            consecutiveTimeouts = 0
            currentTargetMs = baseCushionMs
            isPreRolling = true
            lock.notifyAll()
        }
    }
}
