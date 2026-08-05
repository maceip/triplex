package dev.triplex.telephony.plivo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryEvidenceDecoderTest {
    @Test
    fun decodesSipEventAbi() {
        val buffer = ByteBuffer.allocate(NativePjsip.EVENT_BYTES).order(ByteOrder.nativeOrder())
        buffer.putShort(1.toShort())
        buffer.putShort(NativePjsip.EVENT_BYTES.toShort())
        buffer.putShort(SipEvent.Type.DTMF.wireValue.toShort())
        buffer.putShort(200.toShort())
        buffer.putInt(7)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(160)
        buffer.putLong(123_456L)
        buffer.put('#'.code.toByte())
        buffer.put(1.toByte())
        repeat(6) { buffer.put(0.toByte()) }

        val event = SipEventDecoder.decode(buffer, 1).single()
        assertEquals(SipEvent.Type.DTMF, event.type)
        assertEquals(7, event.callId)
        assertEquals('#', event.digit)
        assertEquals(123_456L, event.monotonicNs)
    }

    @Test
    fun decodesMetricsAbiWithoutOffsetDrift() {
        val buffer = ByteBuffer.allocate(NativePjsip.METRICS_BYTES)
            .order(ByteOrder.nativeOrder())
        buffer.putShort(1.toShort())
        buffer.putShort(NativePjsip.METRICS_BYTES.toShort())
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        buffer.putInt(200)
        buffer.putInt(9)
        buffer.putInt(32)
        buffer.putInt(0x1301)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(8_000)
        buffer.putInt(1)
        buffer.putLong(10)
        buffer.putLong(9)
        buffer.putLong(0x1234)
        buffer.putInt(3_000)
        buffer.putInt(0)
        buffer.putLong(25_000_000)
        repeat(8) { buffer.putLong(it.toLong()) }
        repeat(8) { buffer.putLong((it + 10).toLong()) }
        repeat(5) { buffer.putInt(it) }
        val source = "203.0.113.10:40000".encodeToByteArray()
        buffer.put(source)
        repeat(96 - source.size) { buffer.put(0.toByte()) }
        buffer.putInt(0)
        putMediaMetrics(buffer)
        assertEquals(NativePjsip.METRICS_BYTES, buffer.position())

        val metrics = TransportEvidenceDecoder.decode(buffer)
        assertTrue(metrics.secureSignalingReady)
        assertTrue(metrics.srtpActive)
        assertEquals(NegotiatedTlsVersion.TLS_1_3, metrics.negotiatedTlsVersion)
        assertEquals(9, metrics.activeCallId)
        assertEquals(25_000_000L, metrics.callbackToEnqueueMaxNs)
        assertEquals(25_000_000L, metrics.callbackToEnqueueP95UpperBoundNs)
        assertEquals("203.0.113.10:40000", metrics.sourceRtpAddress)
        assertEquals(MediaRoute.DIRECT_PJSIP, metrics.media.route)
        assertEquals(32L, metrics.media.rx.sequenceGapPackets)
        assertEquals(107L, metrics.media.tx.lastExtendedSequence)
    }

    private fun putMediaMetrics(buffer: ByteBuffer) {
        buffer.putShort(1.toShort())
        buffer.putShort(232.toShort())
        buffer.put(MediaRoute.DIRECT_PJSIP.wireValue.toByte())
        repeat(3) { buffer.put(0.toByte()) }
        buffer.putInt(4)
        buffer.putInt(4)
        repeat(8) { buffer.putLong((it + 20).toLong()) }
        buffer.putInt(2)
        buffer.putInt(3)
        putRtpDirection(buffer, 30)
        putRtpDirection(buffer, 100)
    }

    private fun putRtpDirection(buffer: ByteBuffer, base: Int) {
        repeat(5) { buffer.putLong((base + it).toLong()) }
        buffer.putLong((base + 5).toLong())
        buffer.putInt(base + 6)
        buffer.putInt(8_000)
        buffer.putLong((base + 7).toLong())
        buffer.putLong((base + 7).toLong())
    }
}
