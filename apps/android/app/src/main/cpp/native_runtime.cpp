// App-side JNI over the Rust media runtime (libtriplex_native_media.so).
//
// The former hand-rolled C++ SpscRing/FramePool/AudioPipelineImpl scaffold is
// gone: frame pooling, epoch invalidation, and queue semantics live in the
// Rust crate (see RUNTIME_INVARIANTS.md). This file only adapts JNI calls to
// the C FFI in triplex_native_media.h.

#include <jni.h>
#include <android/log.h>
#include <triplex_native_media.h>

#include <atomic>
#include <chrono>

#define LOG_TAG "TriplexNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<TriplexNativeMediaRuntime*> g_media{nullptr};
std::atomic<TriplexAgentSession*> g_agent{nullptr};

void destroy_agent_if_any() {
    TriplexAgentSession* session = g_agent.exchange(nullptr, std::memory_order_acq_rel);
    if (session != nullptr) {
        triplex_agent_destroy(session);
        LOGI("Agent session destroyed");
    }
}

int64_t monotonic_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_dev_triplex_nativebridge_runtime_NativeRuntime_nativeInitialize(
    JNIEnv*, jobject) {
    if (g_media.load(std::memory_order_acquire) != nullptr) {
        LOGW("Rust media runtime already initialized");
        return JNI_TRUE;
    }
    TriplexNativeMediaRuntime* runtime =
        triplex_media_create(TRIPLEX_ROUTE_DIRECT_PJSIP);
    if (runtime == nullptr) {
        LOGE("triplex_media_create failed");
        return JNI_FALSE;
    }
    TriplexNativeMediaRuntime* expected = nullptr;
    if (!g_media.compare_exchange_strong(expected, runtime,
                                         std::memory_order_acq_rel)) {
        triplex_media_destroy(runtime);
        return JNI_TRUE;
    }
    LOGI("Rust media runtime initialized (epoch: %u)",
         triplex_media_epoch(runtime));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_triplex_nativebridge_runtime_NativeRuntime_nativeShutdown(
    JNIEnv*, jobject) {
    // FFI contract: the agent session must die before the media runtime.
    destroy_agent_if_any();
    TriplexNativeMediaRuntime* runtime =
        g_media.exchange(nullptr, std::memory_order_acq_rel);
    if (runtime != nullptr) {
        triplex_media_destroy(runtime);
        LOGI("Rust media runtime destroyed");
    }
}

JNIEXPORT jlong JNICALL Java_dev_triplex_nativebridge_runtime_NativeRuntime_nativeGetTimestamp(
    JNIEnv*, jobject) {
    return monotonic_us();
}

JNIEXPORT jstring JNICALL Java_dev_triplex_nativebridge_runtime_NativeRuntime_nativeGetVersion(
    JNIEnv* env, jobject) {
    return env->NewStringUTF("0.2.0-rust-media");
}

// Legacy log-only acknowledgements; real SIP lives in :telephony-plivo.
JNIEXPORT jboolean JNICALL
Java_dev_triplex_nativebridge_runtime_NativeRuntimeManager_ackedSipRegister(
    JNIEnv*, jobject, jlong epoch, jstring, jstring, jstring, jstring) {
    LOGI("SIP registration acknowledged (epoch: %lld)",
         static_cast<long long>(epoch));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_triplex_nativebridge_runtime_NativeRuntimeManager_ackedSipUnregister(
    JNIEnv*, jobject, jlong epoch) {
    LOGI("SIP unregistration acknowledged (epoch: %lld)",
         static_cast<long long>(epoch));
}

// The Kotlin AudioPipeline is a state shell; frame flow runs natively between
// PJSIP and the Rust pools. Start reports whether the runtime is warm; stop
// publishes an interruption so queued synthesis is flushed (§3.4).
JNIEXPORT jboolean JNICALL
Java_dev_triplex_nativebridge_audio_AudioPipeline_nativeStartPipeline(
    JNIEnv*, jobject, jlong, jlong epoch) {
    TriplexNativeMediaRuntime* runtime = g_media.load(std::memory_order_acquire);
    if (runtime == nullptr) {
        LOGE("Pipeline start rejected: media runtime not initialized");
        return JNI_FALSE;
    }
    LOGI("Pipeline start (call epoch: %lld, media epoch: %u)",
         static_cast<long long>(epoch), triplex_media_epoch(runtime));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_triplex_nativebridge_audio_AudioPipeline_nativeStopPipeline(
    JNIEnv*, jobject, jlong) {
    TriplexNativeMediaRuntime* runtime = g_media.load(std::memory_order_acquire);
    if (runtime != nullptr) {
        uint32_t next = triplex_media_interrupt(runtime);
        LOGI("Pipeline stop: synthesis flushed (new epoch: %u)", next);
    }
}

// Baseline agent session (triplex-agent-core inside the Rust cdylib).
JNIEXPORT jboolean JNICALL
Java_dev_triplex_nativebridge_agent_AgentBridge_nativeAgentInitialize(JNIEnv*, jobject) {
    TriplexNativeMediaRuntime* media = g_media.load(std::memory_order_acquire);
    if (media == nullptr) {
        LOGE("Agent start rejected: media runtime not initialized");
        return JNI_FALSE;
    }
    if (g_agent.load(std::memory_order_acquire) != nullptr) {
        LOGW("Agent session already running");
        return JNI_TRUE;
    }
    TriplexAgentSession* session = triplex_agent_create(media);
    if (session == nullptr) {
        LOGE("triplex_agent_create failed");
        return JNI_FALSE;
    }
    TriplexAgentSession* expected = nullptr;
    if (!g_agent.compare_exchange_strong(expected, session,
                                         std::memory_order_acq_rel)) {
        triplex_agent_destroy(session);
        return JNI_TRUE;
    }
    LOGI("Baseline agent session started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_triplex_nativebridge_agent_AgentBridge_nativeAgentReset(JNIEnv*, jobject) {
    destroy_agent_if_any();
}

JNIEXPORT void JNICALL
Java_dev_triplex_nativebridge_agent_AgentBridge_nativeAgentShutdown(JNIEnv*, jobject) {
    destroy_agent_if_any();
}

JNIEXPORT jboolean JNICALL
Java_dev_triplex_nativebridge_agent_AgentBridge_nativeAgentStatus(
    JNIEnv* env, jobject, jobject buffer) {
    TriplexAgentSession* session = g_agent.load(std::memory_order_acquire);
    if (session == nullptr || buffer == nullptr) {
        return JNI_FALSE;
    }
    void* address = env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr ||
        capacity < static_cast<jlong>(sizeof(TriplexAgentStatus))) {
        return JNI_FALSE;
    }
    return triplex_agent_status(session, static_cast<TriplexAgentStatus*>(address))
               ? JNI_TRUE
               : JNI_FALSE;
}

// Demo/test capture injection. The capture ring is single-producer: inject
// only while no call is active (the PJSIP media port is the producer then).
JNIEXPORT jboolean JNICALL
Java_dev_triplex_nativebridge_agent_AgentBridge_nativeInjectCapture(
    JNIEnv* env, jobject, jshortArray pcm, jlong mono_ns, jint rtp_ts) {
    TriplexNativeMediaRuntime* media = g_media.load(std::memory_order_acquire);
    if (media == nullptr || pcm == nullptr) {
        return JNI_FALSE;
    }
    jsize len = env->GetArrayLength(pcm);
    if (len <= 0 || len > TRIPLEX_FRAME_SAMPLES) {
        return JNI_FALSE;
    }
    jshort samples[TRIPLEX_FRAME_SAMPLES];
    env->GetShortArrayRegion(pcm, 0, len, samples);
    return triplex_media_capture_push(media, samples, static_cast<size_t>(len),
                                      mono_ns, static_cast<uint32_t>(rtp_ts))
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_triplex_nativebridge_agent_AgentBridge_nativeInterrupt(
    JNIEnv*, jobject, jlong epoch, jstring) {
    TriplexNativeMediaRuntime* runtime = g_media.load(std::memory_order_acquire);
    if (runtime != nullptr) {
        uint32_t next = triplex_media_interrupt(runtime);
        LOGI("Agent interrupt (caller epoch: %lld, new media epoch: %u)",
             static_cast<long long>(epoch), next);
    }
}

}  // extern "C"
