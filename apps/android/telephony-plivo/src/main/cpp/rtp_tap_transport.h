#ifndef TRIPLEX_RTP_TAP_TRANSPORT_H
#define TRIPLEX_RTP_TAP_TRANSPORT_H

#include <atomic>
#include <cstdint>

#include <pjmedia/endpoint.h>
#include <pjmedia/transport.h>

#include "triplex_native_media.h"

pj_status_t triplex_rtp_tap_create(pjmedia_endpt *endpoint,
                                   pjmedia_transport *base_transport,
                                   bool close_base_transport,
                                   TriplexNativeMediaRuntime *media_runtime,
                                   std::atomic<uint32_t> *rtp_clock_rate_hz,
                                   std::atomic<uint32_t> *last_rx_rtp_timestamp,
                                   pjmedia_transport **output);

#endif
