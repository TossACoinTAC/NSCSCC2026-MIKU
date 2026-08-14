#!/usr/bin/env python3
"""Compare two identity-compatible perf20 matrices."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ExperimentError, compare_perf_matrices


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = compare_perf_matrices(args.baseline, args.candidate)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    summary = result["summary"]
    print(
        f"perf20: {summary['baseline_total_cycles']} -> {summary['candidate_total_cycles']} "
        f"({summary['total_change_percent']:+.6f}%), geometric mean "
        f"{summary['geometric_mean_speedup']:.9f}x, exact={summary['exactly_equal']}"
    )
    for row in result["rows"]:
        print(
            f"{row['benchmark']:20s} {row['baseline_cycles']:9d} -> "
            f"{row['candidate_cycles']:9d} {row['change_percent']:+9.5f}%"
        )
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExperimentError as error:
        print(f"experiment compare failed: {error}", file=sys.stderr)
        raise SystemExit(1)
