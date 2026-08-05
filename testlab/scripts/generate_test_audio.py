#!/usr/bin/env python3
"""
Generate test tone and audio injection scripts for PSTN verification.
"""

import argparse
import math
import struct
import wave
from pathlib import Path


def generate_pcm_tone(output: Path, sample_rate: int = 8000, 
                       duration: float = 5.0, frequency: int = 440, 
                       amplitude: float = 0.5):
    """Generate PCM test tone."""
    
    num_samples = int(sample_rate * duration)
    
    with open(output, 'wb') as f:
        for i in range(num_samples):
            t = i / sample_rate
            sample = amplitude * 32767 * math.sin(2 * math.pi * frequency * t)
            f.write(struct.pack('<h', int(sample)))
    
    print(f"Generated PCM tone: {output}")
    print(f"  Sample rate: {sample_rate} Hz")
    print(f"  Duration: {duration} s")
    print(f"  Frequency: {frequency} Hz")
    print(f"  Amplitude: {amplitude}")


def generate_wav_tone(output: Path, sample_rate: int = 8000,
                      duration: float = 5.0, frequency: int = 440,
                      amplitude: float = 0.5):
    """Generate WAV test tone."""
    
    num_samples = int(sample_rate * duration)
    
    with wave.open(str(output), 'w') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)  # 16-bit
        wav.setframerate(sample_rate)
        
        for i in range(num_samples):
            t = i / sample_rate
            sample = amplitude * 32767 * math.sin(2 * math.pi * frequency * t)
            wav.writeframes(struct.pack('<h', int(sample)))
    
    print(f"Generated WAV tone: {output}")


def generate_dtmf_sequence(output: Path, sample_rate: int = 8000,
                           sequence: str = "1234567890",
                           tone_duration: float = 0.1,
                           gap_duration: float = 0.05):
    """Generate DTMF sequence."""
    
    dtmf_freq = {
        '1': (697, 1209), '2': (697, 1336), '3': (697, 1477),
        '4': (770, 1209), '5': (770, 1336), '6': (770, 1477),
        '7': (852, 1209), '8': (852, 1336), '9': (852, 1477),
        '*': (941, 1209), '0': (941, 1336), '#': (941, 1477),
    }
    
    samples = []
    
    tone_samples = int(sample_rate * tone_duration)
    gap_samples = int(sample_rate * gap_duration)
    
    for char in sequence:
        if char not in dtmf_freq:
            continue
        
        f1, f2 = dtmf_freq[char]
        
        for i in range(tone_samples):
            t = i / sample_rate
            sample = 0.25 * 32767 * (math.sin(2 * math.pi * f1 * t) + 
                                       math.sin(2 * math.pi * f2 * t))
            samples.append(int(sample))
        
        # Gap
        for _ in range(gap_samples):
            samples.append(0)
    
    with open(output, 'wb') as f:
        for sample in samples:
            f.write(struct.pack('<h', sample))
    
    print(f"Generated DTMF sequence: {output}")
    print(f"  Sequence: {sequence}")


def main():
    parser = argparse.ArgumentParser(description="Generate test audio files")
    parser.add_argument("--output", default="testlab/pstn-evidence/test_tone.pcm",
                       help="Output file path")
    parser.add_argument("--format", choices=["pcm", "wav"], default="pcm",
                       help="Output format")
    parser.add_argument("--sample-rate", type=int, default=8000,
                       help="Sample rate (Hz)")
    parser.add_argument("--duration", type=float, default=5.0,
                       help="Duration (seconds)")
    parser.add_argument("--frequency", type=int, default=440,
                       help="Tone frequency (Hz)")
    parser.add_argument("--amplitude", type=float, default=0.5,
                       help="Amplitude (0.0-1.0)")
    parser.add_argument("--dtmf", help="Generate DTMF sequence instead of tone")
    
    args = parser.parse_args()
    
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    
    if args.dtmf:
        generate_dtmf_sequence(
            output=output.with_suffix('.pcm'),
            sample_rate=args.sample_rate,
            sequence=args.dtmf
        )
    elif args.format == "wav":
        generate_wav_tone(
            output=output,
            sample_rate=args.sample_rate,
            duration=args.duration,
            frequency=args.frequency,
            amplitude=args.amplitude
        )
    else:
        generate_pcm_tone(
            output=output,
            sample_rate=args.sample_rate,
            duration=args.duration,
            frequency=args.frequency,
            amplitude=args.amplitude
        )


if __name__ == "__main__":
    main()
