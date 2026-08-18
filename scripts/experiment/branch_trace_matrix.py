#!/usr/bin/env python3
"""Attribute branch-trace-v2 events to the m01 benchmark ROI.

The trace is intentionally streamed twice per workload: the first pass finds
the counter-read markers, and the second pass evaluates only the interval
between the first and last marker.  This keeps startup code and post-ROI
cleanup out of predictor statistics without retaining a multi-million-event
trace in memory.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


BRANCH_TYPES = {
    0: "conditional",
    1: "direct",
    2: "indirect",
    3: "return",
    4: "call",
}
LOCAL_TABLE_SIZES = (64, 128, 256, 512, 1024, 2048, 4096)


def _integer(value: Any, key: str) -> int:
    if not isinstance(value, int) or value < 0:
        raise ValueError(f"{key} must be a non-negative integer")
    return value


def _load_records(path: Path) -> Iterable[dict[str, Any]]:
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON at {path}:{line_number}: {error}") from error
            if not isinstance(record, dict):
                raise ValueError(f"trace record at {path}:{line_number} is not an object")
            yield record


def _trace_roi(path: Path) -> tuple[dict[str, Any], int, int, int]:
    header: dict[str, Any] | None = None
    markers: list[int] = []
    for record in _load_records(path):
        kind = record.get("kind")
        if kind == "header":
            if header is not None:
                raise ValueError(f"trace contains multiple headers: {path}")
            header = record
        elif kind == "marker":
            markers.append(_integer(record.get("cycle"), "marker.cycle"))
        elif kind != "branch":
            raise ValueError(f"unknown trace record kind in {path}: {kind!r}")

    if header is None or header.get("format") != "miku-branch-trace-v2":
        raise ValueError(f"ROI matrix requires a miku-branch-trace-v2 header: {path}")
    if len(markers) < 2:
        raise ValueError(f"ROI matrix requires at least two markers: {path}")
    first, last = markers[0], markers[-1]
    if last <= first:
        raise ValueError(f"marker cycles are not increasing: {path}")
    return header, first, last, len(markers)


def _branch_type(record: dict[str, Any]) -> str:
    value = _integer(record.get("predictor_type"), "predictor_type")
    return BRANCH_TYPES.get(value, "unknown")


def _hex_integer(value: Any, key: str) -> int:
    if not isinstance(value, str):
        raise ValueError(f"{key} must be a hexadecimal string")
    try:
        return int(value, 16)
    except ValueError as error:
        raise ValueError(f"{key} must be hexadecimal: {value!r}") from error


def _saturating_update(state: int, taken: bool) -> int:
    if taken:
        return min(3, state + 1)
    return max(0, state - 1)


def _new_stats() -> dict[str, Any]:
    return {
        "branch_events": 0,
        "by_type": Counter(),
        "pht_valid": 0,
        "pht_direction_correct_inferred": 0,
        "conditional_valid": 0,
        "conditional_direction_correct_inferred": 0,
        "weak_conditional": 0,
        "weak_conditional_pht_correct_inferred": 0,
        "weak_conditional_static_btfnt_correct": 0,
        "weak_conditional_by_pht_state": Counter(),
        "weak_conditional_by_pc": Counter(),
        "weak_conditional_mispredicted_pcs": Counter(),
        "weak_conditional_by_pht_index": Counter(),
        "local_replay": {
            str(size): {
                "weak_correct": 0,
                "weak_events": 0,
            }
            for size in LOCAL_TABLE_SIZES
        },
        "roi_branch_cycles": [],
    }


def _finalize_stats(stats: dict[str, Any]) -> dict[str, Any]:
    total = stats["branch_events"]
    conditional = stats["conditional_valid"]
    weak = stats["weak_conditional"]
    stats["by_type"] = dict(sorted(stats["by_type"].items()))
    stats["weak_conditional_by_pht_state"] = dict(
        sorted(stats["weak_conditional_by_pht_state"].items())
    )
    stats["weak_conditional_by_pc"] = dict(
        sorted(stats["weak_conditional_by_pc"].items())
    )
    stats["weak_conditional_mispredicted_pcs"] = dict(
        sorted(stats["weak_conditional_mispredicted_pcs"].items())
    )
    stats["weak_conditional_by_pht_index"] = dict(
        sorted(stats["weak_conditional_by_pht_index"].items())
    )
    stats["branch_events_inferred_ratio"] = (
        stats["pht_direction_correct_inferred"] / stats["pht_valid"]
        if stats["pht_valid"]
        else 0.0
    )
    stats["conditional_inferred_accuracy"] = (
        stats["conditional_direction_correct_inferred"] / conditional
        if conditional
        else 0.0
    )
    stats["weak_pht_inferred_accuracy"] = (
        stats["weak_conditional_pht_correct_inferred"] / weak if weak else 0.0
    )
    stats["weak_static_btfnt_accuracy"] = (
        stats["weak_conditional_static_btfnt_correct"] / weak if weak else 0.0
    )
    for result in stats["local_replay"].values():
        result["weak_accuracy"] = (
            result["weak_correct"] / result["weak_events"]
            if result["weak_events"]
            else 0.0
        )
    return stats


def _process_trace(trace_path: Path) -> dict[str, Any]:
    header, first_marker, last_marker, marker_count = _trace_roi(trace_path)
    m01_path = trace_path.with_name("m01-counters.json")
    try:
        m01 = json.loads(m01_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read m01 counters beside {trace_path}: {error}") from error
    expected_cycles = _integer(m01.get("cycles"), "m01.cycles")
    expected_retired = _integer(m01.get("branch", {}).get("retired"), "m01.branch.retired")
    roi_cycles = last_marker - first_marker - 1
    if roi_cycles != expected_cycles:
        raise ValueError(
            f"ROI cycle mismatch for {trace_path}: marker interval {roi_cycles}, "
            f"m01 {expected_cycles}"
        )

    stats = _new_stats()
    local_states = {size: [1] * size for size in LOCAL_TABLE_SIZES}
    per_pc_states: dict[int, int] = {}
    for record in _load_records(trace_path):
        if record.get("kind") != "branch":
            continue
        cycle = _integer(record.get("cycle"), "branch.cycle")
        if not first_marker < cycle < last_marker:
            continue
        branch_type = _branch_type(record)
        actual_taken = bool(_integer(record.get("actual_taken"), "actual_taken"))
        pht_state = _integer(record.get("pht_state"), "pht_state")
        pht_valid = _integer(record.get("pht_valid"), "pht_valid")
        pht_index = _integer(record.get("pht_index"), "pht_index")
        pc_string = record.get("pc")
        pc = _hex_integer(pc_string, "pc")
        if pht_state > 3 or pht_valid > 1:
            raise ValueError(f"invalid PHT state/valid in {trace_path}")
        stats["branch_events"] += 1
        stats["by_type"][branch_type] += 1
        if pht_valid:
            stats["pht_valid"] += 1
            pht_prediction = pht_state >= 2
            stats["pht_direction_correct_inferred"] += pht_prediction == actual_taken
        if branch_type != "conditional" or not pht_valid:
            continue
        stats["conditional_valid"] += 1
        pht_prediction = pht_state >= 2
        stats["conditional_direction_correct_inferred"] += pht_prediction == actual_taken
        low = pht_state in (1, 2)
        for size in LOCAL_TABLE_SIZES:
            index = (pc >> 2) & (size - 1)
            prediction = local_states[size][index] >= 2
            if low:
                stats["local_replay"][str(size)]["weak_events"] += 1
                stats["local_replay"][str(size)]["weak_correct"] += prediction == actual_taken
            local_states[size][index] = _saturating_update(local_states[size][index], actual_taken)
        per_pc_state = per_pc_states.get(pc, 1)
        per_pc_prediction = per_pc_state >= 2
        per_pc_states[pc] = _saturating_update(per_pc_state, actual_taken)
        if not low:
            continue
        stats["weak_conditional"] += 1
        stats["weak_conditional_pht_correct_inferred"] += pht_prediction == actual_taken
        static_btfnt_prediction = bool((_hex_integer(record.get("instruction"), "instruction") >> 25) & 1)
        stats["weak_conditional_static_btfnt_correct"] += static_btfnt_prediction == actual_taken
        stats["weak_conditional_by_pht_state"][str(pht_state)] += 1
        stats["weak_conditional_by_pc"][pc_string] += 1
        if pht_prediction != actual_taken:
            stats["weak_conditional_mispredicted_pcs"][pc_string] += 1
        stats["weak_conditional_by_pht_index"][str(pht_index)] += 1

    if stats["branch_events"] != expected_retired:
        raise ValueError(
            f"ROI branch mismatch for {trace_path}: trace {stats['branch_events']}, "
            f"m01 {expected_retired}"
        )
    stats = _finalize_stats(stats)
    stats["trace"] = str(trace_path)
    stats["workload"] = trace_path.parts[-4].removeprefix("perf20__")
    stats["roi"] = {
        "first_marker_cycle": first_marker,
        "last_marker_cycle": last_marker,
        "marker_count": marker_count,
        "cycles": roi_cycles,
        "branch_retired": expected_retired,
        "cycle_match": True,
        "branch_match": True,
        "m01_roi_mode": m01.get("roi", {}).get("mode"),
    }
    stats["trace_header"] = header
    return stats


def _add_counter(destination: Counter, source: dict[str, int]) -> None:
    destination.update(source)


def _aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    aggregate = _new_stats()
    aggregate["roi"] = {"workloads": len(rows), "all_match": True}
    for row in rows:
        for key in (
            "branch_events",
            "pht_valid",
            "pht_direction_correct_inferred",
            "conditional_valid",
            "conditional_direction_correct_inferred",
            "weak_conditional",
            "weak_conditional_pht_correct_inferred",
            "weak_conditional_static_btfnt_correct",
        ):
            aggregate[key] += row[key]
        _add_counter(aggregate["by_type"], row["by_type"])
        for key in (
            "weak_conditional_by_pht_state",
            "weak_conditional_by_pc",
            "weak_conditional_mispredicted_pcs",
            "weak_conditional_by_pht_index",
        ):
            _add_counter(aggregate[key], row[key])
        for size, result in aggregate["local_replay"].items():
            result["weak_correct"] += row["local_replay"][size]["weak_correct"]
            result["weak_events"] += row["local_replay"][size]["weak_events"]
    return _finalize_stats(aggregate)


def summarize(root: Path) -> dict[str, Any]:
    traces = sorted(root.glob("**/branch-trace-v2.jsonl"))
    if not traces:
        raise ValueError(f"no branch-trace-v2.jsonl files below {root}")
    rows = [_process_trace(path) for path in traces]
    result = {
        "format": "miku-branch-trace-matrix-v1",
        "source_root": str(root),
        "trace_count": len(rows),
        "roi_contract": {
            "trace_format": "miku-branch-trace-v2",
            "interval": "first-marker-to-last-marker-exclusive",
            "m01_cycles_and_branch_retired_must_match": True,
        },
        "workloads": rows,
        "aggregate": _aggregate(rows),
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="matrix ideal directory or its run root")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        document = summarize(args.root)
        rendered = json.dumps(document, indent=2, sort_keys=True)
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered + "\n", encoding="utf-8")
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
