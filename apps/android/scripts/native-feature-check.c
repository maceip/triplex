#include <pj/config.h>
#include <pjmedia-audiodev/config.h>
#include <pjmedia-codec/config.h>
#include <pjmedia/config.h>

#if !defined(PJ_HAS_SSL_SOCK) || PJ_HAS_SSL_SOCK == 0
#error "Triplex requires PJSIP TLS support"
#endif

#if !defined(PJ_SSL_SOCK_IMP) || PJ_SSL_SOCK_IMP != PJ_SSL_SOCK_IMP_MBEDTLS
#error "Triplex's pinned Android PJSIP build must use Mbed TLS"
#endif

#if !defined(PJMEDIA_HAS_SRTP) || PJMEDIA_HAS_SRTP == 0
#error "Triplex requires PJSIP SRTP support"
#endif

#if defined(PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI) &&                              \
    PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI != 0
#error "Direct agent media must not compile the Android hardware audio backend"
#endif

#if defined(PJMEDIA_AUDIO_DEV_HAS_OPENSL) && PJMEDIA_AUDIO_DEV_HAS_OPENSL != 0
#error "Direct agent media must not compile the OpenSL hardware audio backend"
#endif

#if defined(PJMEDIA_AUDIO_DEV_HAS_OBOE) && PJMEDIA_AUDIO_DEV_HAS_OBOE != 0
#error "Direct agent media must not compile the Oboe hardware audio backend"
#endif

#if defined(PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO) &&                                \
    PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO != 0
#error "Direct agent media must not compile the PortAudio hardware backend"
#endif

#if defined(PJMEDIA_HAS_ANDROID_MEDIACODEC) &&                                 \
    PJMEDIA_HAS_ANDROID_MEDIACODEC != 0
#error "The G.711-only direct endpoint must not compile Android MediaCodec"
#endif

int main(void) { return 0; }
