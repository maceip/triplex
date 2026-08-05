#ifndef TRIPLEX_PJSIP_H
#define TRIPLEX_PJSIP_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "triplex_native_media.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TriplexPjsipEngine TriplexPjsipEngine;

typedef struct TriplexSipConfig {
  const char *username;
  const char *password;
  const char *domain;
  const char *realm;
  const char *ca_bundle_path;
  uint16_t tls_port;
  uint16_t registration_timeout_seconds;
} TriplexSipConfig;

enum TriplexSipEventType {
  TRIPLEX_EVENT_REGISTRATION = 1,
  TRIPLEX_EVENT_INCOMING_CALL = 2,
  TRIPLEX_EVENT_CALL_STATE = 3,
  TRIPLEX_EVENT_MEDIA_STATE = 4,
  TRIPLEX_EVENT_DTMF = 5,
  TRIPLEX_EVENT_IP_CHANGE = 6,
  TRIPLEX_EVENT_CONFERENCE_OPERATION = 7,
  TRIPLEX_EVENT_TRANSPORT_STATE = 8,
};

typedef struct TriplexSipEvent {
  uint16_t abi_version;
  uint16_t struct_size;
  uint16_t type;
  uint16_t code;
  int32_t call_id;
  int32_t status;
  int32_t operation;
  uint32_t value;
  int64_t mono_ns;
  uint8_t digit;
  uint8_t method;
  uint8_t reserved[6];
} TriplexSipEvent;

typedef struct TriplexPjsipMetrics {
  uint16_t abi_version;
  uint16_t struct_size;
  uint8_t registered;
  uint8_t srtp_active;
  uint8_t media_active;
  uint8_t tls_connected;
  int32_t registration_status;
  int32_t active_call_id;
  uint32_t tls_protocol;
  uint32_t tls_cipher;
  uint32_t tls_verify_status;
  int32_t tls_last_status;
  uint32_t codec_clock_rate_hz;
  uint32_t codec_channel_count;
  uint64_t capture_probe_frames;
  uint64_t capture_probe_non_silent_frames;
  uint64_t capture_probe_checksum;
  uint32_t capture_probe_peak;
  uint32_t dropped_control_events;
  uint64_t callback_to_enqueue_max_ns;
  uint64_t callback_to_enqueue_buckets[8];
  uint64_t rtcp_rx_packets;
  uint64_t rtcp_rx_lost;
  uint64_t rtcp_rx_discarded;
  uint64_t rtcp_rx_reordered;
  uint64_t rtcp_rx_duplicates;
  uint64_t rtcp_rx_jitter_mean_us;
  uint64_t rtcp_rx_jitter_max_us;
  uint64_t rtcp_rtt_mean_us;
  uint32_t jitter_buffer_frames;
  uint32_t jitter_buffer_average_delay_ms;
  uint32_t jitter_buffer_max_delay_ms;
  uint32_t jitter_buffer_lost_frames;
  uint32_t jitter_buffer_discarded_frames;
  char source_rtp_address[96];
  uint32_t reserved1;
  TriplexMediaMetrics media;
} TriplexPjsipMetrics;

TriplexPjsipEngine *triplex_pjsip_create(const TriplexSipConfig *config);
int triplex_pjsip_start(TriplexPjsipEngine *engine);
int triplex_pjsip_answer(TriplexPjsipEngine *engine, int32_t call_id);
int triplex_pjsip_make_call(TriplexPjsipEngine *engine,
                            const char *authorized_sip_uri);
int triplex_pjsip_send_dtmf(TriplexPjsipEngine *engine, int32_t call_id,
                            const char *digits);
uint32_t triplex_pjsip_interrupt(TriplexPjsipEngine *engine);
int triplex_pjsip_hangup(TriplexPjsipEngine *engine, int32_t call_id,
                         int32_t sip_status);
int triplex_pjsip_handle_network_change(TriplexPjsipEngine *engine);
int triplex_pjsip_reregister(TriplexPjsipEngine *engine);
int triplex_pjsip_start_probe_tone(TriplexPjsipEngine *engine,
                                   uint32_t frequency_hz, uint32_t duration_ms,
                                   uint16_t amplitude);
size_t triplex_pjsip_drain_events(TriplexPjsipEngine *engine,
                                  TriplexSipEvent *events, size_t capacity);
bool triplex_pjsip_metrics_snapshot(TriplexPjsipEngine *engine,
                                    TriplexPjsipMetrics *output);
void triplex_pjsip_destroy(TriplexPjsipEngine *engine);

#ifdef __cplusplus
}
#endif

#endif
