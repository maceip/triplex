#include "triplex_pjsip.h"

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace {

constexpr size_t kEventRecordBytes = 40;
constexpr size_t kMetricsRecordBytes = 560;
constexpr size_t kMaxEventBatch = 32;

static_assert(sizeof(TriplexSipEvent) == kEventRecordBytes);
static_assert(sizeof(TriplexRtpDirectionSnapshot) == 72);
static_assert(sizeof(TriplexMediaMetrics) == 232);
static_assert(sizeof(TriplexPjsipMetrics) == kMetricsRecordBytes);

template <size_t N> class ScopedUtf8Bytes {
public:
  bool read(JNIEnv *environment, jbyteArray source, bool allow_empty = false) {
    if (environment == nullptr || source == nullptr) {
      return false;
    }
    const jsize length = environment->GetArrayLength(source);
    if (length < 0 || static_cast<size_t>(length) >= bytes_.size() ||
        (!allow_empty && length == 0)) {
      return false;
    }
    environment->GetByteArrayRegion(source, 0, length,
                                    reinterpret_cast<jbyte *>(bytes_.data()));
    if (environment->ExceptionCheck()) {
      return false;
    }
    if (std::memchr(bytes_.data(), 0, static_cast<size_t>(length)) != nullptr) {
      return false;
    }
    bytes_[static_cast<size_t>(length)] = '\0';
    return true;
  }

  const char *get() const { return bytes_.data(); }

  ~ScopedUtf8Bytes() {
    volatile char *cursor = bytes_.data();
    for (size_t index = 0; index < bytes_.size(); ++index) {
      cursor[index] = 0;
    }
  }

private:
  std::array<char, N> bytes_{};
};

TriplexPjsipEngine *from_handle(jlong handle) {
  return reinterpret_cast<TriplexPjsipEngine *>(static_cast<uintptr_t>(handle));
}

jlong to_handle(TriplexPjsipEngine *engine) {
  return static_cast<jlong>(reinterpret_cast<uintptr_t>(engine));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeCreate(
    JNIEnv *environment, jclass, jbyteArray username, jbyteArray password,
    jbyteArray domain, jbyteArray realm, jbyteArray ca_bundle_path,
    jint tls_port, jint registration_timeout_seconds) {
  ScopedUtf8Bytes<128> native_username;
  ScopedUtf8Bytes<256> native_password;
  ScopedUtf8Bytes<256> native_domain;
  ScopedUtf8Bytes<256> native_realm;
  ScopedUtf8Bytes<1024> native_ca_bundle_path;
  if (!native_username.read(environment, username) ||
      !native_password.read(environment, password) ||
      !native_domain.read(environment, domain) ||
      !native_ca_bundle_path.read(environment, ca_bundle_path) ||
      (realm != nullptr && !native_realm.read(environment, realm))) {
    return 0;
  }

  TriplexSipConfig config{};
  config.username = native_username.get();
  config.password = native_password.get();
  config.domain = native_domain.get();
  config.realm = realm == nullptr ? nullptr : native_realm.get();
  config.ca_bundle_path = native_ca_bundle_path.get();
  config.tls_port =
      tls_port > 0 && tls_port <= 65'535 ? static_cast<uint16_t>(tls_port) : 0;
  config.registration_timeout_seconds =
      registration_timeout_seconds > 0 && registration_timeout_seconds <= 65'535
          ? static_cast<uint16_t>(registration_timeout_seconds)
          : 0;
  return to_handle(triplex_pjsip_create(&config));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeStart(JNIEnv *, jclass,
                                                         jlong handle) {
  return triplex_pjsip_start(from_handle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeAnswer(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jint call_id) {
  return triplex_pjsip_answer(from_handle(handle), call_id);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeMakeCall(
    JNIEnv *environment, jclass, jlong handle, jstring authorized_sip_uri,
    jstring grant_header_name, jstring grant_header_value) {
  if (authorized_sip_uri == nullptr || grant_header_name == nullptr ||
      grant_header_value == nullptr) {
    return -1;
  }
  const char *uri = environment->GetStringUTFChars(authorized_sip_uri, nullptr);
  if (uri == nullptr) {
    return -1;
  }
  const char *header_name =
      environment->GetStringUTFChars(grant_header_name, nullptr);
  if (header_name == nullptr) {
    environment->ReleaseStringUTFChars(authorized_sip_uri, uri);
    return -1;
  }
  const char *header_value =
      environment->GetStringUTFChars(grant_header_value, nullptr);
  if (header_value == nullptr) {
    environment->ReleaseStringUTFChars(grant_header_name, header_name);
    environment->ReleaseStringUTFChars(authorized_sip_uri, uri);
    return -1;
  }
  const int status = triplex_pjsip_make_call(from_handle(handle), uri,
                                              header_name, header_value);
  environment->ReleaseStringUTFChars(grant_header_value, header_value);
  environment->ReleaseStringUTFChars(grant_header_name, header_name);
  environment->ReleaseStringUTFChars(authorized_sip_uri, uri);
  return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeSendDtmf(
    JNIEnv *environment, jclass, jlong handle, jint call_id, jstring digits) {
  if (digits == nullptr) {
    return -1;
  }
  const char *native_digits = environment->GetStringUTFChars(digits, nullptr);
  if (native_digits == nullptr) {
    return -1;
  }
  const int status =
      triplex_pjsip_send_dtmf(from_handle(handle), call_id, native_digits);
  environment->ReleaseStringUTFChars(digits, native_digits);
  return status;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeInterrupt(JNIEnv *, jclass,
                                                             jlong handle) {
  return static_cast<jlong>(triplex_pjsip_interrupt(from_handle(handle)));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeHangup(JNIEnv *, jclass,
                                                          jlong handle,
                                                          jint call_id,
                                                          jint sip_status) {
  return triplex_pjsip_hangup(from_handle(handle), call_id, sip_status);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeHandleNetworkChange(
    JNIEnv *, jclass, jlong handle) {
  return triplex_pjsip_handle_network_change(from_handle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeReregister(JNIEnv *, jclass,
                                                              jlong handle) {
  return triplex_pjsip_reregister(from_handle(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeStartProbeTone(
    JNIEnv *, jclass, jlong handle, jint frequency_hz, jint duration_ms,
    jint amplitude) {
  if (frequency_hz < 0 || duration_ms < 0 || amplitude < 0) {
    return -1;
  }
  return triplex_pjsip_start_probe_tone(
      from_handle(handle), static_cast<uint32_t>(frequency_hz),
      static_cast<uint32_t>(duration_ms), static_cast<uint16_t>(amplitude));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeStartSynthesis(
    JNIEnv *environment, jclass, jlong handle, jshortArray samples) {
  if (samples == nullptr) {
    return -1;
  }
  const jsize count = environment->GetArrayLength(samples);
  if (count <= 0) {
    return -1;
  }
  jshort *pcm = environment->GetShortArrayElements(samples, nullptr);
  if (pcm == nullptr) {
    return -1;
  }
  const int status = triplex_pjsip_start_synthesis(
      from_handle(handle), reinterpret_cast<const int16_t *>(pcm),
      static_cast<size_t>(count));
  environment->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
  return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeDrainEvents(
    JNIEnv *environment, jclass, jlong handle, jobject output_buffer) {
  auto *output = static_cast<uint8_t *>(
      environment->GetDirectBufferAddress(output_buffer));
  const jlong capacity = environment->GetDirectBufferCapacity(output_buffer);
  if (output == nullptr || capacity < static_cast<jlong>(kEventRecordBytes)) {
    return 0;
  }
  const size_t record_capacity = std::min(
      static_cast<size_t>(capacity) / kEventRecordBytes, kMaxEventBatch);
  std::array<TriplexSipEvent, kMaxEventBatch> events{};
  const size_t count = triplex_pjsip_drain_events(
      from_handle(handle), events.data(), record_capacity);
  std::memcpy(output, events.data(), count * kEventRecordBytes);
  return static_cast<jint>(count);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeMetricsSnapshot(
    JNIEnv *environment, jclass, jlong handle, jobject output_buffer) {
  auto *output = static_cast<uint8_t *>(
      environment->GetDirectBufferAddress(output_buffer));
  const jlong capacity = environment->GetDirectBufferCapacity(output_buffer);
  if (output == nullptr || capacity < static_cast<jlong>(kMetricsRecordBytes)) {
    return JNI_FALSE;
  }
  TriplexPjsipMetrics metrics{};
  if (!triplex_pjsip_metrics_snapshot(from_handle(handle), &metrics)) {
    return JNI_FALSE;
  }
  std::memcpy(output, &metrics, sizeof(metrics));
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeDrainIncomingPcm(
    JNIEnv *environment, jclass, jlong handle, jobject output_buffer) {
  auto *output = static_cast<int16_t *>(
      environment->GetDirectBufferAddress(output_buffer));
  const jlong capacity = environment->GetDirectBufferCapacity(output_buffer);
  if (output == nullptr || capacity < static_cast<jlong>(sizeof(int16_t))) {
    return 0;
  }
  return static_cast<jint>(triplex_pjsip_drain_incoming_pcm(
      from_handle(handle), output,
      static_cast<size_t>(capacity) / sizeof(int16_t)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_triplex_telephony_plivo_NativePjsip_nativeDestroy(JNIEnv *, jclass,
                                                           jlong handle) {
  triplex_pjsip_destroy(from_handle(handle));
}
