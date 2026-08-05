#include "triplex_pjsip.h"

#include "rtp_tap_transport.h"
#include "transport_drop_cleanup.h"

#include <pjmedia/conference.h>
#include <pjmedia/port.h>
#include <pjmedia/transport_srtp.h>
#include <pjsip/sip_transport_tls.h>
#include <pjsua-lib/pjsua.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <limits>
#include <new>
#include <thread>

namespace {

constexpr size_t kUsernameCapacity = 128;
constexpr size_t kPasswordCapacity = 256;
constexpr size_t kDomainCapacity = 256;
constexpr size_t kRealmCapacity = 256;
constexpr size_t kPathCapacity = 1024;
constexpr size_t kUriCapacity = 640;
constexpr size_t kEventCapacity = 64;
constexpr uint32_t kCanonicalClockRate = 16'000;
constexpr uint32_t kCanonicalSamplesPerFrame = 160;
constexpr uint32_t kCanonicalBytesPerFrame = 320;
constexpr double kPi = 3.14159265358979323846;

int64_t monotonic_raw_ns() {
  timespec value{};
#if defined(CLOCK_MONOTONIC_RAW)
  constexpr clockid_t clock_id = CLOCK_MONOTONIC_RAW;
#else
  constexpr clockid_t clock_id = CLOCK_MONOTONIC;
#endif
  if (clock_gettime(clock_id, &value) != 0) {
    return 0;
  }
  return static_cast<int64_t>(value.tv_sec) * 1'000'000'000LL + value.tv_nsec;
}

template <size_t N> class EventQueue {
public:
  static_assert(N > 0 && (N & (N - 1)) == 0);

  EventQueue() {
    for (size_t index = 0; index < N; ++index) {
      cells_[index].sequence.store(index, std::memory_order_relaxed);
    }
  }

  bool push(const TriplexSipEvent &event) {
    size_t position = enqueue_position_.load(std::memory_order_relaxed);
    for (unsigned attempt = 0; attempt < 8; ++attempt) {
      Cell &cell = cells_[position & (N - 1)];
      const size_t sequence = cell.sequence.load(std::memory_order_acquire);
      const intptr_t difference =
          static_cast<intptr_t>(sequence) - static_cast<intptr_t>(position);
      if (difference == 0 &&
          enqueue_position_.compare_exchange_weak(position, position + 1,
                                                  std::memory_order_relaxed)) {
        cell.event = event;
        cell.sequence.store(position + 1, std::memory_order_release);
        return true;
      }
      if (difference < 0) {
        return false;
      }
      position = enqueue_position_.load(std::memory_order_relaxed);
    }
    return false;
  }

  bool pop(TriplexSipEvent *event) {
    size_t position = dequeue_position_.load(std::memory_order_relaxed);
    for (unsigned attempt = 0; attempt < 8; ++attempt) {
      Cell &cell = cells_[position & (N - 1)];
      const size_t sequence = cell.sequence.load(std::memory_order_acquire);
      const intptr_t difference =
          static_cast<intptr_t>(sequence) - static_cast<intptr_t>(position + 1);
      if (difference == 0 &&
          dequeue_position_.compare_exchange_weak(position, position + 1,
                                                  std::memory_order_relaxed)) {
        *event = cell.event;
        cell.sequence.store(position + N, std::memory_order_release);
        return true;
      }
      if (difference < 0) {
        return false;
      }
      position = dequeue_position_.load(std::memory_order_relaxed);
    }
    return false;
  }

private:
  struct Cell {
    std::atomic<size_t> sequence{0};
    TriplexSipEvent event{};
  };

  alignas(64) std::array<Cell, N> cells_{};
  alignas(64) std::atomic<size_t> enqueue_position_{0};
  alignas(64) std::atomic<size_t> dequeue_position_{0};
};

struct DirectMediaPort {
  pjmedia_port base{};
  struct TriplexPjsipEngine *engine = nullptr;
  uint64_t transmit_timestamp = 0;
};

bool copy_text(char *destination, size_t capacity, const char *source) {
  if (destination == nullptr || capacity == 0 || source == nullptr) {
    return false;
  }
  const size_t length = std::strlen(source);
  if (length == 0 || length >= capacity) {
    return false;
  }
  std::memcpy(destination, source, length + 1);
  return true;
}

bool valid_dtmf(const char *digits) {
  if (digits == nullptr || *digits == '\0') {
    return false;
  }
  for (const char *cursor = digits; *cursor != '\0'; ++cursor) {
    const char value = *cursor;
    if (!((value >= '0' && value <= '9') || value == '*' || value == '#' ||
          (value >= 'A' && value <= 'D') || value == 'R')) {
      return false;
    }
  }
  return true;
}

bool valid_secure_sip_uri(const char *uri) {
  if (uri == nullptr || std::strncmp(uri, "sips:", 5) != 0) {
    return false;
  }
  const size_t length = std::strlen(uri);
  if (length < 6 || length >= kUriCapacity) {
    return false;
  }
  return std::strchr(uri, '\r') == nullptr && std::strchr(uri, '\n') == nullptr;
}

uint64_t math_stat_mean(const pj_math_stat &statistic) {
  return statistic.n == 0 ? 0
                          : static_cast<uint64_t>(std::max(statistic.mean, 0));
}

uint64_t math_stat_max(const pj_math_stat &statistic) {
  return statistic.n == 0 ? 0
                          : static_cast<uint64_t>(std::max(statistic.max, 0));
}

} // namespace

struct TriplexPjsipEngine {
  std::array<char, kUsernameCapacity> username{};
  std::array<char, kPasswordCapacity> password{};
  std::array<char, kDomainCapacity> domain{};
  std::array<char, kRealmCapacity> realm{};
  std::array<char, kPathCapacity> ca_bundle_path{};
  std::array<char, kUriCapacity> identity_uri{};
  std::array<char, kUriCapacity> registrar_uri{};
  uint16_t tls_port = 5061;
  uint16_t registration_timeout_seconds = 300;

  std::atomic<bool> start_attempted{false};
  std::atomic<bool> started{false};
  std::atomic<bool> shutting_down{false};
  std::atomic<bool> registered{false};
  std::atomic<bool> media_active{false};
  std::atomic<bool> tls_connected{false};
  std::atomic<int32_t> registration_status{0};
  std::atomic<uint32_t> tls_protocol{0};
  std::atomic<uint32_t> tls_cipher{0};
  std::atomic<uint32_t> tls_verify_status{0};
  std::atomic<int32_t> tls_last_status{0};
  std::atomic<int32_t> active_call_id{PJSUA_INVALID_ID};
  std::atomic<uint32_t> active_media_index{0};
  std::atomic<int32_t> active_call_slot{PJSUA_INVALID_ID};
  std::atomic<uint32_t> rtp_clock_rate_hz{8'000};
  std::atomic<uint32_t> last_rx_rtp_timestamp{0};
  triplex::telephony::SecureTransportLifecycle secure_transport_lifecycle;

  TriplexNativeMediaRuntime *media_runtime = nullptr;
  pj_pool_t *media_port_pool = nullptr;
  DirectMediaPort *media_port = nullptr;
  pjsua_conf_port_id media_port_slot = PJSUA_INVALID_ID;
  std::array<pjsua_transport_id, 2> tls_transport_ids{
      PJSUA_INVALID_ID,
      PJSUA_INVALID_ID,
  };
  pjsua_acc_id account_id = PJSUA_INVALID_ID;

  // PJSUA control, media, and conference callbacks may be concurrent. This
  // bounded lock-free queue never invokes JNI from a PJSIP callback.
  EventQueue<kEventCapacity> events;
  std::atomic<uint32_t> dropped_events{0};

  std::array<std::atomic<uint64_t>, 8> callback_enqueue_buckets{};
  std::atomic<uint64_t> callback_enqueue_max_ns{0};

  std::atomic<bool> capture_probe_running{false};
  std::thread capture_probe_thread;
  std::atomic<uint64_t> capture_probe_frames{0};
  std::atomic<uint64_t> capture_probe_non_silent_frames{0};
  std::atomic<uint64_t> capture_probe_checksum{1'469'598'103'934'665'603ULL};
  std::atomic<uint32_t> capture_probe_peak{0};

  std::atomic<uint32_t> tone_generation{0};
  std::thread tone_thread;

  void push_control_event(uint16_t type, uint16_t code, int32_t call_id,
                          int32_t status, int32_t operation, uint32_t value) {
    TriplexSipEvent event{};
    event.abi_version = 1;
    event.struct_size = sizeof(event);
    event.type = type;
    event.code = code;
    event.call_id = call_id;
    event.status = status;
    event.operation = operation;
    event.value = value;
    event.mono_ns = monotonic_raw_ns();
    if (!events.push(event)) {
      dropped_events.fetch_add(1, std::memory_order_relaxed);
    }
  }

  void push_dtmf_event(pjsua_call_id call_id, const pjsua_dtmf_info &info) {
    TriplexSipEvent event{};
    event.abi_version = 1;
    event.struct_size = sizeof(event);
    event.type = TRIPLEX_EVENT_DTMF;
    event.call_id = call_id;
    event.value = info.duration;
    event.mono_ns = monotonic_raw_ns();
    event.digit = static_cast<uint8_t>(info.digit);
    event.method = static_cast<uint8_t>(info.method);
    if (!events.push(event)) {
      dropped_events.fetch_add(1, std::memory_order_relaxed);
    }
  }

  void record_callback_enqueue_latency(uint64_t latency_ns) {
    static constexpr std::array<uint64_t, 7> upper_bounds = {
        250'000,   500'000,    1'000'000,  2'000'000,
        5'000'000, 10'000'000, 20'000'000,
    };
    size_t bucket = upper_bounds.size();
    for (size_t index = 0; index < upper_bounds.size(); ++index) {
      if (latency_ns <= upper_bounds[index]) {
        bucket = index;
        break;
      }
    }
    callback_enqueue_buckets[bucket].fetch_add(1, std::memory_order_relaxed);
    uint64_t current_max =
        callback_enqueue_max_ns.load(std::memory_order_relaxed);
    while (latency_ns > current_max &&
           !callback_enqueue_max_ns.compare_exchange_weak(
               current_max, latency_ns, std::memory_order_relaxed)) {
    }
  }

  void start_capture_probe() {
    capture_probe_running.store(true, std::memory_order_release);
    capture_probe_thread = std::thread([this] {
      std::array<int16_t, TRIPLEX_FRAME_SAMPLES> samples{};
      TriplexFrameHeader header{};
      uint64_t checksum =
          capture_probe_checksum.load(std::memory_order_relaxed);
      while (capture_probe_running.load(std::memory_order_acquire)) {
        bool drained = false;
        while (triplex_media_capture_pop(media_runtime, samples.data(),
                                         samples.size(), &header) != 0) {
          drained = true;
          uint32_t peak = 0;
          for (size_t index = 0; index < header.len; ++index) {
            const int32_t value = samples[index];
            const uint32_t magnitude = static_cast<uint32_t>(
                value == std::numeric_limits<int16_t>::min()
                    ? std::numeric_limits<int16_t>::max()
                    : std::abs(value));
            peak = std::max(peak, magnitude);
            checksum ^= static_cast<uint16_t>(samples[index]);
            checksum *= 1'099'511'628'211ULL;
          }
          capture_probe_frames.fetch_add(1, std::memory_order_relaxed);
          if (peak != 0) {
            capture_probe_non_silent_frames.fetch_add(
                1, std::memory_order_relaxed);
          }
          uint32_t observed_peak =
              capture_probe_peak.load(std::memory_order_relaxed);
          while (peak > observed_peak &&
                 !capture_probe_peak.compare_exchange_weak(
                     observed_peak, peak, std::memory_order_relaxed)) {
          }
        }
        capture_probe_checksum.store(checksum, std::memory_order_relaxed);
        if (!drained) {
          std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
      }
    });
  }

  void stop_workers() {
    capture_probe_running.store(false, std::memory_order_release);
    tone_generation.fetch_add(1, std::memory_order_acq_rel);
    if (capture_probe_thread.joinable()) {
      capture_probe_thread.join();
    }
    if (tone_thread.joinable()) {
      tone_thread.join();
    }
  }
};

namespace {

std::atomic<TriplexPjsipEngine *> global_engine{nullptr};

TriplexPjsipEngine *engine_for_callback() {
  TriplexPjsipEngine *engine = global_engine.load(std::memory_order_acquire);
  if (engine == nullptr ||
      engine->shutting_down.load(std::memory_order_acquire)) {
    return nullptr;
  }
  return engine;
}

pj_status_t media_port_get_frame(pjmedia_port *port, pjmedia_frame *frame) {
  auto *direct_port = reinterpret_cast<DirectMediaPort *>(port);
  TriplexPjsipEngine *engine = direct_port->engine;
  if (engine == nullptr || frame == nullptr || frame->buf == nullptr ||
      frame->size < kCanonicalBytesPerFrame) {
    return PJ_EINVAL;
  }

  TriplexFrameHeader header{};
  const size_t samples = triplex_media_synth_pop(
      engine->media_runtime, static_cast<int16_t *>(frame->buf),
      kCanonicalSamplesPerFrame, &header, monotonic_raw_ns());
  if (samples != kCanonicalSamplesPerFrame) {
    return PJ_EUNKNOWN;
  }
  frame->type = PJMEDIA_FRAME_TYPE_AUDIO;
  frame->size = kCanonicalBytesPerFrame;
  frame->timestamp.u64 = direct_port->transmit_timestamp;
  direct_port->transmit_timestamp += kCanonicalSamplesPerFrame;
  frame->bit_info = 0;
  return PJ_SUCCESS;
}

pj_status_t media_port_put_frame(pjmedia_port *port, pjmedia_frame *frame) {
  auto *direct_port = reinterpret_cast<DirectMediaPort *>(port);
  TriplexPjsipEngine *engine = direct_port->engine;
  if (engine == nullptr || frame == nullptr || frame->buf == nullptr ||
      frame->type != PJMEDIA_FRAME_TYPE_AUDIO ||
      frame->size != kCanonicalBytesPerFrame) {
    return PJ_EINVAL;
  }

  const int64_t callback_mono_ns = monotonic_raw_ns();
  const bool accepted = triplex_media_capture_push(
      engine->media_runtime, static_cast<const int16_t *>(frame->buf),
      kCanonicalSamplesPerFrame, callback_mono_ns,
      engine->last_rx_rtp_timestamp.load(std::memory_order_relaxed));
  const int64_t enqueue_done_ns = monotonic_raw_ns();
  if (enqueue_done_ns >= callback_mono_ns) {
    engine->record_callback_enqueue_latency(
        static_cast<uint64_t>(enqueue_done_ns - callback_mono_ns));
  }
  // Capture overload is reported by native-media metrics. The conference
  // bridge must keep its clock and the opposite direction alive.
  PJ_UNUSED_ARG(accepted);
  return PJ_SUCCESS;
}

void on_registration_state(pjsua_acc_id account_id, pjsua_reg_info *info) {
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr) {
    return;
  }
  int32_t status = PJ_EUNKNOWN;
  uint16_t code = 0;
  if (info != nullptr && info->cbparam != nullptr) {
    status = info->cbparam->status;
    code = static_cast<uint16_t>(info->cbparam->code);
  }
  pjsua_acc_info account_info{};
  if (pjsua_acc_get_info(account_id, &account_info) == PJ_SUCCESS) {
    code = static_cast<uint16_t>(account_info.status);
    engine->registered.store(account_info.status == PJSIP_SC_OK,
                             std::memory_order_release);
  }
  engine->registration_status.store(code, std::memory_order_release);
  engine->push_control_event(TRIPLEX_EVENT_REGISTRATION, code, PJSUA_INVALID_ID,
                             status, 0, 0);
}

void on_incoming_call(pjsua_acc_id account_id, pjsua_call_id call_id,
                      pjsip_rx_data *request) {
  PJ_UNUSED_ARG(account_id);
  PJ_UNUSED_ARG(request);
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr) {
    return;
  }
  engine->active_call_id.store(call_id, std::memory_order_release);
  engine->push_control_event(TRIPLEX_EVENT_INCOMING_CALL, 0, call_id,
                             PJ_SUCCESS, 0, 0);
}

void disconnect_media(TriplexPjsipEngine *engine) {
  const int32_t call_slot = engine->active_call_slot.exchange(
      PJSUA_INVALID_ID, std::memory_order_acq_rel);
  if (call_slot != PJSUA_INVALID_ID &&
      engine->media_port_slot != PJSUA_INVALID_ID) {
    pjsua_conf_disconnect(call_slot, engine->media_port_slot);
    pjsua_conf_disconnect(engine->media_port_slot, call_slot);
  }
  engine->media_active.store(false, std::memory_order_release);
}

class EngineAbruptDropDriver final
    : public triplex::telephony::AbruptDropDriver {
public:
  explicit EngineAbruptDropDriver(TriplexPjsipEngine *engine)
      : engine_(engine) {}

  void flush_stale_synthesis() noexcept override {
    engine_->tone_generation.fetch_add(1, std::memory_order_acq_rel);
    triplex_media_interrupt(engine_->media_runtime);
  }

  void disconnect_rtp_media() noexcept override { disconnect_media(engine_); }

  void invalidate_registration() noexcept override {
    engine_->registered.store(false, std::memory_order_release);
  }

private:
  TriplexPjsipEngine *engine_;
};

void on_call_state(pjsua_call_id call_id, pjsip_event *event) {
  PJ_UNUSED_ARG(event);
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr) {
    return;
  }
  pjsua_call_info call_info{};
  if (pjsua_call_get_info(call_id, &call_info) != PJ_SUCCESS) {
    return;
  }
  engine->push_control_event(TRIPLEX_EVENT_CALL_STATE,
                             static_cast<uint16_t>(call_info.state), call_id,
                             call_info.last_status, 0, 0);
  if (call_info.state == PJSIP_INV_STATE_DISCONNECTED) {
    disconnect_media(engine);
    engine->active_call_id.store(PJSUA_INVALID_ID, std::memory_order_release);
  }
}

void on_call_media_state(pjsua_call_id call_id) {
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr || engine->media_port_slot == PJSUA_INVALID_ID) {
    return;
  }
  pjsua_call_info call_info{};
  if (pjsua_call_get_info(call_id, &call_info) != PJ_SUCCESS) {
    return;
  }

  disconnect_media(engine);
  for (unsigned index = 0; index < call_info.media_cnt; ++index) {
    const pjsua_call_media_info &media = call_info.media[index];
    if (media.type != PJMEDIA_TYPE_AUDIO ||
        media.status != PJSUA_CALL_MEDIA_ACTIVE) {
      continue;
    }

    const pjsua_conf_port_id call_slot = media.stream.aud.conf_slot;
    const pj_status_t capture_status =
        pjsua_conf_connect(call_slot, engine->media_port_slot);
    const pj_status_t synthesis_status =
        pjsua_conf_connect(engine->media_port_slot, call_slot);
    if (capture_status == PJ_SUCCESS && synthesis_status == PJ_SUCCESS) {
      engine->active_media_index.store(index, std::memory_order_release);
      engine->active_call_slot.store(call_slot, std::memory_order_release);
      engine->media_active.store(true, std::memory_order_release);

      pjsua_stream_info stream_info{};
      if (pjsua_call_get_stream_info(call_id, index, &stream_info) ==
          PJ_SUCCESS) {
        engine->rtp_clock_rate_hz.store(stream_info.info.aud.fmt.clock_rate,
                                        std::memory_order_release);
      }
    }
    engine->push_control_event(
        TRIPLEX_EVENT_MEDIA_STATE, static_cast<uint16_t>(media.status), call_id,
        capture_status == PJ_SUCCESS ? synthesis_status : capture_status, 0,
        index);
    return;
  }

  engine->push_control_event(TRIPLEX_EVENT_MEDIA_STATE,
                             static_cast<uint16_t>(call_info.media_status),
                             call_id, PJ_ENOTFOUND, 0, 0);
}

void on_dtmf(pjsua_call_id call_id, const pjsua_dtmf_info *info) {
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine != nullptr && info != nullptr) {
    engine->push_dtmf_event(call_id, *info);
  }
}

void on_ip_change(pjsua_ip_change_op operation, pj_status_t status,
                  const pjsua_ip_change_op_info *info) {
  PJ_UNUSED_ARG(info);
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine != nullptr) {
    engine->push_control_event(
        TRIPLEX_EVENT_IP_CHANGE, 0,
        engine->active_call_id.load(std::memory_order_relaxed), status,
        operation, 0);
  }
}

void on_transport_state(pjsip_transport *transport, pjsip_transport_state state,
                        const pjsip_transport_state_info *info) {
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr || transport == nullptr ||
      !PJSIP_TRANSPORT_IS_SECURE(transport)) {
    return;
  }

  const pj_status_t status = info == nullptr ? PJ_EUNKNOWN : info->status;
  uint32_t protocol = engine->tls_protocol.load(std::memory_order_relaxed);
  uint32_t cipher = engine->tls_cipher.load(std::memory_order_relaxed);
  uint32_t verify_status =
      engine->tls_verify_status.load(std::memory_order_relaxed);
  bool established = false;
  if (info != nullptr && info->ext_info != nullptr) {
    const auto *tls_state =
        static_cast<const pjsip_tls_state_info *>(info->ext_info);
    if (tls_state->ssl_sock_info != nullptr) {
      const pj_ssl_sock_info &ssl = *tls_state->ssl_sock_info;
      established =
          state == PJSIP_TP_STATE_CONNECTED && ssl.established == PJ_TRUE;
      protocol = ssl.proto;
      cipher = ssl.cipher;
      verify_status = ssl.verify_status;
    }
  }
  const uintptr_t transport_token = reinterpret_cast<uintptr_t>(transport);
  bool lifecycle_state_changed = false;
  if (established) {
    engine->secure_transport_lifecycle.on_connected(transport_token);
    lifecycle_state_changed = true;
  } else {
    EngineAbruptDropDriver cleanup_driver(engine);
    lifecycle_state_changed = engine->secure_transport_lifecycle.on_abrupt_drop(
        transport_token, cleanup_driver);
  }
  // A late destroy notification for the old Wi-Fi socket must not overwrite
  // the TLS evidence belonging to an already-connected cellular replacement.
  if (lifecycle_state_changed) {
    engine->tls_last_status.store(status, std::memory_order_release);
    engine->tls_protocol.store(protocol, std::memory_order_release);
    engine->tls_cipher.store(cipher, std::memory_order_release);
    engine->tls_verify_status.store(verify_status, std::memory_order_release);
  }
  engine->tls_connected.store(
      engine->secure_transport_lifecycle.has_connected_transport(),
      std::memory_order_release);
  engine->push_control_event(
      TRIPLEX_EVENT_TRANSPORT_STATE, static_cast<uint16_t>(state),
      PJSUA_INVALID_ID, status, static_cast<int32_t>(protocol), verify_status);
}

void on_conference_operation(const pjmedia_conf_op_info *info) {
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine != nullptr && info != nullptr) {
    engine->push_control_event(
        TRIPLEX_EVENT_CONFERENCE_OPERATION, 0,
        engine->active_call_id.load(std::memory_order_relaxed), info->status,
        info->op_type, 0);
  }
}

pjmedia_transport *on_create_media_transport(pjsua_call_id call_id,
                                             unsigned media_index,
                                             pjmedia_transport *base_transport,
                                             unsigned flags) {
  PJ_UNUSED_ARG(call_id);
  PJ_UNUSED_ARG(media_index);
  TriplexPjsipEngine *engine = engine_for_callback();
  if (engine == nullptr) {
    return nullptr;
  }
  pjmedia_transport *tap = nullptr;
  const pj_status_t status = triplex_rtp_tap_create(
      pjsua_get_pjmedia_endpt(), base_transport,
      (flags & PJSUA_MED_TP_CLOSE_MEMBER) != 0, engine->media_runtime,
      &engine->rtp_clock_rate_hz, &engine->last_rx_rtp_timestamp, &tap);
  return status == PJ_SUCCESS ? tap : nullptr;
}

bool configure_identity(TriplexPjsipEngine *engine,
                        const TriplexSipConfig *config) {
  if (!copy_text(engine->username.data(), engine->username.size(),
                 config->username) ||
      !copy_text(engine->password.data(), engine->password.size(),
                 config->password) ||
      !copy_text(engine->domain.data(), engine->domain.size(),
                 config->domain) ||
      !copy_text(engine->ca_bundle_path.data(), engine->ca_bundle_path.size(),
                 config->ca_bundle_path)) {
    return false;
  }
  if (config->realm != nullptr && config->realm[0] != '\0') {
    if (!copy_text(engine->realm.data(), engine->realm.size(), config->realm)) {
      return false;
    }
  } else if (!copy_text(engine->realm.data(), engine->realm.size(),
                        config->domain)) {
    return false;
  }
  engine->tls_port = config->tls_port == 0 ? 5061 : config->tls_port;
  engine->registration_timeout_seconds =
      config->registration_timeout_seconds == 0
          ? 300
          : config->registration_timeout_seconds;

  const int identity_length =
      std::snprintf(engine->identity_uri.data(), engine->identity_uri.size(),
                    "sip:%s@%s;transport=tls", engine->username.data(),
                    engine->domain.data());
  const int registrar_length = std::snprintf(
      engine->registrar_uri.data(), engine->registrar_uri.size(),
      "sips:%s:%u;transport=tls", engine->domain.data(), engine->tls_port);
  return identity_length > 0 &&
         static_cast<size_t>(identity_length) < engine->identity_uri.size() &&
         registrar_length > 0 &&
         static_cast<size_t>(registrar_length) < engine->registrar_uri.size();
}

pj_status_t initialize_library(TriplexPjsipEngine *engine) {
  pj_status_t status = pjsua_create();
  if (status != PJ_SUCCESS) {
    return status;
  }

  pjsua_config user_agent_config{};
  pjsua_config_default(&user_agent_config);
  user_agent_config.max_calls = 1;
  user_agent_config.thread_cnt = 1;
  user_agent_config.use_srtp = PJMEDIA_SRTP_MANDATORY;
  user_agent_config.srtp_secure_signaling = 1;
  user_agent_config.user_agent =
      pj_str(const_cast<char *>("Triplex-PJSIP/0.1"));
  user_agent_config.cb.on_reg_state2 = &on_registration_state;
  user_agent_config.cb.on_incoming_call = &on_incoming_call;
  user_agent_config.cb.on_call_state = &on_call_state;
  user_agent_config.cb.on_call_media_state = &on_call_media_state;
  user_agent_config.cb.on_dtmf_digit2 = &on_dtmf;
  user_agent_config.cb.on_ip_change_progress = &on_ip_change;
  user_agent_config.cb.on_transport_state = &on_transport_state;
  user_agent_config.cb.on_conf_op_completed = &on_conference_operation;
  user_agent_config.cb.on_create_media_transport = &on_create_media_transport;

  pjsua_logging_config logging_config{};
  pjsua_logging_config_default(&logging_config);
  logging_config.msg_logging = PJ_FALSE;
  logging_config.level = 2;
  logging_config.console_level = 0;

  pjsua_media_config media_config{};
  pjsua_media_config_default(&media_config);
  media_config.clock_rate = kCanonicalClockRate;
  media_config.snd_clock_rate = kCanonicalClockRate;
  media_config.channel_count = 1;
  media_config.audio_frame_ptime = 10;
  media_config.thread_cnt = 1;
  media_config.has_ioqueue = PJ_TRUE;
  media_config.ec_tail_len = 0;
  media_config.no_vad = PJ_TRUE;
  media_config.ptime = 10;
  media_config.jb_init = 20;
  media_config.jb_min_pre = 20;
  media_config.jb_max_pre = 60;
  media_config.jb_max = 200;

  status = pjsua_init(&user_agent_config, &logging_config, &media_config);
  if (status != PJ_SUCCESS) {
    return status;
  }

  pjsua_transport_config transport_config{};
  pjsua_transport_config_default(&transport_config);
  transport_config.port = 0;
  transport_config.tls_setting.verify_server = PJ_TRUE;
  transport_config.tls_setting.verify_client = PJ_FALSE;
  transport_config.tls_setting.ca_list_file =
      pj_str(engine->ca_bundle_path.data());
  transport_config.tls_setting.proto =
      PJ_SSL_SOCK_PROTO_TLS1_2 | PJ_SSL_SOCK_PROTO_TLS1_3;
  transport_config.tls_setting.timeout.sec = 5;
  transport_config.tls_setting.timeout.msec = 0;
  status = pjsua_transport_create(PJSIP_TRANSPORT_TLS, &transport_config,
                                  &engine->tls_transport_ids[0]);
  if (status != PJ_SUCCESS) {
    return status;
  }
  return pjsua_transport_create(PJSIP_TRANSPORT_TLS6, &transport_config,
                                &engine->tls_transport_ids[1]);
}

pj_status_t create_media_port(TriplexPjsipEngine *engine) {
  engine->media_port_pool =
      pjsua_pool_create("triplex-direct-media", 2048, 1024);
  if (engine->media_port_pool == nullptr) {
    return PJ_ENOMEM;
  }
  engine->media_port =
      PJ_POOL_ZALLOC_T(engine->media_port_pool, DirectMediaPort);
  engine->media_port->engine = engine;
  pj_str_t name = pj_str(const_cast<char *>("triplex-direct-media"));
  pj_status_t status = pjmedia_port_info_init(
      &engine->media_port->base.info, &name,
      PJMEDIA_SIG_CLASS_PORT_AUD('t', 'x'), kCanonicalClockRate, 1, 16,
      kCanonicalSamplesPerFrame);
  if (status != PJ_SUCCESS) {
    return status;
  }
  engine->media_port->base.get_frame = &media_port_get_frame;
  engine->media_port->base.put_frame = &media_port_put_frame;
  return pjsua_conf_add_port(engine->media_port_pool, &engine->media_port->base,
                             &engine->media_port_slot);
}

pj_status_t add_account(TriplexPjsipEngine *engine) {
  pjsua_acc_config account_config{};
  pjsua_acc_config_default(&account_config);
  account_config.id = pj_str(engine->identity_uri.data());
  account_config.reg_uri = pj_str(engine->registrar_uri.data());
  account_config.reg_timeout = engine->registration_timeout_seconds;
  account_config.reg_first_retry_interval = 1;
  account_config.reg_retry_interval = 5;
  account_config.reg_retry_random_interval = 1;
  account_config.drop_calls_on_reg_fail = PJ_FALSE;
  account_config.disable_reg_on_modify = PJ_TRUE;
  account_config.ip_change_cfg.shutdown_tp = PJ_TRUE;
  account_config.ip_change_cfg.hangup_calls = PJ_FALSE;
  account_config.ip_change_cfg.reinv_use_update = PJ_TRUE;
  account_config.ipv6_sip_use = PJSUA_IPV6_ENABLED_PREFER_IPV4;
  account_config.ipv6_media_use = PJSUA_IPV6_ENABLED_PREFER_IPV4;
  account_config.nat64_opt = PJSUA_NAT64_ENABLED;
  account_config.use_srtp = PJMEDIA_SRTP_MANDATORY;
  account_config.srtp_secure_signaling = 1;
  account_config.cred_count = 1;
  account_config.cred_info[0].realm = pj_str(engine->realm.data());
  account_config.cred_info[0].scheme = pj_str(const_cast<char *>("digest"));
  account_config.cred_info[0].username = pj_str(engine->username.data());
  account_config.cred_info[0].data_type = PJSIP_CRED_DATA_PLAIN_PASSWD;
  account_config.cred_info[0].data = pj_str(engine->password.data());

  return pjsua_acc_add(&account_config, PJ_TRUE, &engine->account_id);
}

void prioritize_pstn_codecs() {
  pjsua_codec_info codecs[64]{};
  unsigned count = std::size(codecs);
  if (pjsua_enum_codecs(codecs, &count) != PJ_SUCCESS) {
    return;
  }
  for (unsigned index = 0; index < count; ++index) {
    pjsua_codec_set_priority(&codecs[index].codec_id,
                             PJMEDIA_CODEC_PRIO_DISABLED);
  }
  pj_str_t pcmu = pj_str(const_cast<char *>("PCMU/8000"));
  pj_str_t pcma = pj_str(const_cast<char *>("PCMA/8000"));
  pjsua_codec_set_priority(&pcmu, PJMEDIA_CODEC_PRIO_HIGHEST);
  pjsua_codec_set_priority(&pcma, PJMEDIA_CODEC_PRIO_NEXT_HIGHER);
}

void secure_zero(std::array<char, kPasswordCapacity> *password) {
  volatile char *cursor = password->data();
  for (size_t index = 0; index < password->size(); ++index) {
    cursor[index] = 0;
  }
}

int effective_call_id(TriplexPjsipEngine *engine, int32_t requested) {
  return requested == PJSUA_INVALID_ID
             ? engine->active_call_id.load(std::memory_order_acquire)
             : requested;
}

void ensure_pj_thread_registered() {
  if (pj_thread_is_registered()) {
    return;
  }
  thread_local pj_thread_desc description{};
  thread_local pj_thread_t *thread = nullptr;
  pj_thread_register("triplex-control", description, &thread);
}

} // namespace

extern "C" TriplexPjsipEngine *
triplex_pjsip_create(const TriplexSipConfig *config) {
  if (config == nullptr) {
    return nullptr;
  }
  auto *engine = new (std::nothrow) TriplexPjsipEngine();
  if (engine == nullptr || !configure_identity(engine, config)) {
    delete engine;
    return nullptr;
  }
  engine->media_runtime = triplex_media_create(TRIPLEX_ROUTE_DIRECT_PJSIP);
  if (engine->media_runtime == nullptr) {
    secure_zero(&engine->password);
    delete engine;
    return nullptr;
  }

  TriplexPjsipEngine *expected = nullptr;
  if (!global_engine.compare_exchange_strong(expected, engine,
                                             std::memory_order_acq_rel)) {
    triplex_media_destroy(engine->media_runtime);
    secure_zero(&engine->password);
    delete engine;
    return nullptr;
  }

  const pj_status_t status = initialize_library(engine);
  if (status != PJ_SUCCESS) {
    engine->shutting_down.store(true, std::memory_order_release);
    pjsua_destroy();
    global_engine.store(nullptr, std::memory_order_release);
    triplex_media_destroy(engine->media_runtime);
    secure_zero(&engine->password);
    delete engine;
    return nullptr;
  }
  return engine;
}

extern "C" int triplex_pjsip_start(TriplexPjsipEngine *engine) {
  if (engine == nullptr ||
      engine != global_engine.load(std::memory_order_acquire) ||
      engine->start_attempted.exchange(true, std::memory_order_acq_rel)) {
    return PJ_EINVALIDOP;
  }
  pj_status_t status = pjsua_start();
  if (status == PJ_SUCCESS) {
    status = pjsua_set_null_snd_dev();
  }
  if (status == PJ_SUCCESS) {
    status = create_media_port(engine);
  }
  if (status == PJ_SUCCESS) {
    prioritize_pstn_codecs();
    status = add_account(engine);
  }
  if (status == PJ_SUCCESS) {
    engine->start_capture_probe();
    engine->started.store(true, std::memory_order_release);
  }
  return status;
}

extern "C" int triplex_pjsip_answer(TriplexPjsipEngine *engine,
                                    int32_t call_id) {
  if (engine == nullptr) {
    return PJ_EINVAL;
  }
  ensure_pj_thread_registered();
  return pjsua_call_answer(effective_call_id(engine, call_id), PJSIP_SC_OK,
                           nullptr, nullptr);
}

extern "C" int triplex_pjsip_make_call(TriplexPjsipEngine *engine,
                                       const char *authorized_sip_uri) {
  if (engine == nullptr || !valid_secure_sip_uri(authorized_sip_uri)) {
    return PJ_EINVAL;
  }
  ensure_pj_thread_registered();
  pj_str_t destination = pj_str(const_cast<char *>(authorized_sip_uri));
  pjsua_call_id call_id = PJSUA_INVALID_ID;
  const pj_status_t status = pjsua_call_make_call(
      engine->account_id, &destination, 0, nullptr, nullptr, &call_id);
  if (status == PJ_SUCCESS) {
    engine->active_call_id.store(call_id, std::memory_order_release);
  }
  return status;
}

extern "C" int triplex_pjsip_send_dtmf(TriplexPjsipEngine *engine,
                                       int32_t call_id, const char *digits) {
  if (engine == nullptr || !valid_dtmf(digits)) {
    return PJ_EINVAL;
  }
  ensure_pj_thread_registered();
  pjsua_call_send_dtmf_param parameter{};
  pjsua_call_send_dtmf_param_default(&parameter);
  parameter.method = PJSUA_DTMF_METHOD_RFC2833;
  parameter.digits = pj_str(const_cast<char *>(digits));
  return pjsua_call_send_dtmf(effective_call_id(engine, call_id), &parameter);
}

extern "C" uint32_t triplex_pjsip_interrupt(TriplexPjsipEngine *engine) {
  if (engine == nullptr) {
    return 0;
  }
  engine->tone_generation.fetch_add(1, std::memory_order_acq_rel);
  return triplex_media_interrupt(engine->media_runtime);
}

extern "C" int triplex_pjsip_hangup(TriplexPjsipEngine *engine, int32_t call_id,
                                    int32_t sip_status) {
  if (engine == nullptr) {
    return PJ_EINVAL;
  }
  triplex_pjsip_interrupt(engine);
  ensure_pj_thread_registered();
  const unsigned status = sip_status >= 300 && sip_status <= 699
                              ? static_cast<unsigned>(sip_status)
                              : 0;
  return pjsua_call_hangup(effective_call_id(engine, call_id), status, nullptr,
                           nullptr);
}

extern "C" int triplex_pjsip_handle_network_change(TriplexPjsipEngine *engine) {
  if (engine == nullptr || !engine->started.load(std::memory_order_acquire)) {
    return PJ_EINVAL;
  }
  ensure_pj_thread_registered();
  pjsua_ip_change_param parameter{};
  pjsua_ip_change_param_default(&parameter);
  parameter.shutdown_transport = PJ_TRUE;
  parameter.restart_listener = PJ_TRUE;
  return pjsua_handle_ip_change(&parameter);
}

extern "C" int triplex_pjsip_reregister(TriplexPjsipEngine *engine) {
  if (engine == nullptr || engine->account_id == PJSUA_INVALID_ID) {
    return PJ_EINVAL;
  }
  ensure_pj_thread_registered();
  return pjsua_acc_set_registration(engine->account_id, PJ_TRUE);
}

extern "C" int triplex_pjsip_start_probe_tone(TriplexPjsipEngine *engine,
                                              uint32_t frequency_hz,
                                              uint32_t duration_ms,
                                              uint16_t amplitude) {
  if (engine == nullptr || frequency_hz < 100 || frequency_hz > 4'000 ||
      duration_ms < 10 || duration_ms > 2'000 || amplitude == 0 ||
      amplitude > static_cast<uint16_t>(std::numeric_limits<int16_t>::max())) {
    return PJ_EINVAL;
  }
  const uint32_t generation =
      engine->tone_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
  if (engine->tone_thread.joinable()) {
    engine->tone_thread.join();
  }
  const uint32_t epoch = triplex_media_epoch(engine->media_runtime);
  engine->tone_thread = std::thread([engine, generation, epoch, frequency_hz,
                                     duration_ms, amplitude] {
    std::array<int16_t, TRIPLEX_FRAME_SAMPLES> frame{};
    const uint32_t frame_count = (duration_ms + 9) / 10;
    double phase = 0.0;
    const double phase_step = (2.0 * kPi * static_cast<double>(frequency_hz)) /
                              static_cast<double>(kCanonicalClockRate);
    auto next_deadline = std::chrono::steady_clock::now();
    for (uint32_t frame_index = 0; frame_index < frame_count; ++frame_index) {
      if (engine->tone_generation.load(std::memory_order_acquire) !=
              generation ||
          triplex_media_epoch(engine->media_runtime) != epoch) {
        return;
      }
      for (int16_t &sample : frame) {
        sample = static_cast<int16_t>(std::sin(phase) *
                                      static_cast<double>(amplitude));
        phase += phase_step;
        if (phase >= 2.0 * kPi) {
          phase -= 2.0 * kPi;
        }
      }
      next_deadline += std::chrono::milliseconds(10);
      const uint16_t flags =
          frame_index + 1 == frame_count ? TRIPLEX_FLAG_FINAL_CHUNK : 0;
      if (!triplex_media_synth_push(engine->media_runtime, frame.data(),
                                    frame.size(), monotonic_raw_ns(), epoch,
                                    flags, TRIPLEX_STREAM_SYNTH)) {
        return;
      }
      std::this_thread::sleep_until(next_deadline);
    }
  });
  return PJ_SUCCESS;
}

extern "C" size_t triplex_pjsip_drain_events(TriplexPjsipEngine *engine,
                                             TriplexSipEvent *events,
                                             size_t capacity) {
  if (engine == nullptr || events == nullptr) {
    return 0;
  }
  size_t count = 0;
  while (count < capacity && engine->events.pop(&events[count])) {
    ++count;
  }
  return count;
}

extern "C" bool triplex_pjsip_metrics_snapshot(TriplexPjsipEngine *engine,
                                               TriplexPjsipMetrics *output) {
  if (engine == nullptr || output == nullptr) {
    return false;
  }
  ensure_pj_thread_registered();
  *output = TriplexPjsipMetrics{};
  output->abi_version = 1;
  output->struct_size = sizeof(*output);
  output->registered = engine->registered.load(std::memory_order_acquire);
  output->media_active = engine->media_active.load(std::memory_order_acquire);
  output->tls_connected = engine->tls_connected.load(std::memory_order_acquire);
  output->registration_status =
      engine->registration_status.load(std::memory_order_acquire);
  output->active_call_id =
      engine->active_call_id.load(std::memory_order_acquire);
  output->tls_protocol = engine->tls_protocol.load(std::memory_order_acquire);
  output->tls_cipher = engine->tls_cipher.load(std::memory_order_acquire);
  output->tls_verify_status =
      engine->tls_verify_status.load(std::memory_order_acquire);
  output->tls_last_status =
      engine->tls_last_status.load(std::memory_order_acquire);
  output->codec_clock_rate_hz =
      engine->rtp_clock_rate_hz.load(std::memory_order_acquire);
  output->capture_probe_frames =
      engine->capture_probe_frames.load(std::memory_order_acquire);
  output->capture_probe_non_silent_frames =
      engine->capture_probe_non_silent_frames.load(std::memory_order_acquire);
  output->capture_probe_checksum =
      engine->capture_probe_checksum.load(std::memory_order_acquire);
  output->capture_probe_peak =
      engine->capture_probe_peak.load(std::memory_order_acquire);
  output->dropped_control_events =
      engine->dropped_events.load(std::memory_order_acquire);
  output->callback_to_enqueue_max_ns =
      engine->callback_enqueue_max_ns.load(std::memory_order_acquire);
  for (size_t index = 0; index < engine->callback_enqueue_buckets.size();
       ++index) {
    output->callback_to_enqueue_buckets[index] =
        engine->callback_enqueue_buckets[index].load(std::memory_order_acquire);
  }
  triplex_media_metrics_snapshot(engine->media_runtime, &output->media);

  const int32_t call_id = output->active_call_id;
  if (call_id == PJSUA_INVALID_ID || !pjsua_call_is_active(call_id)) {
    return true;
  }
  const unsigned media_index =
      engine->active_media_index.load(std::memory_order_acquire);
  pjsua_stream_info stream_info{};
  if (pjsua_call_get_stream_info(call_id, media_index, &stream_info) ==
      PJ_SUCCESS) {
    output->codec_clock_rate_hz = stream_info.info.aud.fmt.clock_rate;
    output->codec_channel_count = stream_info.info.aud.fmt.channel_cnt;
    output->srtp_active = PJMEDIA_TP_PROTO_HAS_FLAG(stream_info.info.aud.proto,
                                                    PJMEDIA_TP_PROFILE_SRTP);
  }

  pjsua_stream_stat stream_stat{};
  if (pjsua_call_get_stream_stat(call_id, media_index, &stream_stat) ==
      PJ_SUCCESS) {
    output->rtcp_rx_packets = stream_stat.rtcp.rx.pkt;
    output->rtcp_rx_lost = stream_stat.rtcp.rx.loss;
    output->rtcp_rx_discarded = stream_stat.rtcp.rx.discard;
    output->rtcp_rx_reordered = stream_stat.rtcp.rx.reorder;
    output->rtcp_rx_duplicates = stream_stat.rtcp.rx.dup;
    output->rtcp_rx_jitter_mean_us = math_stat_mean(stream_stat.rtcp.rx.jitter);
    output->rtcp_rx_jitter_max_us = math_stat_max(stream_stat.rtcp.rx.jitter);
    output->rtcp_rtt_mean_us = math_stat_mean(stream_stat.rtcp.rtt);
    output->jitter_buffer_frames = stream_stat.jbuf.size;
    output->jitter_buffer_average_delay_ms = stream_stat.jbuf.avg_delay;
    output->jitter_buffer_max_delay_ms = stream_stat.jbuf.max_delay;
    output->jitter_buffer_lost_frames = stream_stat.jbuf.lost;
    output->jitter_buffer_discarded_frames = stream_stat.jbuf.discard;
  }

  pjmedia_transport_info transport_info{};
  pjmedia_transport_info_init(&transport_info);
  if (pjsua_call_get_med_transport_info(call_id, media_index,
                                        &transport_info) == PJ_SUCCESS) {
    pj_sockaddr_print(&transport_info.src_rtp_name, output->source_rtp_address,
                      sizeof(output->source_rtp_address), 3);
    for (unsigned index = 0; index < transport_info.specific_info_cnt;
         ++index) {
      const pjmedia_transport_specific_info &specific =
          transport_info.spc_info[index];
      if (specific.type == PJMEDIA_TRANSPORT_TYPE_SRTP &&
          specific.cbsize >= static_cast<int>(sizeof(pjmedia_srtp_info))) {
        pjmedia_srtp_info srtp{};
        std::memcpy(&srtp, specific.buffer, sizeof(srtp));
        output->srtp_active = srtp.active == PJ_TRUE;
      }
    }
  }
  return true;
}

extern "C" void triplex_pjsip_destroy(TriplexPjsipEngine *engine) {
  if (engine == nullptr) {
    return;
  }
  engine->shutting_down.store(true, std::memory_order_release);
  engine->stop_workers();
  ensure_pj_thread_registered();
  disconnect_media(engine);
  if (engine->account_id != PJSUA_INVALID_ID) {
    pjsua_acc_set_registration(engine->account_id, PJ_FALSE);
  }
  if (engine->media_port_slot != PJSUA_INVALID_ID) {
    pjsua_conf_remove_port(engine->media_port_slot);
    engine->media_port_slot = PJSUA_INVALID_ID;
  }
  pjsua_destroy();
  global_engine.store(nullptr, std::memory_order_release);
  triplex_media_destroy(engine->media_runtime);
  secure_zero(&engine->password);
  delete engine;
}
