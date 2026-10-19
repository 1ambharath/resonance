package com.br.resonance

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed audio packet received over local UDP.
 * Validates protocol header and unpacks 16-bit PCM payload.
 */
data class ReceivedAudioPacket(
    val version: Byte,
    val sessionId: Long,
    val sequenceNumber: Long,
    val timestampMs: Long,
    val sampleRate: Int,
    val channelCount: Short,
    val format: Short,
    val payload: ByteArray,
    val arrivalTimeMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val MAGIC_0: Byte = 'D'.code.toByte()
        const val MAGIC_1: Byte = 'A'.code.toByte()
        const val HEADER_SIZE = 40

        fun decode(data: ByteArray, length: Int): ReceivedAudioPacket? {
            if (length < HEADER_SIZE) {
                // Support legacy raw PCM (e.g. exactly 960 bytes)
                if (length == 960) {
                    val raw = ByteArray(960)
                    System.arraycopy(data, 0, raw, 0, 960)
                    return ReceivedAudioPacket(
                        version = 0,
                        sessionId = 0L,
                        sequenceNumber = 0L,
                        timestampMs = System.currentTimeMillis(),
                        sampleRate = 24000,
                        channelCount = 1,
                        format = 1,
                        payload = raw
                    )
                }
                return null
            }

            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val m0 = buffer.get()
            val m1 = buffer.get()
            if (m0 != MAGIC_0 || m1 != MAGIC_1) {
                if (length == 960) {
                    val raw = ByteArray(960)
                    System.arraycopy(data, 0, raw, 0, 960)
                    return ReceivedAudioPacket(
                        version = 0,
                        sessionId = 0L,
                        sequenceNumber = 0L,
                        timestampMs = System.currentTimeMillis(),
                        sampleRate = 24000,
                        channelCount = 1,
                        format = 1,
                        payload = raw
                    )
                }
                return null
            }

            val version = buffer.get()
            buffer.get() // flags
            val sessionId = buffer.getLong()
            val sequenceNumber = buffer.getLong()
            val timestampMs = buffer.getLong()
            val sampleRate = buffer.getInt()
            val channelCount = buffer.getShort()
            val format = buffer.getShort()
            val payloadLength = buffer.getInt()

            if (payloadLength <= 0 || payloadLength > (length - HEADER_SIZE)) {
                return null
            }

            val payload = ByteArray(payloadLength)
            buffer.get(payload, 0, payloadLength)

            return ReceivedAudioPacket(
                version = version,
                sessionId = sessionId,
                sequenceNumber = sequenceNumber,
                timestampMs = timestampMs,
                sampleRate = sampleRate,
                channelCount = channelCount,
                format = format,
                payload = payload
            )
        }
    }
}
