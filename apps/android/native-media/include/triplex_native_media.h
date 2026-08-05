#ifndef TRIPLEX_NATIVE_MEDIA_H
#define TRIPLEX_NATIVE_MEDIA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TRIPLEX_FRAME_SAMPLES 160u
#define TRIPLEX_FRAME_BYTES 320u
#define TRIPLEX_ROUTE_DIRECT_PJSIP 1u
#define TRIPLEX_ROUTE_RELAY_WEBSOCKET 2u
#define TRIPLEX_RTP_RX 0u
#define TRIPLEX_RTP_TX 1u
#define TRIPLEX_STREAM_CAPTURE 0u
#define TRIPLEX_STREAM_SYNTH 1u
#define TRIPLEX_STREAM_REFLEX 2u
#define TRIPLEX_FLAG_DISCONT (1u << 0)
#define TRIPLEX_FLAG_SILENCE (1u << 1)
#define TRIPLEX_FLAG_EOU (1u << 2)
#define TRIPLEX_FLAG_FINAL_CHUNK (1u << 3)

typedef struct TriplexNativeMediaRuntime TriplexNativeMediaRuntime;

typedef struct TriplexFrameHeader {
  uint64_t seq;
  int64_t mono_ns;
  uint32_t rtp_ts;
  uint32_t epoch;
  uint16_t len;
  uint16_t flags;
  uint8_t stream;
  uint8_t reserved[7];
} TriplexFrameHeader;

typedef struct TriplexRtpDirectionSnapshot {
  uint64_t packets;
  uint64_t bytes;
  uint64_t sequence_gap_packets;
  uint64_t reordered_packets;
  uint64_t duplicate_packets;
  int64_t last_arrival_mono_ns;
  uint32_t last_rtp_timestamp;
  uint32_t rtp_clock_rate_hz;
  uint64_t jitter_us;
  uint64_t last_extended_sequence;
} TriplexRtpDirectionSnapshot;

typedef struct TriplexMediaMetrics {
  uint16_t abi_version;
  uint16_t struct_size;
  uint8_t route;
  uint8_t reserved[3];
  uint32_t epoch;
  uint32_t flush_ack_epoch;
  uint64_t capture_frames;
  uint64_t capture_dropped_frames;
  uint64_t capture_discontinuities;
  uint64_t synth_enqueued_frames;
  uint64_t synth_played_frames;
  uint64_t synth_stale_frames;
  uint64_t synth_dropped_frames;
  uint64_t silence_frames;
  uint32_t synth_queue_depth;
  uint32_t capture_queue_depth;
  TriplexRtpDirectionSnapshot rx;
  TriplexRtpDirectionSnapshot tx;
} TriplexMediaMetrics;

TriplexNativeMediaRuntime *triplex_media_create(uint8_t route);
void triplex_media_destroy(TriplexNativeMediaRuntime *runtime);
uint32_t triplex_media_epoch(const TriplexNativeMediaRuntime *runtime);

bool triplex_media_capture_push(const TriplexNativeMediaRuntime *runtime,
                                const int16_t *samples, size_t sample_count,
                                int64_t mono_ns, uint32_t rtp_timestamp);
size_t triplex_media_capture_pop(const TriplexNativeMediaRuntime *runtime,
                                 int16_t *samples_out, size_t sample_capacity,
                                 TriplexFrameHeader *header_out);

bool triplex_media_synth_push(const TriplexNativeMediaRuntime *runtime,
                              const int16_t *samples, size_t sample_count,
                              int64_t mono_ns, uint32_t epoch, uint16_t flags,
                              uint8_t stream);
size_t triplex_media_synth_pop(const TriplexNativeMediaRuntime *runtime,
                               int16_t *samples_out, size_t sample_capacity,
                               TriplexFrameHeader *header_out, int64_t mono_ns);

uint32_t triplex_media_interrupt(const TriplexNativeMediaRuntime *runtime);

/* Baseline agent session (triplex-agent-core). The session is the sole
 * consumer of the runtime's capture and synth rings; never run it
 * concurrently with a live call's media port, and always destroy the
 * session before the media runtime. */
typedef struct TriplexAgentSession TriplexAgentSession;

typedef struct TriplexAgentStatus {
  uint16_t abi_version;
  uint16_t struct_size;
  uint32_t state;
  uint32_t epoch;
  uint32_t reserved;
  uint64_t vad_onsets;
  uint64_t turns_committed;
  uint64_t utterances_completed;
  uint64_t barge_ins;
  uint64_t egressed_samples;
  uint64_t voiced_samples;
} TriplexAgentStatus;

TriplexAgentSession *triplex_agent_create(const TriplexNativeMediaRuntime *media);
void triplex_agent_destroy(TriplexAgentSession *session);
bool triplex_agent_status(const TriplexAgentSession *session,
                          TriplexAgentStatus *output);
bool triplex_media_observe_rtp(const TriplexNativeMediaRuntime *runtime,
                               uint8_t direction, uint16_t sequence,
                               uint32_t timestamp, uint32_t packet_bytes,
                               int64_t arrival_mono_ns, uint32_t clock_rate_hz);
bool triplex_media_metrics_snapshot(const TriplexNativeMediaRuntime *runtime,
                                    TriplexMediaMetrics *output);

#ifdef __cplusplus
}
#endif

#endif
