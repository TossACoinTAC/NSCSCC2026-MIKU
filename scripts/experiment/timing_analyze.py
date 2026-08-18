#!/usr/bin/env python3
"""Classify Vivado top timing paths into stable microarchitecture families."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import statistics
import sys
from typing import Any


class TimingAnalysisError(ValueError):
    pass


CATEGORY_RULES = (
    ("predictor", ("targetpredictor", "branchpredictor", "pht", "btb", "speculativeras")),
    ("IQ", ("issuequeue", "issuequeues")),
    ("ROB/CSR", ("/rob/", "reorderbuffer", "systemarea_csr", "/csr")),
    ("LSQ", ("loadstorequeue", "storedataqueue")),
    ("cache/L2", ("cachehierarchy", "cachearray", "/l1i/", "/l1d/", "/l2/")),
    ("frontend", ("/frontend/", "ooofrontend")),
)


def classify(source: str, destination: str) -> str:
    text = f"{source} {destination}".lower()
    if "u_cpu/" not in text:
        return "platform"
    for category, tokens in CATEGORY_RULES:
        if any(token in text for token in tokens):
            return category
    return "other CPU"


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percent
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def path_distribution(paths: list[dict[str, Any]]) -> dict[str, Any]:
    slacks = [item["slack_ns"] for item in paths]
    route_percentages = [item["route_percent"] for item in paths]
    logic_delays = [item["logic_delay_ns"] for item in paths]
    route_delays = [item["route_delay_ns"] for item in paths]
    return {
        "count": len(paths),
        "worst_slack_ns": min(slacks) if slacks else None,
        "median_slack_ns": statistics.median(slacks) if slacks else None,
        # P90 is the 90th percentile of delay pressure, expressed as the
        # 10th percentile of slack because smaller slack is worse.
        "p90_pressure_slack_ns": percentile(slacks, 0.10),
        "slack_below_0_1_ns": sum(value < 0.1 for value in slacks),
        "slack_below_0_2_ns": sum(value < 0.2 for value in slacks),
        "average_route_percent": statistics.fmean(route_percentages)
        if route_percentages else None,
        "average_logic_delay_ns": statistics.fmean(logic_delays) if logic_delays else None,
        "average_route_delay_ns": statistics.fmean(route_delays) if route_delays else None,
    }


def parse_timing_report(path: Path) -> list[dict[str, Any]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    starts = list(re.finditer(r"(?m)^Slack \((?:VIOLATED|MET)\)\s*:\s*([-+]?[0-9.]+)ns", text))
    paths: list[dict[str, Any]] = []
    for index, start in enumerate(starts):
        block = text[start.start() : starts[index + 1].start() if index + 1 < len(starts) else len(text)]
        source = re.search(r"(?m)^\s*Source:\s+(\S+)", block)
        destination = re.search(r"(?m)^\s*Destination:\s+(\S+)", block)
        delay = re.search(
            r"Data Path Delay:\s*([0-9.]+)ns\s*\(logic\s*([0-9.]+)ns\s*\([0-9.]+%\)\s*route\s*([0-9.]+)ns\s*\(([0-9.]+)%\)\)",
            block,
        )
        levels = re.search(r"Logic Levels:\s*(\d+)", block)
        if source is None or destination is None or delay is None or levels is None:
            raise TimingAnalysisError(f"无法解析 timing path #{index + 1}: {path}")
        src, dst = source.group(1), destination.group(1)
        paths.append({
            "rank": index + 1,
            "slack_ns": float(start.group(1)),
            "source": src,
            "destination": dst,
            "data_path_delay_ns": float(delay.group(1)),
            "logic_delay_ns": float(delay.group(2)),
            "route_delay_ns": float(delay.group(3)),
            "route_percent": float(delay.group(4)),
            "logic_levels": int(levels.group(1)),
            "category": classify(src, dst),
        })
    if not paths:
        raise TimingAnalysisError(f"报告中没有 timing path: {path}")
    return paths


def summarize(paths: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, Any] = {}
    for category in ("frontend", "predictor", "IQ", "ROB/CSR", "LSQ", "cache/L2", "platform", "other CPU"):
        selected = [item for item in paths if item["category"] == category]
        if selected:
            groups[category] = path_distribution(selected)
            groups[category]["average_slack_ns"] = statistics.fmean(
                item["slack_ns"] for item in selected
            )
            groups[category]["ranks"] = [item["rank"] for item in selected]
        else:
            groups[category] = {
                **path_distribution([]),
                "average_slack_ns": None,
                "ranks": [],
            }
    return {
        "schema_version": 2,
        "path_count": len(paths),
        "distribution": path_distribution(paths),
        "groups": groups,
        "paths": paths,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(parse_timing_report(args.report))
    result["report"] = str(args.report.resolve())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    for category, group in result["groups"].items():
        worst = "n/a" if group["worst_slack_ns"] is None else f"{group['worst_slack_ns']:+.3f}ns"
        print(f"{category:10s} count={group['count']:2d} worst={worst}")
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, TimingAnalysisError) as error:
        print(f"timing analysis failed: {error}", file=sys.stderr)
        raise SystemExit(1)
