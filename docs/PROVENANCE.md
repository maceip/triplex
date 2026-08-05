# Triplex Provenance Ledger

Status: Active  
Created: 2026-08-02  
Scope: /Users/mac/triplex

## Purpose
Track disposition of all imported code with evidence and reasoning. Prevent rejected code from entering production without explicit review.

## Disposition Categories
- **adopt**: Proven, tested, moves to canonical location unchanged
- **adapt**: Requires modification, moves with changes documented
- **rewrite**: Concept preserved, implementation replaced
- **evidence-only**: Reference/test value, not production runtime
- **reject**: Known bugs or wrong approach, preserved for inspection only

## Import Log

### /Users/mac/plivo-engine
**Status**: Pending snapshot  
**Action**: Import under experiments/plivo-native-draft  
**Disposition**: TBD per file

| File | Original Path | Disposition | Evidence | Reason | Canonical Destination |
|------|---------------|-------------|----------|--------|------------------------|
| TBD | TBD | TBD | TBD | TBD | TBD |

### /Users/mac/triplex (Legacy)
**Status**: Restructuring  
**Action**: Preserve interfaces/tests, remove from default runtime

| Component | Original Path | Disposition | Evidence | Reason | Canonical Destination |
|-----------|---------------|-------------|----------|--------|------------------------|
| Python pipeline | src/ | evidence-only | Reference implementation | Not Android runtime | experiments/python-agent |
| Mobile app | mobile/ | rewrite | Corrupted client, placeholders | Architecture mismatch | apps/android |
| Tests | tests/ | adopt | pytest suite | Valid test coverage | tests/ |

## Classification Rules
1. Every file must be classified before moving to canonical location
2. Rejected code remains in experiments/ with read-only marker
3. Adoption requires: (a) working test, (b) latency measurement, or (c) proven PSTN evidence
4. Provenance entries are immutable; add supplement rows for corrections

## Canonical Structure
```
/Users/mac/triplex/
├── apps/
│   └── android/              # Phone-first agent runtime
│       ├── app/              # Jetpack Compose UI
│       ├── agent/            # Local inference, VAD, ASR, TTS
│       ├── telephony/        # SIP/WebRTC, provider adapters
│       └── native-media/     # Frame processing, JNI
├── gateway/                  # Cloud control API
│   ├── app/
│   │   ├── api/              # REST endpoints
│   │   ├── models/           # Pydantic schemas
│   │   ├── services/         # Business logic
│   │   └── db/               # PostgreSQL models
│   └── alembic/              # Migrations
├── experiments/              # Reference/fallback code
│   ├── python-agent/
│   ├── plivo-native-draft/
│   └── chime-kvs-browser/
└── testlab/                  # Test infrastructure
```

## Review Schedule
- Weekly during active migration
- On any disposition dispute
- Before production deployment

---
_Governed by `UNIFICATION_PLAN.md`_
