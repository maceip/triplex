#ifndef TRIPLEX_PJ_CONFIG_SITE_H
#define TRIPLEX_PJ_CONFIG_SITE_H

/* One registered endpoint and one live call for the direct-media spike. */
#define PJSUA_MAX_CALLS 1
#define PJSUA_MAX_ACC 1
#define PJSUA_MAX_BUDDIES 1
#define PJSUA_MAX_PLAYERS 1
#define PJSUA_MAX_RECORDERS 1
#define PJSUA_MAX_CONF_PORTS 4

/* PJSIP logging is disabled at runtime; compile out verbose paths as well. */
#define PJ_LOG_MAX_LEVEL 2
#define PJSIP_MAX_PKT_LEN 4000

/* The direct agent endpoint must never open Android capture/playout hardware. */
#define PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI 0
#define PJMEDIA_AUDIO_DEV_HAS_OPENSL 0
#define PJMEDIA_AUDIO_DEV_HAS_OBOE 0
#define PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO 0

/* G.711 is built in; do not pull Android's general MediaCodec surface in. */
#define PJMEDIA_HAS_ANDROID_MEDIACODEC 0

#endif
