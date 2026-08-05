package dev.triplex.telephony.plivo

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SipEvent(
    val type: Type,
    val code: Int,
    val callId: Int,
    val status: Int,
    val operation: Int,
    val value: Long,
    val monotonicNs: Long,
    val digit: Char?,
    val method: Int,
) {
    enum class Type(val wireValue: Int) {
        REGISTRATION(1),
        INCOMING_CALL(2),
        CALL_STATE(3),
        MEDIA_STATE(4),
        DTMF(5),
        IP_CHANGE(6),
        CONFERENCE_OPERATION(7),
        TRANSPORT_STATE(8),
        ;

        companion object {
            fun fromWire(value: Int): Type = entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown SIP event type: $value")
        }
    }
}

internal object SipEventDecoder {
    fun decode(buffer: ByteBuffer, count: Int): List<SipEvent> {
        require(count in 0..NativePjsip.MAX_EVENT_BATCH)
        buffer.order(ByteOrder.nativeOrder()).position(0)
        return List(count) {
            check(buffer.short.toUShort().toInt() == 1) { "SIP event ABI mismatch" }
            check(buffer.short.toUShort().toInt() == NativePjsip.EVENT_BYTES) {
                "SIP event record size mismatch"
            }
            val type = SipEvent.Type.fromWire(buffer.short.toUShort().toInt())
            val code = buffer.short.toUShort().toInt()
            val callId = buffer.int
            val status = buffer.int
            val operation = buffer.int
            val value = buffer.int.toUInt().toLong()
            val monotonicNs = buffer.long
            val digitByte = buffer.get().toUByte().toInt()
            val method = buffer.get().toUByte().toInt()
            buffer.position(buffer.position() + 6)
            SipEvent(
                type = type,
                code = code,
                callId = callId,
                status = status,
                operation = operation,
                value = value,
                monotonicNs = monotonicNs,
                digit = digitByte.takeIf { it != 0 }?.toChar(),
                method = method,
            )
        }
    }
}
