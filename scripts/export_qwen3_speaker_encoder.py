#!/usr/bin/env python3
"""Export the Qwen3-TTS ECAPA-TDNN speaker encoder to on-device LiteRT/TFLite.

This exports ONLY the speaker-embedding sub-network ("x-vector" extractor) of
Qwen/Qwen3-TTS-12Hz-0.6B-Base -- an ECAPA-TDNN model that maps a log-mel
spectrogram to a 1024-d speaker embedding. It does NOT export the mel
front-end (STFT) itself, because torch.stft / dynamic-shape audio ops do not
convert cleanly to a portable, dependency-free TFLite graph. Instead the mel
front-end is documented here and provided as a pure NumPy reference
(`compute_mel`) so callers can reproduce it exactly (e.g. re-implement it in
Kotlin/Swift/C++ on-device, or run it once in Python before invoking the
.tflite model).

Pipeline (matches `qwen_tts.core.models.modeling_qwen3_tts.Qwen3TTSTTSModel
.extract_speaker_embedding`):

    24kHz mono PCM
        -> log-mel spectrogram (n_fft=1024, hop=256, win=1024, 128 mels,
           fmin=0, fmax=12000, librosa "slaney" filterbank, natural log
           compression, reflect-padded, center=False)
        -> pad/truncate to a FIXED number of frames (default 469)
        -> ECAPA-TDNN speaker encoder (this exported .tflite)
        -> 1024-d float32 x-vector

This script:
  1. Loads `speaker_encoder.*` weights out of the Qwen3-TTS
     `model.safetensors` checkpoint (bf16 -> float32).
  2. Instantiates the real `Qwen3TTSSpeakerEncoder` module from the `qwen_tts`
     package (installed in this repo's venv) and loads the weights.
  3. Exports it to ONNX with a fixed input shape (1, num_frames, 128).
  4. Converts ONNX -> TFLite (float32) via `onnx2tf`.
  5. Verifies the resulting .tflite loads and produces numerically consistent
     output vs. the PyTorch reference (cosine similarity check).
  6. Writes `speaker_encoder_meta.json` documenting the I/O contract and mel
     parameters next to the .tflite file.

Does NOT generate or persist any user voice embeddings -- only the model
artifact itself is produced, using only the pretrained checkpoint weights.

Usage:
    /Users/mac/triplex/.venv/bin/python scripts/export_qwen3_speaker_encoder.py

Requires (already present in /Users/mac/triplex/.venv):
    torch, transformers, qwen_tts, onnx, onnx2tf, tensorflow, ai_edge_litert
"""
from __future__ import annotations

import argparse
import json
import shutil
import struct
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import torch

DEFAULT_HF_MODEL_DIR = (
    "/Users/mac/.cache/huggingface/hub/"
    "models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/"
    "5d83992436eae1d760afd27aff78a71d676296fc"
)
DEFAULT_OUTPUT_DIR = "/Users/mac/.cache/triplex/qwen3-tts-0.6b-litert"

# --- Mel front-end parameters (must match qwen_tts.core.models.modeling_qwen3_tts) ---
SAMPLE_RATE = 24000
N_FFT = 1024
HOP_SIZE = 256
WIN_SIZE = 1024
NUM_MELS = 128
FMIN = 0
FMAX = 12000
DEFAULT_NUM_FRAMES = 469  # ~3.8-5.0s of audio depending on how you count padding; see meta json


@dataclass
class ExportConfig:
    model_dir: str = DEFAULT_HF_MODEL_DIR
    output_dir: str = DEFAULT_OUTPUT_DIR
    num_frames: int = DEFAULT_NUM_FRAMES
    num_mels: int = NUM_MELS
    onnx_opset: int = 17
    skip_verify: bool = False
    keep_intermediates: bool = False


def load_speaker_encoder_state_dict(safetensors_path: Path) -> dict[str, torch.Tensor]:
    """Reads only the `speaker_encoder.*` tensors out of a (possibly large)
    safetensors file without materializing the rest of the checkpoint."""
    from safetensors import safe_open

    state_dict = {}
    with safe_open(str(safetensors_path), framework="pt") as f:
        for key in f.keys():
            if key.startswith("speaker_encoder."):
                stripped = key[len("speaker_encoder."):]
                state_dict[stripped] = f.get_tensor(key).float()
    return state_dict


def build_speaker_encoder(model_dir: Path):
    from qwen_tts.core.models.configuration_qwen3_tts import (
        Qwen3TTSSpeakerEncoderConfig,
    )
    from qwen_tts.core.models.modeling_qwen3_tts import Qwen3TTSSpeakerEncoder

    with open(model_dir / "config.json") as f:
        full_config = json.load(f)
    spk_cfg_dict = full_config.get("speaker_encoder_config", {})
    enc_dim = spk_cfg_dict.get("enc_dim", 1024)
    sample_rate = spk_cfg_dict.get("sample_rate", SAMPLE_RATE)
    assert sample_rate == SAMPLE_RATE, f"unexpected sample_rate in config.json: {sample_rate}"

    cfg = Qwen3TTSSpeakerEncoderConfig(enc_dim=enc_dim, sample_rate=sample_rate)
    model = Qwen3TTSSpeakerEncoder(cfg)

    state_dict = load_speaker_encoder_state_dict(model_dir / "model.safetensors")
    missing, unexpected = model.load_state_dict(state_dict, strict=True)
    assert not missing and not unexpected, (missing, unexpected)

    model.eval()
    return model, enc_dim


class SpeakerEncoderWrapper(torch.nn.Module):
    """Thin wrapper giving the graph a fixed, named I/O contract for export."""

    def __init__(self, speaker_encoder: torch.nn.Module):
        super().__init__()
        self.speaker_encoder = speaker_encoder

    def forward(self, log_mel_spectrogram: torch.Tensor) -> torch.Tensor:
        # log_mel_spectrogram: (batch, num_frames, num_mels) float32
        return self.speaker_encoder(log_mel_spectrogram)


def compute_mel(audio: np.ndarray, num_frames: int | None = None) -> np.ndarray:
    """Pure NumPy reference implementation of the Qwen3-TTS mel front-end.

    Mirrors `qwen_tts.core.models.modeling_qwen3_tts.mel_spectrogram` exactly
    (librosa "slaney"-normalized mel filterbank, Hann window, reflect padding,
    center=False STFT, natural-log dynamic range compression).

    Args:
        audio: float32 mono PCM in [-1, 1], sampled at 24kHz, shape (num_samples,)
        num_frames: if given, the mel is padded (edge-replicate of the last
            frame) or truncated on the time axis to exactly this many frames.

    Returns:
        float32 array of shape (num_frames_out, num_mels) -- i.e. time-major,
        matching the PyTorch module's public (batch, frames, mels) API.

        IMPORTANT: the exported .tflite graph's actual input tensor is
        channel-second, shape (1, num_mels, num_frames) -- onnx2tf fused the
        model's internal transpose into the graph input during conversion.
        Before calling the TFLite model, transpose this array's axes
        (frames, mels) -> (mels, frames) and add a leading batch dimension,
        e.g.:

            mel = compute_mel(audio, num_frames=469)      # (469, 128)
            tflite_input = mel.T[np.newaxis, ...]          # (1, 128, 469)
    """
    import librosa

    audio = np.asarray(audio, dtype=np.float32)
    padding = (N_FFT - HOP_SIZE) // 2
    padded = np.pad(audio, (padding, padding), mode="reflect")

    stft = librosa.stft(
        padded,
        n_fft=N_FFT,
        hop_length=HOP_SIZE,
        win_length=WIN_SIZE,
        window="hann",
        center=False,
        pad_mode="reflect",
    )
    mag = np.sqrt(np.abs(stft) ** 2 + 1e-9)
    mel_basis = librosa.filters.mel(
        sr=SAMPLE_RATE, n_fft=N_FFT, n_mels=NUM_MELS, fmin=FMIN, fmax=FMAX
    )
    # Apple Accelerate's BLAS raises spurious (harmless) denormal-float32
    # RuntimeWarnings on this matmul; the actual result contains no NaN/Inf.
    with np.errstate(divide="ignore", over="ignore", invalid="ignore"):
        mel = mel_basis @ mag
    log_mel = np.log(np.clip(mel, a_min=1e-5, a_max=None))
    log_mel = log_mel.T.astype(np.float32)  # (frames, num_mels)

    if num_frames is not None:
        cur = log_mel.shape[0]
        if cur > num_frames:
            log_mel = log_mel[:num_frames]
        elif cur < num_frames:
            pad_len = num_frames - cur
            if cur == 0:
                log_mel = np.zeros((num_frames, NUM_MELS), dtype=np.float32)
            else:
                last = log_mel[-1:]
                log_mel = np.concatenate([log_mel, np.repeat(last, pad_len, axis=0)], axis=0)
    return log_mel


def export_onnx(wrapper: torch.nn.Module, cfg: ExportConfig, onnx_path: Path):
    dummy = torch.randn(1, cfg.num_frames, cfg.num_mels, dtype=torch.float32)
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            (dummy,),
            str(onnx_path),
            input_names=["log_mel_spectrogram"],
            output_names=["speaker_embedding"],
            opset_version=cfg.onnx_opset,
            dynamo=False,
            do_constant_folding=True,
        )


def convert_onnx_to_tflite(onnx_path: Path, work_dir: Path) -> Path:
    cmd = [
        sys.executable,
        "-m",
        "onnx2tf",
        "-i",
        str(onnx_path),
        "-o",
        str(work_dir),
        "-coion",  # keep onnx input/output names on the tflite graph
        "-osd",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    print(proc.stdout[-4000:])
    if proc.returncode != 0:
        print(proc.stderr[-4000:], file=sys.stderr)
        raise RuntimeError(f"onnx2tf failed with exit code {proc.returncode}")

    candidates = sorted(work_dir.glob("*_float32.tflite"))
    if not candidates:
        candidates = sorted(work_dir.glob("*.tflite"))
    if not candidates:
        raise RuntimeError(f"onnx2tf did not produce any .tflite file in {work_dir}")
    return candidates[0]


def verify_tflite(tflite_path: Path, wrapper: torch.nn.Module, cfg: ExportConfig):
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    assert len(input_details) == 1, input_details
    assert len(output_details) == 1, output_details

    in_shape = list(input_details[0]["shape"])
    out_shape = list(output_details[0]["shape"])

    rng = np.random.default_rng(0)
    sample = rng.standard_normal(in_shape).astype(np.float32)

    interpreter.set_tensor(input_details[0]["index"], sample)
    interpreter.invoke()
    tflite_out = interpreter.get_tensor(output_details[0]["index"])

    # onnx2tf fused the model's internal (batch, time, mel) -> (batch, mel, time)
    # transpose into the graph input itself, so the exported TFLite input is
    # already channel-second: (batch, num_mels, num_frames). The PyTorch
    # wrapper's public API is (batch, num_frames, num_mels), so transpose back.
    with torch.no_grad():
        if in_shape[1] == cfg.num_mels and in_shape[2] == cfg.num_frames:
            torch_in = torch.from_numpy(sample).transpose(1, 2)
        else:
            torch_in = torch.from_numpy(sample)
        torch_out = wrapper(torch_in).numpy()

    torch_flat = torch_out.reshape(-1)
    tflite_flat = tflite_out.reshape(-1)
    cos_sim = float(
        np.dot(torch_flat, tflite_flat)
        / (np.linalg.norm(torch_flat) * np.linalg.norm(tflite_flat) + 1e-9)
    )
    max_abs_diff = float(np.max(np.abs(torch_flat - tflite_flat)))

    return {
        "input_details": input_details,
        "output_details": output_details,
        "in_shape": in_shape,
        "out_shape": out_shape,
        "cosine_similarity": cos_sim,
        "max_abs_diff": max_abs_diff,
    }


def write_meta_json(
    output_path: Path,
    tflite_filename: str,
    verify_info: dict,
    cfg: ExportConfig,
    enc_dim: int,
):
    in_detail = verify_info["input_details"][0]
    out_detail = verify_info["output_details"][0]

    meta = {
        "model": "Qwen/Qwen3-TTS-12Hz-0.6B-Base speaker_encoder (ECAPA-TDNN)",
        "artifact": tflite_filename,
        "description": (
            "Standalone ECAPA-TDNN x-vector speaker encoder extracted from "
            "Qwen3-TTS-12Hz-0.6B-Base. Takes a fixed-length log-mel "
            "spectrogram and returns a 1024-d float32 speaker embedding. "
            "Does NOT include the mel front-end (STFT) -- compute mel "
            "features separately using the parameters below (see "
            "scripts/export_qwen3_speaker_encoder.py:compute_mel for a "
            "NumPy reference implementation)."
        ),
        "input": {
            "name": in_detail["name"],
            "index": int(in_detail["index"]),
            "shape": [int(x) for x in in_detail["shape"]],
            "dtype": str(in_detail["dtype"]),
            "layout": "[batch=1, num_mels, num_frames] (channel-second; onnx2tf fused the model's internal time<->mel transpose into the graph input)",
            "semantics": "log-mel spectrogram, natural-log dynamic range compression, NOT normalized further",
            "note": (
                "The underlying PyTorch module's public API takes "
                "(batch, num_frames, num_mels); compute_mel() in the export "
                "script returns that layout. Transpose the last two axes "
                "((frames, mels) -> (mels, frames)) before feeding this "
                "TFLite model."
            ),
        },
        "output": {
            "name": out_detail["name"],
            "index": int(out_detail["index"]),
            "shape": [int(x) for x in out_detail["shape"]],
            "dtype": str(out_detail["dtype"]),
            "semantics": f"{enc_dim}-d float32 x-vector speaker embedding (raw, not L2-normalized)",
        },
        "mel_frontend": {
            "sample_rate_hz": SAMPLE_RATE,
            "n_fft": N_FFT,
            "hop_size": HOP_SIZE,
            "win_size": WIN_SIZE,
            "num_mels": NUM_MELS,
            "fmin_hz": FMIN,
            "fmax_hz": FMAX,
            "window": "hann",
            "center": False,
            "pad_mode": "reflect",
            "stft_padding_each_side": (N_FFT - HOP_SIZE) // 2,
            "mel_filterbank_norm": "slaney (librosa default)",
            "compression": "natural log, clamp(min=1e-5) before log",
        },
        "fixed_length_frames": {
            "num_frames": cfg.num_frames,
            "reason": (
                "The exported TFLite graph only supports the ECAPA-TDNN conv "
                "stack (no dynamic-shape STFT ops), so the mel time axis "
                "must be a static, fixed length for export/conversion. "
                "This matches the fixed-length pattern used in ExecuTorch "
                "exports of similar ECAPA speaker encoders."
            ),
            "approx_audio_seconds": round(
                (cfg.num_frames - 1) * HOP_SIZE / SAMPLE_RATE + WIN_SIZE / SAMPLE_RATE, 3
            ),
            "padding_policy": (
                "If fewer than num_frames mel frames are available, repeat "
                "the last computed mel frame to pad up to num_frames "
                "(edge-replicate). If more, truncate to the first "
                "num_frames frames. See compute_mel() in the export script."
            ),
        },
        "verification": {
            "cosine_similarity_vs_pytorch": verify_info["cosine_similarity"],
            "max_abs_diff_vs_pytorch": verify_info["max_abs_diff"],
            "note": "Computed on a random Gaussian input tensor purely for numerical parity; not a real voice embedding.",
        },
        "source": {
            "hf_repo": "Qwen/Qwen3-TTS-12Hz-0.6B-Base",
            "hint_repo": "marksverdhei/Qwen3-Voice-Embedding-12Hz-0.6B",
            "python_package": "qwen_tts (qwen_tts.core.models.modeling_qwen3_tts.Qwen3TTSSpeakerEncoder)",
        },
    }

    with open(output_path, "w") as f:
        json.dump(meta, f, indent=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", default=DEFAULT_HF_MODEL_DIR)
    parser.add_argument("--output-dir", default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--num-frames", type=int, default=DEFAULT_NUM_FRAMES)
    parser.add_argument("--skip-verify", action="store_true")
    parser.add_argument("--keep-intermediates", action="store_true")
    args = parser.parse_args()

    cfg = ExportConfig(
        model_dir=args.model_dir,
        output_dir=args.output_dir,
        num_frames=args.num_frames,
        skip_verify=args.skip_verify,
        keep_intermediates=args.keep_intermediates,
    )

    model_dir = Path(cfg.model_dir)
    output_dir = Path(cfg.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir = output_dir / "_export_work"
    work_dir.mkdir(parents=True, exist_ok=True)

    print(f"[1/6] Loading speaker_encoder weights from {model_dir / 'model.safetensors'}")
    speaker_encoder, enc_dim = build_speaker_encoder(model_dir)
    wrapper = SpeakerEncoderWrapper(speaker_encoder).eval()
    print(f"      enc_dim={enc_dim}, params={sum(p.numel() for p in speaker_encoder.parameters()):,}")

    onnx_path = work_dir / "speaker_encoder.onnx"
    print(f"[2/6] Exporting to ONNX (fixed shape [1, {cfg.num_frames}, {cfg.num_mels}]) -> {onnx_path}")
    export_onnx(wrapper, cfg, onnx_path)

    print(f"[3/6] Converting ONNX -> TFLite via onnx2tf in {work_dir}")
    tflite_tmp_path = convert_onnx_to_tflite(onnx_path, work_dir)
    print(f"      onnx2tf produced: {tflite_tmp_path}")

    final_tflite_path = output_dir / "speaker_encoder.tflite"
    shutil.copyfile(tflite_tmp_path, final_tflite_path)
    print(f"[4/6] Copied final artifact -> {final_tflite_path} ({final_tflite_path.stat().st_size:,} bytes)")

    if not cfg.skip_verify:
        print("[5/6] Verifying TFLite loads and matches PyTorch reference numerically")
        verify_info = verify_tflite(final_tflite_path, wrapper, cfg)
        print(f"      input_details : {verify_info['input_details']}")
        print(f"      output_details: {verify_info['output_details']}")
        print(f"      cosine_similarity vs pytorch = {verify_info['cosine_similarity']:.6f}")
        print(f"      max_abs_diff vs pytorch      = {verify_info['max_abs_diff']:.6e}")
        if verify_info["cosine_similarity"] < 0.999:
            print("      WARNING: cosine similarity below 0.999 -- inspect conversion for correctness", file=sys.stderr)
    else:
        print("[5/6] Skipping verification (--skip-verify)")
        with torch.no_grad():
            dummy = torch.zeros(1, cfg.num_frames, cfg.num_mels)
            _ = wrapper(dummy)
        from ai_edge_litert.interpreter import Interpreter

        interpreter = Interpreter(model_path=str(final_tflite_path))
        interpreter.allocate_tensors()
        verify_info = {
            "input_details": interpreter.get_input_details(),
            "output_details": interpreter.get_output_details(),
            "cosine_similarity": None,
            "max_abs_diff": None,
        }

    meta_path = output_dir / "speaker_encoder_meta.json"
    print(f"[6/6] Writing metadata sidecar -> {meta_path}")
    write_meta_json(meta_path, final_tflite_path.name, verify_info, cfg, enc_dim)

    if not cfg.keep_intermediates:
        shutil.rmtree(work_dir, ignore_errors=True)

    print("\nDone.")
    print(f"  TFLite : {final_tflite_path}")
    print(f"  Meta   : {meta_path}")


if __name__ == "__main__":
    main()
