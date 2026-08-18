#!/usr/bin/env python3
"""Combine matching perf20 and direct-full Vivado evidence into one scorecard."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ExperimentError, load_json, parse_key_values


class OptimizationMetricsError(ExperimentError):
    pass


def parse_design_timing_summary(path: Path) -> dict[str, float | int]:
    text = path.read_text(encoding="utf-8", errors="replace")
    heading = text.find("| Design Timing Summary")
    if heading < 0:
        raise OptimizationMetricsError(f"缺少 Design Timing Summary: {path}")
    body = text[heading:]
    match = re.search(
        r"(?m)^\s*([-+]?[0-9.]+)\s+([-+]?[0-9.]+)\s+(\d+)\s+\d+\s+"
        r"([-+]?[0-9.]+)\s+([-+]?[0-9.]+)\s+(\d+)\s+\d+",
        body,
    )
    if match is None:
        raise OptimizationMetricsError(f"无法解析 Design Timing Summary: {path}")
    return {
        "setup_wns_ns": float(match.group(1)),
        "setup_tns_ns": float(match.group(2)),
        "setup_failing_endpoints": int(match.group(3)),
        "hold_wns_ns": float(match.group(4)),
        "hold_tns_ns": float(match.group(5)),
        "hold_failing_endpoints": int(match.group(6)),
    }


def parse_utilization(path: Path) -> dict[str, float | int]:
    text = path.read_text(encoding="utf-8", errors="replace")
    labels = {
        "lut": "Slice LUTs",
        "ff": "Slice Registers",
        "bram": "Block RAM Tile",
        "dsp": "DSPs",
    }
    result: dict[str, float | int] = {}
    for key, label in labels.items():
        match = re.search(
            rf"(?m)^\|\s*{re.escape(label)}\s*\|\s*([0-9.]+)\s*\|",
            text,
        )
        if match is None:
            raise OptimizationMetricsError(f"资源报告缺少 {label}: {path}")
        raw = match.group(1)
        result[key] = float(raw) if "." in raw else int(raw)
    return result


def load_timing_analysis(path: Path) -> dict[str, Any]:
    document = load_json(path)
    if not isinstance(document.get("paths"), list) or not isinstance(
        document.get("distribution"), dict
    ):
        raise OptimizationMetricsError(f"timing analysis schema 错误: {path}")
    return document


def load_perf_comparison(path: Path) -> dict[str, Any]:
    document = load_json(path)
    summary = document.get("summary")
    if not isinstance(summary, dict):
        raise OptimizationMetricsError(f"perf comparison schema 错误: {path}")
    speedup = summary.get("geometric_mean_speedup")
    if not isinstance(speedup, (int, float)) or speedup <= 0:
        raise OptimizationMetricsError(f"perf comparison 缺少合法几何平均: {path}")
    for key in ("baseline_total_cycles", "candidate_total_cycles"):
        value = summary.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise OptimizationMetricsError(f"perf comparison 缺少合法 {key}: {path}")
    return document


def fproxy(wns_ns: float, period_ns: float) -> float:
    effective_period = period_ns - wns_ns
    if effective_period <= 0:
        raise OptimizationMetricsError("WNS 导致非正 effective period")
    return 1000.0 / effective_period


def build_scorecard(
    comparison: dict[str, Any],
    baseline_timing: dict[str, float | int],
    candidate_timing: dict[str, float | int],
    baseline_paths: dict[str, Any],
    candidate_paths: dict[str, Any],
    baseline_utilization: dict[str, float | int],
    candidate_utilization: dict[str, float | int],
    period_ns: float,
) -> dict[str, Any]:
    comparison_summary = comparison["summary"]
    ipc_factor = float(comparison_summary["geometric_mean_speedup"])
    baseline_total_cycles = int(comparison_summary["baseline_total_cycles"])
    candidate_total_cycles = int(comparison_summary["candidate_total_cycles"])
    if baseline_total_cycles <= 0 or candidate_total_cycles <= 0:
        raise OptimizationMetricsError("perf comparison 总周期必须为正")
    baseline_fproxy = fproxy(float(baseline_timing["setup_wns_ns"]), period_ns)
    candidate_fproxy = fproxy(float(candidate_timing["setup_wns_ns"]), period_ns)
    return {
        "schema_version": 2,
        "clock_period_ns": period_ns,
        "ipc_factor": ipc_factor,
        "total_cycle_factor": baseline_total_cycles / candidate_total_cycles,
        "total_cycles": {
            "baseline": baseline_total_cycles,
            "candidate": candidate_total_cycles,
        },
        "fproxy_mhz": {"baseline": baseline_fproxy, "candidate": candidate_fproxy},
        "system_score_proxy": ipc_factor * candidate_fproxy / baseline_fproxy,
        "timing": {"baseline": baseline_timing, "candidate": candidate_timing},
        "top_paths": {
            "baseline": baseline_paths["distribution"],
            "candidate": candidate_paths["distribution"],
            "baseline_groups": baseline_paths["groups"],
            "candidate_groups": candidate_paths["groups"],
        },
        "utilization": {
            "baseline": baseline_utilization,
            "candidate": candidate_utilization,
            "delta": {
                key: candidate_utilization[key] - baseline_utilization[key]
                for key in baseline_utilization
            },
        },
        "cycle_transparent": bool(comparison["summary"].get("exactly_equal")),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--comparison", type=Path, required=True)
    parser.add_argument("--baseline-timing-summary", type=Path, required=True)
    parser.add_argument("--candidate-timing-summary", type=Path, required=True)
    parser.add_argument("--baseline-paths", type=Path, required=True)
    parser.add_argument("--candidate-paths", type=Path, required=True)
    parser.add_argument("--baseline-utilization", type=Path, required=True)
    parser.add_argument("--candidate-utilization", type=Path, required=True)
    parser.add_argument("--clock-period-ns", type=float, default=10.0)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.clock_period_ns <= 0:
        raise OptimizationMetricsError("clock period 必须为正")
    result = build_scorecard(
        load_perf_comparison(args.comparison),
        parse_design_timing_summary(args.baseline_timing_summary),
        parse_design_timing_summary(args.candidate_timing_summary),
        load_timing_analysis(args.baseline_paths),
        load_timing_analysis(args.candidate_paths),
        parse_utilization(args.baseline_utilization),
        parse_utilization(args.candidate_utilization),
        args.clock_period_ns,
    )
    result["inputs"] = {
        key: str(value.resolve())
        for key, value in vars(args).items()
        if isinstance(value, Path)
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"IPC={result['ipc_factor']:.9f}x "
        f"TotalCycleFactor={result['total_cycle_factor']:.9f}x "
        f"Fproxy={result['fproxy_mhz']['baseline']:.3f}->"
        f"{result['fproxy_mhz']['candidate']:.3f}MHz "
        f"SystemScoreProxy={result['system_score_proxy']:.9f}x"
    )
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, OptimizationMetricsError) as error:
        print(f"optimization metrics failed: {error}", file=sys.stderr)
        raise SystemExit(1)
