package com.triplex.dialer

/** Application-wide constants. */
object Constants {
    /** Chime SIP media application hostname. */
    const val CHIME_SIP_MEDIA_APP = "chime-sip-media-app.triplex.secure.build"

    /** Chime SIP media application full URL (HTTPS). */
    const val CHIME_SIP_MEDIA_APP_URL = "https://$CHIME_SIP_MEDIA_APP"

    /** Default Chime AWS region for meetings. */
    const val CHIME_DEFAULT_REGION = "us-east-1"

    /** Triplex voice agent API base URL. */
    const val TRIPLEX_API_BASE = "https://api.triplex.secure.build/v1"

    /** WebSocket gateway for real-time audio. */
    const val TRIPLEX_WS_GATEWAY = "wss://gateway.triplex.secure.build/ws"

    /** Plivo bridge server for telephony audio streaming. */
    const val PLIVO_BRIDGE_WS_URL = "wss://bridge.public.computer"

    /** Default echo delay calibration (ms). Calibrated per PSTN line. */
    const val DEFAULT_ECHO_DELAY_MS = 30

    /** Interruption teardown deadline (ms). */
    const val INTERRUPTION_DEADLINE_MS = 50

    /** Voice activity detection threshold (dB). */
    const val VAD_THRESHOLD_DB = -30.0f

    /** Max duration for voice cloning reference audio (seconds). */
    const val MAX_CLONE_REFERENCE_SEC = 30

    /** Minimum duration for voice cloning reference audio (seconds). */
    const val MIN_CLONE_REFERENCE_SEC = 3
}
