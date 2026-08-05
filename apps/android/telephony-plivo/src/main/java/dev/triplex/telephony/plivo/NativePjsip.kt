package dev.triplex.telephony.plivo

import java.nio.ByteBuffer

internal object NativePjsip {
    const val EVENT_BYTES = 40
    const val METRICS_BYTES = 560
    const val MAX_EVENT_BATCH = 32

    init {
        System.loadLibrary("triplex_telephony")
    }

    @JvmStatic external fun nativeCreate(
        usernameUtf8: ByteArray,
        passwordUtf8: ByteArray,
        domainUtf8: ByteArray,
        realmUtf8: ByteArray?,
        caBundlePathUtf8: ByteArray,
        tlsPort: Int,
        registrationTimeoutSeconds: Int,
    ): Long

    @JvmStatic external fun nativeStart(handle: Long): Int
    @JvmStatic external fun nativeAnswer(handle: Long, callId: Int): Int
    @JvmStatic external fun nativeMakeCall(handle: Long, authorizedSipUri: String): Int
    @JvmStatic external fun nativeSendDtmf(handle: Long, callId: Int, digits: String): Int
    @JvmStatic external fun nativeInterrupt(handle: Long): Long
    @JvmStatic external fun nativeHangup(handle: Long, callId: Int, sipStatus: Int): Int
    @JvmStatic external fun nativeHandleNetworkChange(handle: Long): Int
    @JvmStatic external fun nativeReregister(handle: Long): Int
    @JvmStatic external fun nativeStartProbeTone(
        handle: Long,
        frequencyHz: Int,
        durationMs: Int,
        amplitude: Int,
    ): Int

    @JvmStatic external fun nativeDrainEvents(handle: Long, output: ByteBuffer): Int
    @JvmStatic external fun nativeMetricsSnapshot(handle: Long, output: ByteBuffer): Boolean
    @JvmStatic external fun nativeDestroy(handle: Long)
}
