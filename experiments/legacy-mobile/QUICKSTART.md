# Triplex Quickstart

## What is this?

Triplex is a modern reimagining of Google Duplex (2018) - an autonomous voice agent that makes phone calls on your behalf. Key differences:

| Problem | Duplex 2018 | Triplex 2026 |
|---------|-------------|--------------|
| Deception | No disclosure, illegal in consent states | Upfront announcement, implied consent |
| Hidden Operators | 25-40% human fallback | Transparent failure, user alerted |
| Rigid Intents | Fails on unexpected responses | LLM zero-shot handles anything |
| Latency | 2-4s with synthetic "um" stalling | ~300ms end-to-end |

## Project Files

```
triplex/
├── README.md                    # Full documentation
├── pyproject.toml               # Dependencies and config
├── configs/production.yaml      # Runtime configuration
├── src/triplex/
│   ├── __init__.py              # Package root
│   ├── demo.py                  # Run demo: python -m triplex.demo
│   ├── agent/
│   │   ├── compliance.py        # Legal disclosure handling
│   │   ├── call_state.py        # Conversation state machine
│   │   └── voice_agent.py       # Main agent loop
│   ├── audio/
│   │   ├── frontend.py          # Mel spectrogram extraction
│   │   ├── vad.py               # Voice activity detection
│   │   └── synthesis.py         # TTS integration
│   ├── inference/
│   │   ├── inherent_gate.py     # Inherent audio gate
│   │   └── llm_client.py        # Voice LLM (GPT-4o-audio, etc)
│   └── tasks/
│       └── booking.py           # Appointment booking handler
└── tests/test_agent.py          # Unit tests
```

## Run the Demo

```bash
cd /Users/mac/triplex

# Create venv
python -m venv .venv
source .venv/bin/activate

# Install
pip install -e ".[dev]"

# Run demo
python -m triplex.demo
```

## Integration with Existing Projects

### Inherent Audio Gate (`~/inherent`)

The Inherent model provides fast on-device audio filtering (2-6ms):

```python
from triplex.inference.inherent_gate import InherentGate

gate = InherentGate("/path/to/inherent.tflite")
scores = gate.score(mel_spectrogram)

if scores.is_above_threshold("isInteresting"):
    # Process with LLM
```

### Google Tensor SDK (`~/Downloads`)

For Pixel EdgeTPU acceleration:
- `~/Downloads/LiteRT NPU AOT compilation for Google Tensor.ipynb`
- `~/Downloads/litert_plugin_compiler.tar.gz`

### Android Deployment (`~/tenet-android`)

For mobile deployment, see the tenet-android project.

## Next Steps

1. **Train/Load Inherent Model**: Export from `~/inherent` to get the audio gate
2. **Connect Voice LLM**: Integrate OpenAI GPT-4o-audio or Gemini Live
3. **Add Telephony**: Connect to Twilio, Telnyx, or other phone API
4. **Build UI**: Create interface for users to initiate calls

## The Four Improvements

Read `improve` file for detailed comparison with original Duplex.

### 1. Eliminating Deception
- Old: "Hi, I'm calling to..." (sounds human, no disclosure)
- New: "Hi, this is an automated assistant calling to..." (transparent, legal)

### 2. Removing Shadow Call Center
- Old: 25% fully human, 15% human-assisted, kept secret
- New: Graceful failure with user notification when stuck

### 3. Bypassing Rigid Intent Trap
- Old: Pre-mapped intents, fails on "no 7pm but patio 7:15pm"
- New: LLM reasons: "Patio 7:15 is a compromise, check with user"

### 4. Solving Latency Trap
- Old: 2-4s pipeline, synthetic "um" to stall
- New: Inherent gates in 2-6ms, end-to-end audio in ~300ms
