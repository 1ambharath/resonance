package com.br.resonance

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned audio packet encoder for Dual Audio Sync.
 *
 * Header format (40 bytes, Little Endian):
 * - Bytes 0..1   : Magic bytes 'D', 'A' (0x44, 0x41)
 * - Byte 2       : Protocol version (1)
 * - Byte 3       : Flags (0)
 * - Bytes 4..11  : Session ID (Long)
 * - Bytes 12..19 : Sequence Number (Long, monotonically increasing)
 * - Bytes 20..27 : Capture Timestamp (Long, milliseconds)
 * - Bytes 28..31 : Sample Rate (Int, 24000)
 * - Bytes 32..33 : Channel count (Short, 1 = mono)
 * - Bytes 34..35 : Audio format (Short, 1 = 16-bit PCM)
 * - Bytes 36..39 : Payload length in bytes (Int, 960)
 * Followed by raw 16-bit PCM payload.
 */
object AudioPacket {
    const val MAGIC_0: Byte = 'D'.code.toByte()
    const val MAGIC_1: Byte = 'A'.code.toByte()
    const val PROTOCOL_VERSION: Byte = 1
    const val HEADER_SIZE = 40

    fun encode(
        sessionId: Long,
        sequenceNumber: Long,
        timestampMs: Long,
        sampleRate: Int,
        pcmBytes: ByteArray,
        pcmLength: Int
    ): ByteArray {
        val totalSize = HEADER_SIZE + pcmLength
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(0.toByte()) // flags
        buffer.putLong(sessionId)
        buffer.putLong(sequenceNumber)
        buffer.putLong(timestampMs)
        buffer.putInt(sampleRate)
        buffer.putShort(1.toShort()) // mono
        buffer.putShort(1.toShort()) // 16-bit PCM
        buffer.putInt(pcmLength)
        buffer.put(pcmBytes, 0, pcmLength)

        return buffer.array()
    }
}
