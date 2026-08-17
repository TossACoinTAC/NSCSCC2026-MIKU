#!/usr/bin/env python3
"""Bind one Scala test run to its tracked inputs and XML reports."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evidence_identity import (
    EvidenceIdentityError,
    SCALA_INPUT_SCOPE,
    TREE_HASH_ALGORITHM,
    scala_report_snapshot,
    tracked_content_tree_sha256,
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceIdentityError(message)


def write_json(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(
        json.dumps(document, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def input_identity(root: Path) -> dict[str, object]:
    tree_sha256, file_count = tracked_content_tree_sha256(root, SCALA_INPUT_SCOPE)
    return {
        "hash_algorithm": TREE_HASH_ALGORITHM,
        "scope": list(SCALA_INPUT_SCOPE),
        "file_count": file_count,
        "tree_sha256": tree_sha256,
    }


def prepare(root: Path, output: Path) -> None:
    write_json(output, {
        "schema_version": 1,
        "evidence_type": "scala_test_run_input",
        "input": input_identity(root),
    })


def finalize(root: Path, prepared_path: Path, report_directory: Path, output: Path) -> None:
    require(prepared_path.is_file(), "Scala test input identity is missing")
    prepared = json.loads(prepared_path.read_text(encoding="utf-8"))
    require(isinstance(prepared, dict), "Scala test input identity must be an object")
    require(prepared.get("schema_version") == 1 and
            prepared.get("evidence_type") == "scala_test_run_input",
            "Scala test input identity has an unsupported schema")
    current_input = input_identity(root)
    require(prepared.get("input") == current_input,
            "Scala test inputs changed while the test suite was running")

    reports = scala_report_snapshot(root, report_directory)
    require(reports["error_count"] == 0 and reports["failure_count"] == 0,
            "Scala reports contain failures")
    write_json(output, {
        "schema_version": 1,
        "evidence_type": "scala_test_run",
        "command": "make cpu-test-all",
        "input": current_input,
        "reports": reports,
    })


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("prepare", "finalize"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    prepared = args.prepared.resolve()
    require(prepared.is_relative_to(root), "Scala prepared identity must stay in repository")
    if args.phase == "prepare":
        prepare(root, prepared)
        return 0

    require(args.reports is not None and args.out is not None,
            "finalize requires --reports and --out")
    reports = args.reports.resolve()
    output = args.out.resolve()
    require(reports.is_relative_to(root), "Scala report directory must stay in repository")
    require(output.is_relative_to(root), "Scala run manifest must stay in repository")
    finalize(root, prepared, reports, output)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EvidenceIdentityError, OSError, json.JSONDecodeError) as error:
        print(f"Scala run evidence failed: {error}", file=sys.stderr)
        raise SystemExit(1)
