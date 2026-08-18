#!/usr/bin/env python3
"""Summarize the simulator-owned miku-branch-trace-v1 sidecar."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


BRANCH_TYPES = {
    0: "conditional",
    1: "direct",
    2: "indirect",
    3: "return",
    4: "call",
}


def _nonnegative_int(record: dict[str, Any], key: str) -> int:
    value = record.get(key)
    if not isinstance(value, int) or value < 0:
        raise ValueError(f"{key} must be a non-negative integer")
    return value


def summarize(path: Path) -> dict[str, Any]:
    header: dict[str, Any] | None = None
    events: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"invalid JSON at line {line_number}: {error}") from error
            if not isinstance(record, dict):
                raise ValueError(f"line {line_number} is not a JSON object")
            kind = record.get("kind")
            if kind == "header":
                if header is not None:
                    raise ValueError("trace contains multiple headers")
                header = record
            elif kind == "branch":
                events.append(record)
            else:
                raise ValueError(f"unknown trace record kind at line {line_number}: {kind!r}")

    if header is None or header.get("format") != "miku-branch-trace-v1":
        raise ValueError("missing miku-branch-trace-v1 header")
    pht_index_width = _nonnegative_int(header, "pht_index_width")
    metadata_valid_bit = _nonnegative_int(header, "metadata_valid_bit")
    if pht_index_width == 0 or pht_index_width > 16 or metadata_valid_bit >= 32:
        raise ValueError("invalid metadata layout in trace header")

    by_type = {name: 0 for name in BRANCH_TYPES.values()}
    low_by_type = {name: 0 for name in BRANCH_TYPES.values()}
    pht_states = {str(state): 0 for state in range(4)}
    pht_valid = {"valid": 0, "fallback": 0}
    pcs: set[str] = set()
    indices: set[int] = set()
    low_indices: set[int] = set()
    low_events = 0

    for event in events:
        branch_type = BRANCH_TYPES.get(_nonnegative_int(event, "predictor_type"), "unknown")
        by_type.setdefault(branch_type, 0)
        by_type[branch_type] += 1
        low = event.get("low_confidence_pht")
        if not isinstance(low, bool):
            raise ValueError("low_confidence_pht must be boolean")
        if low:
            low_events += 1
            low_by_type.setdefault(branch_type, 0)
            low_by_type[branch_type] += 1
        state = _nonnegative_int(event, "pht_state")
        valid = _nonnegative_int(event, "pht_valid")
        index = _nonnegative_int(event, "pht_index")
        if state > 3 or valid > 1:
            raise ValueError("invalid PHT state or valid bit")
        if index >= 1 << pht_index_width:
            raise ValueError("PHT index exceeds the trace header width")
        expected_low = valid != 0 and state in (1, 2)
        if low != expected_low:
            raise ValueError("low_confidence_pht disagrees with PHT state/valid")
        pht_states[str(state)] += 1
        pht_valid["valid" if valid else "fallback"] += 1
        pc = event.get("pc")
        if not isinstance(pc, str):
            raise ValueError("pc must be a hexadecimal string")
        pcs.add(pc)
        indices.add(index)
        if low:
            low_indices.add(index)

    total = len(events)
    return {
        "format": "miku-branch-trace-summary-v1",
        "source": str(path),
        "trace_header": header,
        "branch_events": total,
        "low_confidence_pht_events": low_events,
        "low_confidence_pht_ratio": low_events / total if total else 0.0,
        "by_type": by_type,
        "low_confidence_pht_by_type": low_by_type,
        "pht_state": pht_states,
        "pht_valid": pht_valid,
        "unique_pcs": len(pcs),
        "unique_pht_indices": len(indices),
        "unique_low_confidence_pht_indices": len(low_indices),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    args = parser.parse_args()
    try:
        document = summarize(args.trace)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(document, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
