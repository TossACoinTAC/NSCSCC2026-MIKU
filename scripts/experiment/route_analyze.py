#!/usr/bin/env python3
"""Extract stable physical-health metrics from a completed Vivado route log."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any


class RouteAnalysisError(ValueError):
    pass


def duration_seconds(value: str) -> int:
    match = re.fullmatch(r"(\d+):(\d{2}):(\d{2})", value)
    if match is None:
        raise RouteAnalysisError(f"非法 Vivado duration: {value}")
    hours, minutes, seconds = (int(part) for part in match.groups())
    return hours * 3600 + minutes * 60 + seconds


def parse_route_log(path: Path) -> dict[str, Any]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    iterations: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    awaiting_duration: dict[str, Any] | None = None
    congestion_samples: list[dict[str, Any]] = []
    route_design_seconds: int | None = None
    implementation_seconds: int | None = None
    post_route_setup_wns_ns: float | None = None
    post_route_tns_ns: float | None = None
    post_route_hold_wns_ns: float | None = None
    post_route_ths_ns: float | None = None
    congestion_warning_count = 0
    failed_nets_samples: list[int] = []
    all_overlap_samples: list[int] = []
    skipped_very_high_fanout: list[dict[str, Any]] = []

    iteration_header = re.compile(r"^Phase\s+([0-9.]+)\s+Global Iteration\s+(\d+)$")
    overlap_line = re.compile(r"Number of Nodes with overlaps\s*=\s*(\d+)")
    timing_line = re.compile(
        r"Intermediate Timing Summary\s*\|\s*WNS=([-+0-9.]+)\s*\|\s*"
        r"TNS=([-+0-9.]+)\s*\|\s*WHS=([-+0-9.N/A]+)\s*\|\s*THS=([-+0-9.N/A]+)"
    )
    post_timing_line = re.compile(
        r"Post Routing Timing Summary\s*\|\s*WNS=([-+0-9.]+)\s*\|\s*"
        r"TNS=([-+0-9.]+)\s*\|\s*WHS=([-+0-9.]+)\s*\|\s*THS=([-+0-9.]+)"
    )
    elapsed_line = re.compile(r"elapsed\s*=\s*(\d+:\d{2}:\d{2})")
    congestion_line = re.compile(
        r"^(North|South|East|West) Dir\s+\S+\s+Area,\s+Max Cong\s*=\s*([0-9.]+)%"
    )
    skipped_fanout_line = re.compile(
        r"Very high fanout net '([^']+)' is not considered.*?"
        r"changed from (\d+) to (\d+) due to a timing constraint"
    )

    for line_number, line in enumerate(lines, start=1):
        stripped = line.strip()
        header = iteration_header.match(stripped)
        if header is not None:
            current = {
                "phase": header.group(1),
                "iteration": int(header.group(2)),
                "overlap_samples": [],
                "peak_overlaps": 0,
                "final_overlaps": None,
                "elapsed_seconds": None,
                "intermediate_setup_wns_ns": None,
                "intermediate_tns_ns": None,
            }
            iterations.append(current)
            awaiting_duration = None
            continue

        overlap = overlap_line.search(stripped)
        if overlap is not None:
            all_overlap_samples.append(int(overlap.group(1)))

        if current is not None:
            if overlap is not None:
                value = int(overlap.group(1))
                current["overlap_samples"].append(value)
                current["peak_overlaps"] = max(current["peak_overlaps"], value)
                current["final_overlaps"] = value
            timing = timing_line.search(stripped)
            if timing is not None:
                current["intermediate_setup_wns_ns"] = float(timing.group(1))
                current["intermediate_tns_ns"] = float(timing.group(2))
            closing = re.match(
                rf"^Phase\s+{re.escape(current['phase'])}\s+Global Iteration\s+"
                rf"{current['iteration']}\s+\|",
                stripped,
            )
            if closing is not None:
                awaiting_duration = current
                current = None

        if awaiting_duration is not None:
            elapsed = elapsed_line.search(stripped)
            if elapsed is not None:
                awaiting_duration["elapsed_seconds"] = duration_seconds(elapsed.group(1))
                awaiting_duration = None

        congestion = congestion_line.match(stripped)
        if congestion is not None:
            congestion_samples.append(
                {
                    "direction": congestion.group(1).lower(),
                    "percent": float(congestion.group(2)),
                    "line": line_number,
                }
            )
        if "[Route 35-447] Congestion is preventing the router" in stripped:
            congestion_warning_count += 1

        skipped_fanout = skipped_fanout_line.search(stripped)
        if skipped_fanout is not None:
            skipped_very_high_fanout.append(
                {
                    "net": skipped_fanout.group(1),
                    "original_fanout": int(skipped_fanout.group(2)),
                    "timing_eligible_fanout": int(skipped_fanout.group(3)),
                }
            )

        post_timing = post_timing_line.search(stripped)
        if post_timing is not None:
            post_route_setup_wns_ns = float(post_timing.group(1))
            post_route_tns_ns = float(post_timing.group(2))
            post_route_hold_wns_ns = float(post_timing.group(3))
            post_route_ths_ns = float(post_timing.group(4))

        failed_nets = re.search(r"Number of Failed Nets\s*=\s*(\d+)", stripped)
        if failed_nets is not None:
            failed_nets_samples.append(int(failed_nets.group(1)))

        route_duration = re.search(
            r"route_design:\s+Time.*?elapsed\s*=\s*(\d+:\d{2}:\d{2})", stripped
        )
        if route_duration is not None:
            route_design_seconds = duration_seconds(route_duration.group(1))
        implementation_duration = re.search(
            r"wait_on_runs:\s+Time.*?elapsed\s*=\s*(\d+:\d{2}:\d{2})", stripped
        )
        if implementation_duration is not None:
            implementation_seconds = duration_seconds(implementation_duration.group(1))

    if not iterations:
        raise RouteAnalysisError(f"日志中没有 Global Iteration: {path}")
    if route_design_seconds is None:
        raise RouteAnalysisError(f"日志中没有完成的 route_design duration: {path}")
    if post_route_setup_wns_ns is None or post_route_hold_wns_ns is None:
        raise RouteAnalysisError(f"日志中没有 Post Routing Timing Summary: {path}")

    iteration_overlap_samples = [
        sample
        for iteration in iterations
        for sample in iteration["overlap_samples"]
    ]
    maximum_by_direction: dict[str, float] = {}
    latest_by_direction: dict[str, float] = {}
    for sample in congestion_samples:
        direction = sample["direction"]
        percent = sample["percent"]
        maximum_by_direction[direction] = max(maximum_by_direction.get(direction, 0.0), percent)
        latest_by_direction[direction] = percent

    return {
        "schema_version": 1,
        "route_design_seconds": route_design_seconds,
        "implementation_seconds": implementation_seconds,
        "congestion_warning_count": congestion_warning_count,
        "peak_overlaps": max(all_overlap_samples, default=0),
        "final_overlaps": all_overlap_samples[-1] if all_overlap_samples else None,
        "overlap_sample_count": len(all_overlap_samples),
        "iteration_overlap_sample_count": len(iteration_overlap_samples),
        "iterations_with_overlaps": sum(
            1 for iteration in iterations if iteration["peak_overlaps"] > 0
        ),
        "global_iterations": iterations,
        "congestion": {
            "report_count": len(congestion_samples) // 4,
            "maximum_percent": max((sample["percent"] for sample in congestion_samples), default=None),
            "maximum_by_direction_percent": maximum_by_direction,
            "latest_by_direction_percent": latest_by_direction,
        },
        "very_high_fanout": {
            "skipped_count": len(skipped_very_high_fanout),
            "maximum_original_fanout": max(
                (item["original_fanout"] for item in skipped_very_high_fanout),
                default=None,
            ),
            "skipped": skipped_very_high_fanout,
        },
        "post_route_timing": {
            "setup_wns_ns": post_route_setup_wns_ns,
            "tns_ns": post_route_tns_ns,
            "hold_wns_ns": post_route_hold_wns_ns,
            "ths_ns": post_route_ths_ns,
        },
        "final_failed_nets": failed_nets_samples[-1] if failed_nets_samples else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = parse_route_log(args.log)
    result["log"] = str(args.log.resolve())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        "route health: "
        f"peak_overlaps={result['peak_overlaps']} "
        f"iterations={result['iterations_with_overlaps']} "
        f"route_seconds={result['route_design_seconds']} "
        f"max_congestion={result['congestion']['maximum_percent']}% "
        f"skipped_vhfn={result['very_high_fanout']['skipped_count']}"
    )
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RouteAnalysisError) as error:
        print(f"route analysis failed: {error}", file=sys.stderr)
        raise SystemExit(1)
