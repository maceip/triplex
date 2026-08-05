# Triplex PSTN Verification

Live PSTN call verification against Plivo infrastructure to clear production gates.

## Overview

Per `UNIFICATION_PLAN.md`:
> No component is called production-ready without real PSTN, device, timing, interruption, and recovery evidence.

This testlab executes real outbound PSTN calls and receives inbound calls to measure:
- Round-trip audio latency (p50/p95)
- Jitter distribution
- Packet reordering and duplication rates
- Call establishment time
- Audio quality metrics (clipping, silence ratio)

## Prerequisites

1. **Plivo Account**
   - Auth ID and Auth Token (environment variables)
   - Provisioned Plivo number for testing
   - SIP endpoint registered from Android device

2. **Environment Variables**
   ```bash
   export PLIVO_AUTH_ID="your_auth_id"
   export PLIVO_AUTH_TOKEN="your_auth_token"
   export PLIVO_NUMBER="+1234567890"
   export TEST_DESTINATION="+0987654321"
   export SIP_ENDPOINT="triplex-endpoint"
   ```

3. **Plivo Number Assignment**
   - Virtual number assigned in dashboard
   - Answer URL configured: `https://your-gateway/plivo/answer`
   - Hangup URL configured: `https://your-gateway/plivo/hangup`

4. **Gateway Running**
   - Triplex gateway accessible at `GATEWAY_URL`
   - Plivo webhook endpoints responding

## Quick Start

```bash
# Run full verification sequence
./testlab/scripts/run_pstn_verification.sh

# Or individual steps:
python testlab/scripts/live_pstn_test.py \
    --outbound \
    --from-number $PLIVO_NUMBER \
    --to-number $TEST_DESTINATION \
    --sip-endpoint $SIP_ENDPOINT \
    --call-duration 30 \
    --inject-audio
```

## Scripts

### live_pstn_test.py

Primary test runner for PSTN calls.

**Features**:
- Outbound call placement
- Inbound call handling (wait mode)
- Audio injection (440Hz test tone)
- Telemetry capture (RTP headers, timing)
- Production gate validation

**Arguments**:
| Argument | Description |
|----------|-------------|
| `--auth-id` | Plivo auth ID (or `PLIVO_AUTH_ID`) |
| `--auth-token` | Plivo auth token (or `PLIVO_AUTH_TOKEN`) |
| `--from-number` | Plivo number to call from |
| `--to-number` | Destination PSTN number |
| `--sip-endpoint` | SIP endpoint ID |
| `--outbound` | Place outbound call |
| `--inbound` | Wait for inbound call |
| `--call-duration` | Call duration (seconds) |
| `--inject-audio` | Inject test tone |
| `--gateway` | Triplex gateway URL |

**Output**: `testlab/pstn-evidence/pstn-validation.json`

### compare.py

Transport validation comparison with PSTN evidence.

```bash
python testlab/transport/compare.py \
    --pstn testlab/transport/pstn-validation.json \
    --production-gate
```

**Production Gates**:
| Gate | Threshold |
|------|-----------|
| Establishment time p95 | ≤5000 ms |
| Jitter p95 | ≤100 ms |
| Packet loss | ≤1% |
| Min call duration | ≥10 s |
| `production_ready` | true |
| `physical_pstn_evidence` | true |

### generate_test_audio.py

Generate test tone/DNTF for audio injection.

```bash
python testlab/scripts/generate_test_audio.py \
    --output testlab/pstn-evidence/test_tone.pcm \
    --sample-rate 8000 \
    --duration 5.0 \
    --frequency 440
```

### document_evidence.py

Archive and document PSTN evidence with checksums.

```bash
python testlab/scripts/document_evidence.py
```

**Output**:
- `pstn-evidence-{timestamp}.tar.gz` - Evidence archive
- `pstn-evidence-documentation.json` - Machine-readable documentation
- `PSTN_EVIDENCE.md` - Human-readable summary

## Evidence Collection

### Captured Metrics

| Metric | Source |
|--------|--------|
| Call establishment time | Plivo webhook timing |
| RTP sequence numbers | SIP stack |
| Jitter | RFC 3550 calculation |
| Packet loss | Sequence gap analysis |
| Audio levels | PCM amplitude analysis |
| Clipping | Peak detection |

### Output Files

```
testlab/pstn-evidence/
├── pstn-validation.json      # Validation report
├── test_tone.pcm             # Injected audio
├── outbound_captured.pcm     # Captured outbound audio
├── inbound_captured.pcm      # Captured inbound audio
└── rtp_log.txt               # RTP packet log
```

### Validation Report Schema

See `schemas/pstn-validation.schema.json`:

```json
{
  "passed": true,
  "production_ready": true,
  "physical_pstn_evidence": true,
  "latency": {
    "p50": 2.3,
    "p95": 4.1
  },
  "packets": {
    "sent": 3000,
    "received": 2998,
    "dropped": 2,
    "avgJitterMs": 15.3
  },
  "errors": [],
  "warnings": []
}
```

## Production Gate Sign-Off

**Gate**: `PHYSICAL_PSTN_EVIDENCE_REQUIRED`

**Conditions** (all must be true):
1. At least one successful PSTN call (inbound or outbound)
2. Call duration ≥10 seconds
3. Establishment time p95 ≤5000ms
4. Packet loss ≤1%
5. Jitter p95 ≤100ms
6. No critical errors

**Sign-Off**:
```json
{
  "production_ready": true,
  "physical_pstn_evidence": true
}
```

## Measured Targets

Per `UNIFICATION_PLAN.md`:

| Measure | Target (p95) |
|---------|--------------|
| Provider transit + establishment | ≤5 s |
| Jitter (playout buffer) | ≤100 ms |
| Packet loss | ≤1% |
| Dropped audio frames | 0 |

## Safety Notes

1. **Real PSTN Calls**: This makes actual phone calls. Ensure:
   - Test numbers are consented
   - Call durations are reasonable
   - Audio injection is appropriate

2. **Costs**: Plivo charges per minute. Monitor usage.

3. **Privacy**: Recordings may contain real audio. Handle per policy.

## Troubleshooting

**Gateway not reachable**:
```bash
curl http://localhost:8000/health
```

**Plivo auth invalid**:
```bash
curl -u "$PLIVO_AUTH_ID:$PLIVO_AUTH_TOKEN" https://api.plivo.com/v1/Account/$PLIVO_AUTH_ID/
```

**SIP endpoint not registered**:
```bash
# Check device readiness via gateway API
curl http://localhost:8000/devices/status
```

**No audio captured**:
- Check microphone permissions on Android
- Verify audio routing is enabled
- Ensure silence detection thresholds are appropriate
