# Native dependency record

| Dependency | Pin | Purpose | License gate |
|---|---|---|---|
| PJSIP | 2.17 / `5a457451fa2712ba18e12b01738e8ff3af2b26fd` | SIP, RTP/RTCP, SRTP, codec, jitter/PLC, resampling | GPL-2.0-or-later or commercial; distribution requires an approved licensing path |
| Mbed TLS | 3.6.6 / archive SHA-256 `8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a` | TLS 1.2/1.3 and certificate verification | Apache-2.0; retain notices |

The build consumes upstream source into ignored `.native-deps`; third-party
source is not copied into Triplex. `manifest.sha256` fingerprints the exact
headers and binaries used by each ABI build.
