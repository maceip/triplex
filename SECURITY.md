# Security posture

Last reviewed: 2026-07-31

## Runtime boundaries

- The meeting gateway binds to loopback, disables interactive API docs, and
  requires call-scoped producer/browser tokens plus an administrator token.
- vLLM runs through its in-process engine. Triplex does not expose vLLM's HTTP,
  gRPC, multimodal, GGUF, tool-parser, or speculative-decoding interfaces.
- Runtime model identifiers and immutable Hugging Face revisions are fixed in
  source. The call path never accepts a model repository, checkpoint, template,
  or local cache path from a caller.
- The container runs as an unprivileged user. Clone mode accepts only a local,
  consent-validated PCM WAV and never interprets it as a model artifact.

## Automated checks

The production Python paths pass Bandit with no findings. The browser bridge
passes `npm audit` with no findings. The resolved Linux dependency graph is
checked with `pip-audit` during release review.

## Dependency VEX

The official `qwen-tts==0.1.1` release requires
`transformers==4.57.3`. That constraint is incompatible with `vllm>=0.24`, so
the current joint CUDA environment pins `vllm==0.23.0` until either upstream
provides a compatible secure resolution or the two GPU engines are isolated.

The current advisory feed reports findings in vLLM, Transformers, DiskCache,
Setuptools, and Torch. Their published vulnerable surfaces are not reachable in
Triplex:

- vLLM is in-process, text-only, fixed-model inference with no public vLLM
  server, multimodal input, GGUF weights, or speculative decoding.
- Transformers loads only the fixed Qwen revision. Triplex does not use
  Trainer checkpoints, X-CLIP conversion, LightGlue, or caller-selected models.
- DiskCache's pickle risk requires modification of the container's local cache;
  no cache file is accepted from the network or a shared tenant.
- vLLM constrains runtime Setuptools below 81. Its current advisory concerns
  Unicode-normalization bypasses while building source distributions on macOS;
  the Linux runtime builds no distributions. The wheel is built separately
  with Setuptools 83 before installation into the runtime image.
- The Torch advisory identified by the audit applies through 2.6.0, while the
  resolved production graph uses 2.11.0 and does not invoke `torch.jit.script`.

These are explicit compensating controls, not a claim that the upstream
packages have no advisories. Re-run the audit and remove this VEX as soon as a
compatible Qwen/vLLM upgrade is available. A deployment that enables arbitrary
model IDs, shared writable model caches, or public vLLM endpoints invalidates
this assessment.

Audited VEX identifiers:

- DiskCache: `PYSEC-2026-2447`
- Setuptools: `PYSEC-2026-3447`
- Torch: `PYSEC-2025-194`
- Transformers: `PYSEC-2025-217`, `PYSEC-2026-2288`,
  `PYSEC-2026-2289`, `PYSEC-2026-2290`
- vLLM: `PYSEC-2026-2303`, `PYSEC-2026-2304`, `PYSEC-2026-2305`,
  `PYSEC-2026-3403`, `PYSEC-2026-3404`, `PYSEC-2026-3405`,
  `PYSEC-2026-3406`, `PYSEC-2026-3408`, `PYSEC-2026-3542`

The one direct wheel URL in the lock is pinned by SHA-256. The model-weight
repositories are pinned by immutable revision as described above.
