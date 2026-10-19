package com.br.resonance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {

    @Test
    fun testAudioPacketEncodeAndDecode() {
        val sessionId = 123456789L
        val sequenceNumber = 42L
        val timestampMs = 1700000000000L
        val sampleRate = 24000
        val pcmPayload = ByteArray(960) { (it % 127).toByte() }

        val encoded = AudioPacket.encode(
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            timestampMs = timestampMs,
            sampleRate = sampleRate,
            pcmBytes = pcmPayload,
            pcmLength = pcmPayload.size
        )

        assertEquals(AudioPacket.HEADER_SIZE + pcmPayload.size, encoded.size)

        val decoded = ReceivedAudioPacket.decode(encoded, encoded.size)
        assertNotNull(decoded)
        assertEquals(1.toByte(), decoded!!.version)
        assertEquals(sessionId, decoded.sessionId)
        assertEquals(sequenceNumber, decoded.sequenceNumber)
        assertEquals(timestampMs, decoded.timestampMs)
        assertEquals(sampleRate, decoded.sampleRate)
        assertEquals(1.toShort(), decoded.channelCount)
        assertEquals(1.toShort(), decoded.format)
        assertArrayEquals(pcmPayload, decoded.payload)
    }

    @Test
    fun testDecodeLegacyRawPcm() {
        val rawPcm = ByteArray(960) { 0x05 }
        val decoded = ReceivedAudioPacket.decode(rawPcm, rawPcm.size)

        assertNotNull(decoded)
        assertEquals(0.toByte(), decoded!!.version)
        assertEquals(24000, decoded.sampleRate)
        assertEquals(1.toShort(), decoded.channelCount)
        assertArrayEquals(rawPcm, decoded.payload)
    }

    @Test
    fun testDecodeInvalidShortPacket() {
        val invalidBytes = byteArrayOf(0x01, 0x02, 0x03)
        val decoded = ReceivedAudioPacket.decode(invalidBytes, invalidBytes.size)
        assertNull(decoded)
    }

    @Test
    fun testAudioAnalyzerSilenceDetection() {
        val silentShorts = ShortArray(480) { 0 }
        val analysis = AudioAnalyzer.analyze(silentShorts, silentShorts.size)

        assertEquals(0, analysis.peak)
        assertEquals(0, analysis.peakPercent)
        assertEquals(-100f, analysis.rmsDbfs, 0.01f)
        assertTrue(analysis.isSilent)
    }

    @Test
    fun testAudioAnalyzerLoudSignalDetection() {
        val loudShorts = ShortArray(480) { 20000 }
        val analysis = AudioAnalyzer.analyze(loudShorts, loudShorts.size)

        assertTrue(analysis.peak >= 20000)
        assertTrue(analysis.peakPercent > 50)
        assertTrue(analysis.rmsDbfs > -10.0f)
        assertFalse(analysis.isSilent)
    }

    @Test
    fun testJitterDelayBufferPushAndPoll() {
        val buffer = JitterDelayBuffer(baseCushionMs = 40)
        val payload = ByteArray(960) { 1 }

        val packet = ReceivedAudioPacket(
            version = 1,
            sessionId = 1L,
            sequenceNumber = 1L,
            timestampMs = 1000L,
            sampleRate = 24000,
            channelCount = 1,
            format = 1,
            payload = payload
        )

        // 40ms cushion = 40 * 48 = 1920 bytes (2 frames of 960 bytes)
        buffer.push(packet, offsetMs = 0)
        assertTrue(buffer.isPreRolling)

        val packet2 = packet.copy(sequenceNumber = 2L)
        buffer.push(packet2, offsetMs = 0)

        // Now cushion is reached (1920 bytes buffered)
        assertFalse(buffer.isPreRolling)
        assertEquals(2, buffer.getQueuedFrameCount())
        assertEquals(40, buffer.getBufferedDepthMs())

        // Poll one frame (960 bytes)
        val polled = buffer.pollBlocking(timeoutMs = 50L)
        assertNotNull(polled)
        assertEquals(960, polled!!.size)
        assertEquals(1, buffer.getQueuedFrameCount())

        // Clear buffer
        buffer.clear()
        assertEquals(0, buffer.getQueuedFrameCount())
        assertTrue(buffer.isPreRolling)
    }

    @Test
    fun testJitterDelayBufferNegativeDelayAdjustment() {
        val buffer = JitterDelayBuffer(baseCushionMs = 80)
        val payload = ByteArray(960) { 0 }
        val packet = ReceivedAudioPacket(
            version = 1,
            sessionId = 1L,
            sequenceNumber = 1L,
            timestampMs = 1000L,
            sampleRate = 24000,
            channelCount = 1,
            format = 1,
            payload = payload
        )

        // Push 4 frames (80ms cushion)
        repeat(4) { buffer.push(packet, offsetMs = 0) }
        assertEquals(80, buffer.getBufferedDepthMs())

        // Now apply negative offset (-20ms) -> target becomes 60ms
        buffer.push(packet, offsetMs = -20)
        assertEquals(60, buffer.currentTargetMs)
    }
}
