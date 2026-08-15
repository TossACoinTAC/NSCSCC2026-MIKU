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
            groups[category] = {
                "count": len(selected),
                "worst_slack_ns": min(item["slack_ns"] for item in selected),
                "average_slack_ns": statistics.fmean(item["slack_ns"] for item in selected),
                "average_route_percent": statistics.fmean(item["route_percent"] for item in selected),
                "ranks": [item["rank"] for item in selected],
            }
        else:
            groups[category] = {"count": 0, "worst_slack_ns": None, "average_slack_ns": None, "average_route_percent": None, "ranks": []}
    return {"schema_version": 1, "path_count": len(paths), "groups": groups, "paths": paths}


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
