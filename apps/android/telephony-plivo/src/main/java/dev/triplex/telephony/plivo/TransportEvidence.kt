package dev.triplex.telephony.plivo

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaRoute(val wireValue: Int) {
    DIRECT_PJSIP(1),
    RELAY_WEBSOCKET(2),
    ;

    companion object {
        fun fromWire(value: Int): MediaRoute = entries.firstOrNull { it.wireValue == value }
            ?: error("Unknown media route: $value")
    }
}

data class RtpDirectionMetrics(
    val packets: Long,
    val bytes: Long,
    val sequenceGapPackets: Long,
    val reorderedPackets: Long,
    val duplicatePackets: Long,
    val lastArrivalMonotonicNs: Long,
    val lastProviderRtpTimestamp: Long,
    val clockRateHz: Long,
    val jitterUs: Long,
    val lastExtendedSequence: Long,
)

data class NativeMediaMetrics(
    val route: MediaRoute,
    val epoch: Long,
    val flushAckEpoch: Long,
    val captureFrames: Long,
    val captureDroppedFrames: Long,
    val captureDiscontinuities: Long,
    val synthEnqueuedFrames: Long,
    val synthPlayedFrames: Long,
    val synthStaleFrames: Long,
    val synthDroppedFrames: Long,
    val silenceFrames: Long,
    val synthQueueDepth: Long,
    val captureQueueDepth: Long,
    val rx: RtpDirectionMetrics,
    val tx: RtpDirectionMetrics,
)

enum class NegotiatedTlsVersion(val pjSslProtocol: Long) {
    TLS_1_2(1L shl 4),
    TLS_1_3(1L shl 5),
    ;

    companion object {
        private val allowedProtocolMask = entries.fold(0L) { mask, version ->
            mask or version.pjSslProtocol
        }

        fun fromPjSslProtocol(value: Long): NegotiatedTlsVersion? =
            entries.firstOrNull { it.pjSslProtocol == value }

        /**
         * PJSIP's Mbed TLS backend may expose the effective protocol mask
         * (TLS 1.2 | TLS 1.3) after a successful handshake instead of one
         * exact enum bit. The transport itself is configured with only these
         * two versions, so a non-empty subset of that mask is acceptable;
         * any legacy or unknown protocol bit remains fail-closed.
         */
        fun isAllowedProtocolMask(value: Long): Boolean =
            value != 0L && value and allowedProtocolMask == value
    }
}

internal data class TransportSecuritySnapshot(
    val registered: Boolean,
    val registrationStatus: Int,
    val tlsConnected: Boolean,
    val tlsProtocol: Long,
    val tlsCipher: Long,
    val tlsVerifyStatus: Long,
    val tlsLastStatus: Int,
    val mediaActive: Boolean,
    val srtpActive: Boolean,
)

internal enum class TransportSecurityFailure {
    NOT_REGISTERED,
    REGISTRATION_NOT_OK,
    TLS_NOT_CONNECTED,
    TLS_VERSION_NOT_ALLOWED,
    TLS_CIPHER_MISSING,
    CERTIFICATE_NOT_VERIFIED,
    TLS_TRANSPORT_ERROR,
    MEDIA_NOT_ACTIVE,
    SRTP_NOT_ACTIVE,
}

internal data class TransportSecurityVerdict(
    val accepted: Boolean,
    val tlsVersion: NegotiatedTlsVersion?,
    val failures: Set<TransportSecurityFailure>,
)

internal object DirectTransportSecurityPolicy {
    fun evaluate(
        snapshot: TransportSecuritySnapshot,
        requireActiveMedia: Boolean,
    ): TransportSecurityVerdict {
        val version = NegotiatedTlsVersion.fromPjSslProtocol(snapshot.tlsProtocol)
        val failures = mutableSetOf<TransportSecurityFailure>()
        if (!snapshot.registered) failures += TransportSecurityFailure.NOT_REGISTERED
        if (snapshot.registrationStatus != 200) {
            failures += TransportSecurityFailure.REGISTRATION_NOT_OK
        }
        if (!snapshot.tlsConnected) failures += TransportSecurityFailure.TLS_NOT_CONNECTED
        if (!NegotiatedTlsVersion.isAllowedProtocolMask(snapshot.tlsProtocol)) {
            failures += TransportSecurityFailure.TLS_VERSION_NOT_ALLOWED
        }
        if (snapshot.tlsCipher == 0L) failures += TransportSecurityFailure.TLS_CIPHER_MISSING
        if (snapshot.tlsVerifyStatus != 0L) {
            failures += TransportSecurityFailure.CERTIFICATE_NOT_VERIFIED
        }
        if (snapshot.tlsLastStatus != 0) {
            failures += TransportSecurityFailure.TLS_TRANSPORT_ERROR
        }
        if (requireActiveMedia && !snapshot.mediaActive) {
            failures += TransportSecurityFailure.MEDIA_NOT_ACTIVE
        }
        if (requireActiveMedia && !snapshot.srtpActive) {
            failures += TransportSecurityFailure.SRTP_NOT_ACTIVE
        }
        return TransportSecurityVerdict(
            accepted = failures.isEmpty(),
            tlsVersion = version,
            failures = failures,
        )
    }
}

data class DirectTransportMetrics(
    val registered: Boolean,
    val srtpActive: Boolean,
    val mediaActive: Boolean,
    val tlsConnected: Boolean,
    val registrationStatus: Int,
    val activeCallId: Int,
    val tlsProtocol: Long,
    val tlsCipher: Long,
    val tlsVerifyStatus: Long,
    val tlsLastStatus: Int,
    val codecClockRateHz: Long,
    val codecChannelCount: Long,
    val capturedPcmFrames: Long,
    val capturedNonSilentPcmFrames: Long,
    val capturedPcmChecksum: Long,
    val capturedPcmPeak: Long,
    val droppedNativeEvents: Long,
    val callbackToEnqueueMaxNs: Long,
    val callbackToEnqueueBuckets: List<Long>,
    val rtcpRxPackets: Long,
    val rtcpRxLost: Long,
    val rtcpRxDiscarded: Long,
    val rtcpRxReordered: Long,
    val rtcpRxDuplicates: Long,
    val rtcpRxJitterMeanUs: Long,
    val rtcpRxJitterMaxUs: Long,
    val rtcpRttMeanUs: Long,
    val jitterBufferFrames: Long,
    val jitterBufferAverageDelayMs: Long,
    val jitterBufferMaxDelayMs: Long,
    val jitterBufferLostFrames: Long,
    val jitterBufferDiscardedFrames: Long,
    val sourceRtpAddress: String,
    val media: NativeMediaMetrics,
) {
    internal val securitySnapshot: TransportSecuritySnapshot
        get() = TransportSecuritySnapshot(
            registered = registered,
            registrationStatus = registrationStatus,
            tlsConnected = tlsConnected,
            tlsProtocol = tlsProtocol,
            tlsCipher = tlsCipher,
            tlsVerifyStatus = tlsVerifyStatus,
            tlsLastStatus = tlsLastStatus,
            mediaActive = mediaActive,
            srtpActive = srtpActive,
        )

    val negotiatedTlsVersion: NegotiatedTlsVersion?
        get() = NegotiatedTlsVersion.fromPjSslProtocol(tlsProtocol)

    /**
     * Public Triplex SIP edges register over UDP without TLS/SRTP (Plivo Dial
     * bridges PSTN as plain RTP). Plivo Direct endpoints still require the
     * full TLS+SRTP evidence path.
     */
    val edgePlainTransport: Boolean
        get() = registered && registrationStatus == 200 && !tlsConnected

    val secureSignalingReady: Boolean
        get() = if (edgePlainTransport) {
            true
        } else {
            DirectTransportSecurityPolicy.evaluate(
                securitySnapshot,
                requireActiveMedia = false,
            ).accepted
        }

    val secureMediaReady: Boolean
        get() = if (edgePlainTransport) {
            mediaActive
        } else {
            DirectTransportSecurityPolicy.evaluate(
                securitySnapshot,
                requireActiveMedia = true,
            ).accepted
        }

    val callbackToEnqueueP95UpperBoundNs: Long
        get() {
            val total = callbackToEnqueueBuckets.sum()
            if (total == 0L) return 0L
            val target = (total * 95L + 99L) / 100L
            val bounds = longArrayOf(
                250_000L,
                500_000L,
                1_000_000L,
                2_000_000L,
                5_000_000L,
                10_000_000L,
                20_000_000L,
                maxOf(20_000_000L, callbackToEnqueueMaxNs),
            )
            var cumulative = 0L
            callbackToEnqueueBuckets.forEachIndexed { index, count ->
                cumulative += count
                if (cumulative >= target) return bounds[index]
            }
            return callbackToEnqueueMaxNs
        }
}

internal object TransportEvidenceDecoder {
    private const val ABI_VERSION = 1

    fun decode(buffer: ByteBuffer): DirectTransportMetrics {
        buffer.order(ByteOrder.nativeOrder()).position(0)
        check(buffer.short.toUShort().toInt() == ABI_VERSION) { "Metrics ABI mismatch" }
        check(buffer.short.toUShort().toInt() == NativePjsip.METRICS_BYTES) {
            "Metrics record size mismatch"
        }
        val registered = buffer.u8() != 0
        val srtpActive = buffer.u8() != 0
        val mediaActive = buffer.u8() != 0
        val tlsConnected = buffer.u8() != 0
        val registrationStatus = buffer.int
        val activeCallId = buffer.int
        val tlsProtocol = buffer.u32()
        val tlsCipher = buffer.u32()
        val tlsVerifyStatus = buffer.u32()
        val tlsLastStatus = buffer.int
        val codecClockRateHz = buffer.u32()
        val codecChannelCount = buffer.u32()
        val capturedPcmFrames = buffer.long
        val capturedNonSilentPcmFrames = buffer.long
        val capturedPcmChecksum = buffer.long
        val capturedPcmPeak = buffer.u32()
        val droppedNativeEvents = buffer.u32()
        val callbackToEnqueueMaxNs = buffer.long
        val callbackToEnqueueBuckets = List(8) { buffer.long }
        val rtcp = List(8) { buffer.long }
        val jitterBuffer = List(5) { buffer.u32() }
        val addressBytes = ByteArray(96)
        buffer.get(addressBytes)
        val terminator = addressBytes.indexOf(0).let { if (it < 0) addressBytes.size else it }
        val sourceRtpAddress = addressBytes.decodeToString(endIndex = terminator)
        buffer.int // reserved for ABI-compatible transport evidence fields
        val media = decodeNativeMedia(buffer)
        check(buffer.position() == NativePjsip.METRICS_BYTES) { "Metrics decoder drift" }

        return DirectTransportMetrics(
            registered = registered,
            srtpActive = srtpActive,
            mediaActive = mediaActive,
            tlsConnected = tlsConnected,
            registrationStatus = registrationStatus,
            activeCallId = activeCallId,
            tlsProtocol = tlsProtocol,
            tlsCipher = tlsCipher,
            tlsVerifyStatus = tlsVerifyStatus,
            tlsLastStatus = tlsLastStatus,
            codecClockRateHz = codecClockRateHz,
            codecChannelCount = codecChannelCount,
            capturedPcmFrames = capturedPcmFrames,
            capturedNonSilentPcmFrames = capturedNonSilentPcmFrames,
            capturedPcmChecksum = capturedPcmChecksum,
            capturedPcmPeak = capturedPcmPeak,
            droppedNativeEvents = droppedNativeEvents,
            callbackToEnqueueMaxNs = callbackToEnqueueMaxNs,
            callbackToEnqueueBuckets = callbackToEnqueueBuckets,
            rtcpRxPackets = rtcp[0],
            rtcpRxLost = rtcp[1],
            rtcpRxDiscarded = rtcp[2],
            rtcpRxReordered = rtcp[3],
            rtcpRxDuplicates = rtcp[4],
            rtcpRxJitterMeanUs = rtcp[5],
            rtcpRxJitterMaxUs = rtcp[6],
            rtcpRttMeanUs = rtcp[7],
            jitterBufferFrames = jitterBuffer[0],
            jitterBufferAverageDelayMs = jitterBuffer[1],
            jitterBufferMaxDelayMs = jitterBuffer[2],
            jitterBufferLostFrames = jitterBuffer[3],
            jitterBufferDiscardedFrames = jitterBuffer[4],
            sourceRtpAddress = sourceRtpAddress,
            media = media,
        )
    }

    private fun decodeNativeMedia(buffer: ByteBuffer): NativeMediaMetrics {
        check(buffer.short.toUShort().toInt() == ABI_VERSION) { "Media ABI mismatch" }
        check(buffer.short.toUShort().toInt() == 232) { "Media record size mismatch" }
        val route = MediaRoute.fromWire(buffer.u8())
        buffer.position(buffer.position() + 3)
        val epoch = buffer.u32()
        val flushAckEpoch = buffer.u32()
        val counters = List(8) { buffer.long }
        val synthQueueDepth = buffer.u32()
        val captureQueueDepth = buffer.u32()
        return NativeMediaMetrics(
            route = route,
            epoch = epoch,
            flushAckEpoch = flushAckEpoch,
            captureFrames = counters[0],
            captureDroppedFrames = counters[1],
            captureDiscontinuities = counters[2],
            synthEnqueuedFrames = counters[3],
            synthPlayedFrames = counters[4],
            synthStaleFrames = counters[5],
            synthDroppedFrames = counters[6],
            silenceFrames = counters[7],
            synthQueueDepth = synthQueueDepth,
            captureQueueDepth = captureQueueDepth,
            rx = decodeRtpDirection(buffer),
            tx = decodeRtpDirection(buffer),
        )
    }

    private fun decodeRtpDirection(buffer: ByteBuffer): RtpDirectionMetrics =
        RtpDirectionMetrics(
            packets = buffer.long,
            bytes = buffer.long,
            sequenceGapPackets = buffer.long,
            reorderedPackets = buffer.long,
            duplicatePackets = buffer.long,
            lastArrivalMonotonicNs = buffer.long,
            lastProviderRtpTimestamp = buffer.u32(),
            clockRateHz = buffer.u32(),
            jitterUs = buffer.long,
            lastExtendedSequence = buffer.long,
        )

    private fun ByteBuffer.u8(): Int = get().toUByte().toInt()
    private fun ByteBuffer.u32(): Long = int.toUInt().toLong()
}
