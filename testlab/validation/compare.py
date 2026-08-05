#!/usr/bin/env python3
"""
Transport validation comparison suite.
Compares simulated, Kotlin, and C++ transport implementations.
"""

import argparse
import json
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, List, Optional
import subprocess
import statistics


@dataclass
class LatencyMetrics:
    p50: float
    p95: float
    p99: float
    min: float
    max: float
    mean: float
    count: int


@dataclass
class PacketMetrics:
    sent: int
    received: int
    dropped: int
    out_of_order: int
    duplicates: int
    avg_jitter_ms: float


@dataclass
class ValidationResult:
    passed: bool
    latency: LatencyMetrics
    packets: PacketMetrics
    errors: List[str]
    warnings: List[str]
    duration_ms: float
    timestamp: str


class SimulatedTransportValidator:
    """Run simulated transport validation with packet loss and latency injection."""
    
    def run(self, duration_seconds: int = 60, packet_loss_rate: float = 0.0) -> ValidationResult:
        print(f"Running simulated transport validation (duration: {duration_seconds}s, loss: {packet_loss_rate})")
        
        latencies = []
        packets_sent = 0
        packets_received = 0
        packets_dropped = 0
        
        start_time = time.time()
        
        # Simulate 1000 packets
        for i in range(1000):
            packets_sent += 1
            
            # Simulate network latency (log-normal distribution)
            import random
            base_latency = random.lognormvariate(4.5, 0.5)  # ~90ms mean
            jitter = random.gauss(0, 10)
            latency = max(10, base_latency + jitter)
            latencies.append(latency)
            
            # Simulate packet loss
            if random.random() < packet_loss_rate:
                packets_dropped += 1
            else:
                packets_received += 1
        
        duration_ms = (time.time() - start_time) * 1000
        
        latency_metrics = self._calculate_latency_metrics(latencies)
        packet_metrics = PacketMetrics(
            sent=packets_sent,
            received=packets_received,
            dropped=packets_dropped,
            out_of_order=0,
            duplicates=0,
            avg_jitter_ms=statistics.stdev(latencies) if len(latencies) > 1 else 0.0
        )
        
        errors = []
        warnings = []
        
        if packet_metrics.dropped > 0:
            errors.append(f"Packet drops detected: {packet_metrics.dropped}")
        
        if latency_metrics.p95 > 150:
            errors.append(f"p95 latency exceeds 150ms: {latency_metrics.p95:.2f}ms")
        
        return ValidationResult(
            passed=len(errors) == 0,
            latency=latency_metrics,
            packets=packet_metrics,
            errors=errors,
            warnings=warnings,
            duration_ms=duration_ms,
            timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        )
    
    def _calculate_latency_metrics(self, latencies: List[float]) -> LatencyMetrics:
        if not latencies:
            return LatencyMetrics(0, 0, 0, 0, 0, 0, 0)
        
        sorted_latencies = sorted(latencies)
        n = len(sorted_latencies)
        
        return LatencyMetrics(
            p50=sorted_latencies[n // 2],
            p95=sorted_latencies[int(n * 0.95)],
            p99=sorted_latencies[int(n * 0.99)],
            min=sorted_latencies[0],
            max=sorted_latencies[-1],
            mean=statistics.mean(latencies),
            count=n
        )


class KotlinTransportValidator:
    """Run Kotlin-based transport validation via Gradle."""
    
    def run(self, gradle_task: str = ":telephony-plivo:transportValidation") -> ValidationResult:
        print(f"Running Kotlin transport validation: {gradle_task}")
        
        start_time = time.time()
        
        try:
            result = subprocess.run(
                ["./gradlew", gradle_task, "--stacktrace"],
                cwd="apps/android",
                capture_output=True,
                text=True,
                timeout=300
            )
            
            duration_ms = (time.time() - start_time) * 1000
            
            # Parse Gradle output for metrics
            output_file = Path("apps/android/telephony-plivo/build/transport-validation.json")
            if output_file.exists():
                with open(output_file) as f:
                    data = json.load(f)
                
                return ValidationResult(
                    passed=result.returncode == 0,
                    latency=LatencyMetrics(**data.get("latency", {})),
                    packets=PacketMetrics(**data.get("packets", {})),
                    errors=data.get("errors", []),
                    warnings=data.get("warnings", []),
                    duration_ms=duration_ms,
                    timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                )
            else:
                return ValidationResult(
                    passed=result.returncode == 0,
                    latency=LatencyMetrics(0, 0, 0, 0, 0, 0, 0),
                    packets=PacketMetrics(0, 0, 0, 0, 0, 0.0),
                    errors=[f"Gradle task failed: {result.stderr}"],
                    warnings=[],
                    duration_ms=duration_ms,
                    timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                )
        
        except subprocess.TimeoutExpired:
            return ValidationResult(
                passed=False,
                latency=LatencyMetrics(0, 0, 0, 0, 0, 0, 0),
                packets=PacketMetrics(0, 0, 0, 0, 0, 0.0),
                errors=["Kotlin validation timed out after 5 minutes"],
                warnings=[],
                duration_ms=(time.time() - start_time) * 1000,
                timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            )


class CppTransportValidator:
    """Run C++ native transport validation."""
    
    def run(self, test_executable: str = "./transport_validation") -> ValidationResult:
        print(f"Running C++ transport validation: {test_executable}")
        
        start_time = time.time()
        
        try:
            result = subprocess.run(
                [test_executable, "--output=json"],
                cwd="apps/android/native-media/build",
                capture_output=True,
                text=True,
                timeout=300
            )
            
            duration_ms = (time.time() - start_time) * 1000
            
            # Parse JSON output from C++ test
            try:
                if result.stdout:
                    data = json.loads(result.stdout)
                else:
                    output_file = Path("apps/android/native-media/build/cpp-validation.json")
                    if output_file.exists():
                        with open(output_file) as f:
                            data = json.load(f)
                    else:
                        raise ValueError("No validation output found")
                
                return ValidationResult(
                    passed=data.get("passed", False),
                    latency=LatencyMetrics(**data.get("latency", {})),
                    packets=PacketMetrics(**data.get("packets", {})),
                    errors=data.get("errors", []),
                    warnings=data.get("warnings", []),
                    duration_ms=duration_ms,
                    timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                )
            except (json.JSONDecodeError, ValueError) as e:
                return ValidationResult(
                    passed=False,
                    latency=LatencyMetrics(0, 0, 0, 0, 0, 0, 0),
                    packets=PacketMetrics(0, 0, 0, 0, 0, 0.0),
                    errors=[f"Failed to parse C++ validation output: {e}"],
                    warnings=[],
                    duration_ms=duration_ms,
                    timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                )
        
        except subprocess.TimeoutExpired:
            return ValidationResult(
                passed=False,
                latency=LatencyMetrics(0, 0, 0, 0, 0, 0, 0),
                packets=PacketMetrics(0, 0, 0, 0, 0, 0.0),
                errors=["C++ validation timed out after 5 minutes"],
                warnings=[],
                duration_ms=(time.time() - start_time) * 1000,
                timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            )


def main():
    parser = argparse.ArgumentParser(description="Transport validation comparison suite")
    parser.add_argument("--mode", choices=["simulated", "kotlin", "cpp", "all"], 
                        default="all", help="Validation mode")
    parser.add_argument("--output", type=str, default="validation-results.json",
                        help="Output JSON file path")
    parser.add_argument("--duration", type=int, default=60,
                        help="Duration in seconds for simulated mode")
    parser.add_argument("--packet-loss", type=float, default=0.0,
                        help="Packet loss rate for simulated mode")
    
    args = parser.parse_args()
    
    results = {}
    
    if args.mode in ["simulated", "all"]:
        validator = SimulatedTransportValidator()
        results["simulated"] = asdict(validator.run(args.duration, args.packet_loss))
    
    if args.mode in ["kotlin", "all"]:
        validator = KotlinTransportValidator()
        results["kotlin"] = asdict(validator.run())
    
    if args.mode in ["cpp", "all"]:
        validator = CppTransportValidator()
        results["cpp"] = asdict(validator.run())
    
    # Write results
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, "w") as f:
        json.dump(results, f, indent=2)
    
    print(f"\nValidation results written to: {output_path}")
    
    # Print summary
    for mode, result in results.items():
        status = "✅ PASSED" if result["passed"] else "❌ FAILED"
        print(f"{mode.upper()}: {status}")
        print(f"  Latency: p50={result['latency']['p50']:.2f}ms, p95={result['latency']['p95']:.2f}ms")
        print(f"  Packets: dropped={result['packets']['dropped']}")
        if result["errors"]:
            print(f"  Errors: {len(result['errors'])}")
    
    # Exit with error if any validation failed
    if not all(r["passed"] for r in results.values()):
        sys.exit(1)


if __name__ == "__main__":
    main()
