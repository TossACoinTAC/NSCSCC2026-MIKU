#!/usr/bin/env python3
"""Map changed public source paths to the minimum required contract tests."""

from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


class ImpactError(ValueError):
    pass


def manifest_impact_map(manifest: Path) -> Path:
    match = re.search(r"(?m)^impact_map:\s*([^#\s]+)\s*$", manifest.read_text(encoding="utf-8"))
    if match is None:
        raise ImpactError(f"测试 manifest 缺少 impact_map: {manifest}")
    return (manifest.parents[1] / match.group(1)).resolve()


def changed_paths(root: Path, base: str) -> list[str]:
    commands = (
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        ["git", "diff", "--name-only"],
        ["git", "diff", "--name-only", "--cached"],
        ["git", "ls-files", "--others", "--exclude-standard"],
    )
    result: set[str] = set()
    for command in commands:
        try:
            output = subprocess.run(command, cwd=root, check=True, text=True, stdout=subprocess.PIPE).stdout
        except (OSError, subprocess.CalledProcessError) as error:
            raise ImpactError(f"无法计算变更路径: {' '.join(command)}") from error
        result.update(line for line in output.splitlines() if line)
    return sorted(result)


def calculate_impact(paths: list[str], mapping: dict[str, Any]) -> dict[str, Any]:
    suites: set[str] = set()
    contracts: set[str] = set()
    matches: list[dict[str, Any]] = []
    rules = mapping.get("rules")
    if mapping.get("schema_version") != 1 or not isinstance(rules, list):
        raise ImpactError("impact map schema 错误")
    for rule in rules:
        patterns = rule.get("paths", [])
        matched = sorted(path for path in paths if any(fnmatch.fnmatch(path, pattern) for pattern in patterns))
        if not matched:
            continue
        suites.update(rule.get("scala_suites", []))
        contracts.update(rule.get("python_contracts", []))
        matches.append({"rule": rule.get("name"), "paths": matched})
    explicitly_mapped = {
        path
        for match in matches
        for path in match["paths"]
    }
    scala_test_paths = sorted(
        path
        for path in paths
        if path.startswith("cpu/src/test/scala/")
        and path.endswith("Spec.scala")
        and path not in explicitly_mapped
    )
    for path in scala_test_paths:
        suite_path = path.removeprefix("cpu/src/test/scala/").removesuffix(".scala")
        suites.add(suite_path.replace("/", "."))
    if scala_test_paths:
        matches.append({"rule": "scala-test-source", "paths": scala_test_paths})
    unmatched = sorted(path for path in paths if not any(path in match["paths"] for match in matches))
    return {
        "schema_version": 1,
        "changed_paths": paths,
        "matches": matches,
        "unmatched_paths": unmatched,
        "scala_suites": sorted(suites),
        "python_contracts": sorted(contracts),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--path", action="append", default=[])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    map_path = manifest_impact_map(args.manifest.resolve())
    mapping = json.loads(map_path.read_text(encoding="utf-8"))
    paths = sorted(set(args.path)) if args.path else changed_paths(root, args.base)
    result = calculate_impact(paths, mapping)
    result.update({"base": args.base, "impact_map": str(map_path.relative_to(root))})
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print("Scala suites:")
    for suite in result["scala_suites"]:
        print(f"  {suite}")
    print("Python contracts:")
    for contract in result["python_contracts"]:
        print(f"  {contract}")
    if result["unmatched_paths"]:
        print("Unmatched paths:")
        for path in result["unmatched_paths"]:
            print(f"  {path}")
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ImpactError, OSError, json.JSONDecodeError) as error:
        print(f"test impact failed: {error}", file=sys.stderr)
        raise SystemExit(1)
