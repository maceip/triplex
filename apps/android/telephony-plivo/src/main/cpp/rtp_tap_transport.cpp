#include "rtp_tap_transport.h"

#include <pj/assert.h>
#include <pj/lock.h>
#include <pj/pool.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <ctime>

namespace {

struct RtpTapTransport {
  pjmedia_transport base;
  pjmedia_transport *child;
  pj_pool_t *pool;
  pj_bool_t close_child;
  TriplexNativeMediaRuntime *media_runtime;
  std::atomic<uint32_t> *rtp_clock_rate_hz;
  std::atomic<uint32_t> *last_rx_rtp_timestamp;

  void *stream_user_data;
  void *stream_ref;
  void (*stream_rtp_callback)(void *, void *, pj_ssize_t);
  void (*stream_rtp_callback2)(pjmedia_tp_cb_param *);
  void (*stream_rtcp_callback)(void *, void *, pj_ssize_t);
};

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

void observe_rtp(RtpTapTransport *tap, uint8_t direction, const void *packet,
                 pj_ssize_t packet_size) {
  if (tap == nullptr || tap->media_runtime == nullptr || packet == nullptr ||
      packet_size < 12) {
    return;
  }
  const auto *bytes = static_cast<const uint8_t *>(packet);
  if ((bytes[0] >> 6U) != 2U) {
    return;
  }

  const uint16_t sequence =
      static_cast<uint16_t>((static_cast<uint16_t>(bytes[2]) << 8U) | bytes[3]);
  const uint32_t timestamp = (static_cast<uint32_t>(bytes[4]) << 24U) |
                             (static_cast<uint32_t>(bytes[5]) << 16U) |
                             (static_cast<uint32_t>(bytes[6]) << 8U) |
                             static_cast<uint32_t>(bytes[7]);
  const uint32_t clock_rate =
      tap->rtp_clock_rate_hz->load(std::memory_order_relaxed);
  if (direction == TRIPLEX_RTP_RX) {
    tap->last_rx_rtp_timestamp->store(timestamp, std::memory_order_relaxed);
  }
  triplex_media_observe_rtp(tap->media_runtime, direction, sequence, timestamp,
                            static_cast<uint32_t>(packet_size),
                            monotonic_raw_ns(), clock_rate);
}

pj_status_t get_info(pjmedia_transport *transport,
                     pjmedia_transport_info *info) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_get_info(tap->child, info);
}

void on_rtp(pjmedia_tp_cb_param *parameter) {
  auto *tap = static_cast<RtpTapTransport *>(parameter->user_data);
  observe_rtp(tap, TRIPLEX_RTP_RX, parameter->pkt, parameter->size);

  if (tap->stream_rtp_callback2 != nullptr) {
    pjmedia_tp_cb_param forwarded = *parameter;
    forwarded.user_data = tap->stream_user_data;
    tap->stream_rtp_callback2(&forwarded);
  } else if (tap->stream_rtp_callback != nullptr) {
    tap->stream_rtp_callback(tap->stream_user_data, parameter->pkt,
                             parameter->size);
  }
}

void on_rtcp(void *user_data, void *packet, pj_ssize_t size) {
  auto *tap = static_cast<RtpTapTransport *>(user_data);
  if (tap->stream_rtcp_callback != nullptr) {
    tap->stream_rtcp_callback(tap->stream_user_data, packet, size);
  }
}

pj_status_t attach2(pjmedia_transport *transport,
                    pjmedia_transport_attach_param *parameter) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  PJ_ASSERT_RETURN(tap->stream_user_data == nullptr, PJ_EINVALIDOP);

  tap->stream_user_data = parameter->user_data;
  tap->stream_ref = parameter->stream;
  tap->stream_rtp_callback = parameter->rtp_cb;
  tap->stream_rtp_callback2 = parameter->rtp_cb2;
  tap->stream_rtcp_callback = parameter->rtcp_cb;

  pjmedia_transport_attach_param forwarded = *parameter;
  forwarded.user_data = tap;
  forwarded.rtp_cb = nullptr;
  forwarded.rtp_cb2 = &on_rtp;
  forwarded.rtcp_cb = &on_rtcp;

  const pj_status_t status = pjmedia_transport_attach2(tap->child, &forwarded);
  if (status != PJ_SUCCESS) {
    tap->stream_user_data = nullptr;
    tap->stream_ref = nullptr;
    tap->stream_rtp_callback = nullptr;
    tap->stream_rtp_callback2 = nullptr;
    tap->stream_rtcp_callback = nullptr;
  }
  return status;
}

void detach(pjmedia_transport *transport, void *stream) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  PJ_UNUSED_ARG(stream);
  if (tap->stream_user_data == nullptr) {
    return;
  }
  pjmedia_transport_detach(tap->child, tap);
  tap->stream_user_data = nullptr;
  tap->stream_ref = nullptr;
  tap->stream_rtp_callback = nullptr;
  tap->stream_rtp_callback2 = nullptr;
  tap->stream_rtcp_callback = nullptr;
}

pj_status_t send_rtp(pjmedia_transport *transport, const void *packet,
                     pj_size_t size) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  observe_rtp(tap, TRIPLEX_RTP_TX, packet, static_cast<pj_ssize_t>(size));
  return pjmedia_transport_send_rtp(tap->child, packet, size);
}

pj_status_t send_rtcp(pjmedia_transport *transport, const void *packet,
                      pj_size_t size) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_send_rtcp(tap->child, packet, size);
}

pj_status_t send_rtcp2(pjmedia_transport *transport,
                       const pj_sockaddr_t *address, unsigned address_length,
                       const void *packet, pj_size_t size) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_send_rtcp2(tap->child, address, address_length,
                                      packet, size);
}

pj_status_t media_create(pjmedia_transport *transport, pj_pool_t *sdp_pool,
                         unsigned options,
                         const pjmedia_sdp_session *remote_sdp,
                         unsigned media_index) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_media_create(tap->child, sdp_pool, options,
                                        remote_sdp, media_index);
}

pj_status_t encode_sdp(pjmedia_transport *transport, pj_pool_t *sdp_pool,
                       pjmedia_sdp_session *local_sdp,
                       const pjmedia_sdp_session *remote_sdp,
                       unsigned media_index) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_encode_sdp(tap->child, sdp_pool, local_sdp,
                                      remote_sdp, media_index);
}

pj_status_t media_start(pjmedia_transport *transport, pj_pool_t *pool,
                        const pjmedia_sdp_session *local_sdp,
                        const pjmedia_sdp_session *remote_sdp,
                        unsigned media_index) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_media_start(tap->child, pool, local_sdp, remote_sdp,
                                       media_index);
}

pj_status_t media_stop(pjmedia_transport *transport) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_media_stop(tap->child);
}

pj_status_t simulate_loss(pjmedia_transport *transport, pjmedia_dir direction,
                          unsigned percent) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  return pjmedia_transport_simulate_lost(tap->child, direction, percent);
}

void release_pool(void *argument) {
  auto *tap = static_cast<RtpTapTransport *>(argument);
  pj_pool_release(tap->pool);
}

pj_status_t destroy(pjmedia_transport *transport) {
  auto *tap = reinterpret_cast<RtpTapTransport *>(transport);
  if (tap->close_child == PJ_TRUE) {
    pjmedia_transport_close(tap->child);
  }
  if (tap->base.grp_lock != nullptr) {
    pj_grp_lock_dec_ref(tap->base.grp_lock);
  } else {
    release_pool(tap);
  }
  return PJ_SUCCESS;
}

pjmedia_transport_op operations = {
    &get_info,      nullptr,       &detach,     &send_rtp,    &send_rtcp,
    &send_rtcp2,    &media_create, &encode_sdp, &media_start, &media_stop,
    &simulate_loss, &destroy,      &attach2,
};

} // namespace

pj_status_t triplex_rtp_tap_create(pjmedia_endpt *endpoint,
                                   pjmedia_transport *base_transport,
                                   bool close_base_transport,
                                   TriplexNativeMediaRuntime *media_runtime,
                                   std::atomic<uint32_t> *rtp_clock_rate_hz,
                                   std::atomic<uint32_t> *last_rx_rtp_timestamp,
                                   pjmedia_transport **output) {
  PJ_ASSERT_RETURN(endpoint != nullptr && base_transport != nullptr &&
                       media_runtime != nullptr &&
                       rtp_clock_rate_hz != nullptr &&
                       last_rx_rtp_timestamp != nullptr && output != nullptr,
                   PJ_EINVAL);

  pj_pool_t *pool =
      pjmedia_endpt_create_pool(endpoint, "triplex-rtp-tap", 1024, 512);
  PJ_ASSERT_RETURN(pool != nullptr, PJ_ENOMEM);
  auto *tap = PJ_POOL_ZALLOC_T(pool, RtpTapTransport);
  tap->pool = pool;
  tap->child = base_transport;
  tap->close_child = close_base_transport ? PJ_TRUE : PJ_FALSE;
  tap->media_runtime = media_runtime;
  tap->rtp_clock_rate_hz = rtp_clock_rate_hz;
  tap->last_rx_rtp_timestamp = last_rx_rtp_timestamp;
  pj_ansi_strxcpy(tap->base.name, "triplex-rtp-tap", sizeof(tap->base.name));
  tap->base.type =
      static_cast<pjmedia_transport_type>(PJMEDIA_TRANSPORT_TYPE_USER + 1);
  tap->base.op = &operations;

  if (base_transport->grp_lock != nullptr) {
    tap->base.grp_lock = base_transport->grp_lock;
    pj_grp_lock_add_ref(tap->base.grp_lock);
    pj_grp_lock_add_handler(tap->base.grp_lock, pool, tap, &release_pool);
  }

  *output = &tap->base;
  return PJ_SUCCESS;
}
