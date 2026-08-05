# Plivo direct-media spike

Status: implemented and host-verified; real Android/PSTN evidence is still
required. This module is not production-ready.

PJSIP is the provisional first-spike adapter. PJSIP versus Linphone remains
open until equivalent device and PSTN evidence supports a selection.

## Boundary

PJSIP owns SIP, RTP/RTCP, SRTP, G.711, jitter buffering, PLC, and 8 kHz ↔
16 kHz conversion. Triplex adds only:

- a metadata-only RTP transport wrapper for sender timestamps, extended
  sequence gaps, reordering, duplication, bytes, arrival time, and jitter;
- one 16 kHz mono, 10 ms `pjmedia_port` connected bidirectionally to the call;
- the bounded Rust frame pools, queues, epoch invalidation, and metrics;
- a fixed-record JNI control/evidence surface with no native-to-Kotlin
  callbacks.

`pjsua_set_null_snd_dev()` supplies the conference clock. The PJSIP Android
hardware audio backends are compiled out, port zero is never connected, and
neither `AudioRecord` nor `AudioTrack` appears in this module. Caller PCM goes
from the call conference port to `native-media`; generated PCM returns through
the same port.

## Security defaults

- TLS 1.2/1.3 only, server certificate verification on, caller-provided CA
  bundle required;
- IPv4 and IPv6 TLS transports, NAT64 enabled, wildcard local binding;
- registration uses `sips:...;transport=tls` and the account is not allowed to
  downgrade to UDP/TCP;
- SRTP is mandatory and requires a TLS signaling hop;
- outbound calls accept only a non-expired gateway grant containing a `sips:`
  URI;
- registration credentials are not persisted by this module. JNI copies them
  into bounded native storage and the Kotlin password byte array is zeroed.

Plivo's endpoint documentation defines the credentials and
`sip:user@phone.plivo.com` form, and its firewall page lists TCP 5061. It does
not, by itself, establish that every SIP Endpoint account negotiates TLS plus
SRTP. The spike therefore fails closed. A real endpoint must pass TLS
certificate/protocol evidence and mandatory-SRTP call setup before this route
can be selected; do not weaken these settings to obtain a green demo.

## Lifecycle and signaling

`PlivoSipEndpoint` serializes all control calls on one thread. Android's
`registerDefaultNetworkCallback` waits for a validated replacement default
network before invoking `pjsua_handle_ip_change()`. PJSIP then closes stale
TLS transports, restarts listeners, re-registers, and refreshes active call
contact/media state. IP-change operation results are emitted as fixed events.

The same surface handles incoming answer, RFC 2833 DTMF receive/send, bounded
probe-tone injection, epoch publication on interruption, graceful hangup, and
explicit re-registration. Interruption never flushes caller capture; it makes
old synthesis epochs unplayable and records the first mixer flush
acknowledgement.

## Build

Install Android SDK 35 and an NDK, then prepare the pinned native stack:

```bash
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
apps/android/scripts/prepare-native.sh arm64-v8a
cd apps/android
./gradlew :telephony-plivo:transportValidation :app:assembleDebug
```

`transportValidation` runs JVM policy/state tests plus dependency-free host C++
tests using mock socket and RTP drivers. It writes machine-readable, explicitly
CI-only artifacts to:

- `telephony-plivo/build/reports/transport/kotlin-transport-validation.json`
- `telephony-plivo/build/reports/transport/cpp-transport-validation.json`

The C++ lifecycle guard under test is also used by the live PJSIP secure
transport callback: an abrupt active-socket drop publishes synthesis
cancellation, detaches stale media, and invalidates registration exactly once;
a late close from the prior Wi-Fi socket cannot tear down its cellular
replacement.

The preparation script verifies PJSIP 2.17 at commit
`5a457451fa2712ba18e12b01738e8ff3af2b26fd`, verifies the official Mbed TLS
3.6.6 archive checksum, checks that TLS and SRTP are compiled in, checks that
the Android hardware audio backend is compiled out, and writes SHA-256 hashes
for every staged header/library.

## Required device proof

For both direct PJSIP and the minimal relay comparison, capture one
`triplex.transport-call.v2` row per physical PSTN call. The first boundary
decision requires at least 20 calls per route and the comparator at
`testlab/transport/compare.py`. Required evidence includes:

- verified TLS protocol/cipher/certificate state and active SRTP;
- non-silent caller PCM plus injected PCM and RTP in both directions;
- callback-to-enqueue histogram, queue drops, sequence gaps, jitter, RTCP loss,
  and source RTP address;
- caller-side probe latency and caller-audible interruption stop time;
- Wi-Fi, LTE/5G, validated handoff, reconnect, DTMF, and hangup cases.

No emulator, host syntax check, generated waveform, or executable APK is a
substitute for this matrix.
