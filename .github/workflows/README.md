# Triplex CI/CD Infrastructure

Automated workflow enforcing real-time performance guarantees and transport validation gates.

## Workflow Structure

```
.github/workflows/ci.yml
├── allocation-guard      → RT zero-allocation soak tests (RtGuardAlloc)
├── agent-core-tests      → Rust agent-core unit + integration tests
├── transport-validation  → Gradle + Python validation suite (p50/p95/drops gates)
├── gateway-validation    → FastAPI integration tests (PostgreSQL)
├── dialogue-tests        → Kotlin conversation-loop unit tests
├── android-build         → APK assembly + unit tests
└── evidence-gate         → Artifact collection & manifest verification

Jobs above evidence-gate run independently (no needs: chain) so a red
allocation guard cannot skip transport, gateway, or Android.
```

## Jobs

### 1. RT Allocation Guard

**Purpose**: Verify zero heap allocations on RT-tagged threads.

**Implementation**:
- Run `rt_alloc` and `vad_rt` integration tests via `--test` (not positional filters)
- Use `RtGuardAlloc` + `tag_rt_thread` (same guard as agent-core)
- Assert `rtguard::violations() == 0`

**Artifacts**:
- `allocation-report.json` - Total allocation events
- `vad-allocations.json` - VAD-specific allocations

**Performance Gate**: Zero allocations on RT paths

### 2. Transport Validation

**Purpose**: Validate p50/p95 latency and packet drops against thresholds.

**Gradle Task**: `:telephony-plivo:transportValidation`

**Python Scripts**:
- `compare.py --mode=simulated` - Network simulation
- `compare.py --mode=kotlin` - Kotlin coroutine tests
- `compare.py --mode=cpp` - Native C++ transport tests
- `validate_gates.py` - Enforce thresholds

**Gates** (p95 release objectives):
| Metric | Gate |
|--------|------|
| p50 Latency | ≤120 ms |
| p95 Latency | ≤150 ms |
| Packet Drops | 0 |

**Artifacts**:
- `simulated-validation.json`
- `kotlin-transport-validation.json`
- `cpp-transport-validation.json`

**PR Comments**: Automatic table posted with results

### 3. Gateway Integration

**Purpose**: Validate FastAPI control endpoints.

**Services**:
- PostgreSQL 16 (port 5432)
- Plivo simulator (port 8000)

**Tests**: pytest with async coverage

**Artifacts**:
- `coverage.xml`
- `htmlcov/`

### 4. Android Build

**Purpose**: Build APK and run unit tests.

**Tasks**:
- `:app:assembleDebug`
- `:app:testDebugUnitTest`

**Artifacts**:
- `app-debug.apk`
- Test reports

### 5. Evidence Gate

**Purpose**: Collect, verify, and archive all evidence artifacts.

**Inputs**: All previous job artifacts

**Scripts**:
- `validate_evidence.py` - Completeness check
- `create_manifest.py` - SHA256 hashes + metadata
- `check_gates.py` - Final gate status

**Artifacts**:
- `evidence-manifest.json` (90-day retention)
- `gate-status.json`

## Evidence Schema

JSON schemas located in `schemas/`:

- `transport-validation.schema.json` - Validates latency/packet metrics
- `evidence-manifest.schema.json` - Validates manifest structure

All evidence files must conform to schemas before archiving.

## Scripts

| Script | Purpose |
|--------|---------|
| `compare.py` | Run transport validation across modes |
| `validate_gates.py` | Enforce p50/p95/drop limits |
| `validate_evidence.py` | Check artifact completeness |
| `create_manifest.py` | Hash and record all evidence |
| `check_gates.py` | Final gate check |

## Local Testing

```bash
# Run allocation guard tests
cd apps/android/native-media
cargo test --release --test rt_alloc --test vad_rt -- --nocapture

# Run agent-core tests
cd apps/android/agent
cargo test --all-targets

# Run transport validation
./gradlew :telephony-plivo:transportValidation
python scripts/compare.py --mode=all --output=artifacts/

# Validate gates
python scripts/validate_gates.py \
  --simulated artifacts/simulated-validation.json \
  --kotlin artifacts/kotlin-transport-validation.json \
  --cpp artifacts/cpp-transport-validation.json \
  --p50-limit 120 \
  --p95-limit 150 \
  --drop-limit 0
```

## Performance Targets

Per `UNIFICATION_PLAN.md` p95 release objectives:

| Measure | Target (p95) | CI Gate |
|---------|--------------|---------|
| VAD to ASR enqueue | 5 ms | Allocation guard |
| Packet latency | 150 ms | Transport validation |
| Packet drops | 0 | Transport validation |
| Gateway response | <200 ms | Integration tests |

## Adding New Gates

1. Add test script or Gradle task
2. Define JSON output schema in `schemas/`
3. Add artifact to `evidence-gate` job
4. Update `validate_evidence.py` checks
5. Add gate to `evidence-manifest.schema.json`

## Troubleshooting

**Allocation guard fails**: Check for heap allocation in RT functions
```rust
// ❌ Bad: Vec allocation on RT path
let buffer = vec![0i16; 160];

// ✅ Good: Stack allocation
let buffer = [0i16; 160];
```

**Transport validation fails**: Check latency gates
```bash
# View results
cat artifacts/kotlin-transport-validation.json | jq '.latency'
```

**Evidence incomplete**: Download artifacts manually
```bash
gh run download <run-id> -d all-artifacts
```
