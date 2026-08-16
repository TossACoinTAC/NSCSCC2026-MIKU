#!/usr/bin/env python3
"""Repeatable, fast Yosys structural analysis for frozen CPU RTL."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from typing import Any


SCHEMA_VERSION = 1
TOP = "core_top"
PATH_MODULES = (
    "OooFrontend",
    "BankedFetchPredictor",
    "DecodeRenameBuffer",
    "DispatchRouter",
    "IssueQueue",
    "IssueQueue_1",
    "IssueQueue_2",
    "IssueQueue_3",
    "ReorderBuffer",
    "RenameMap",
    "PhysicalRegisterFreeList",
    "PhysicalRegisterFile",
    "LoadStoreQueue",
    "StoreDataQueue",
    "AddressTranslationUnit",
    "L1InstructionCache",
    "L1DataCache",
    "L2Cache",
)


class YosysAnalysisError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def load_json(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise YosysAnalysisError(f"cannot read JSON {path}: {error}") from error
    if not isinstance(document, dict):
        raise YosysAnalysisError(f"JSON root must be an object: {path}")
    return document


def clean_module_name(name: str) -> str:
    return name[1:] if name.startswith("\\") else name


def normalize_signal(signal: str) -> str:
    signal = re.sub(r"(?:[^\s()]*?/)?(?:mycpu_top|core_top)\.v:\d+\$\d+", "<rtl>", signal)
    signal = re.sub(r"\$(?:procmux|procdff)\$\d+", "$generated", signal)
    return signal.strip()


def parse_ltp_text(text: str) -> dict[str, Any]:
    header = re.search(r"Longest topological path in (\S+) \(length=(\d+)\):", text)
    if header is None:
        raise YosysAnalysisError("Yosys ltp output has no path header")
    rows: list[tuple[str, str]] = []
    for match in re.finditer(r"(?m)^\s*(ff|\d+):\s+(.+?)(?:\s+\(via .*)?$", text[header.end() :]):
        rows.append((match.group(1), match.group(2).strip()))
    if not rows:
        raise YosysAnalysisError("Yosys ltp output has no path nodes")
    start = rows[0][1]
    endpoint = next((signal for index, signal in reversed(rows) if index == "ff"), rows[-1][1])
    normalized = [normalize_signal(signal) for _, signal in rows]
    fingerprint = hashlib.sha256("\n".join(normalized).encode()).hexdigest()
    return {
        "module": clean_module_name(header.group(1)),
        "length": int(header.group(2)),
        "start": start,
        "endpoint": endpoint,
        "normalized_start": normalized[0],
        "normalized_endpoint": normalize_signal(endpoint),
        "path_fingerprint": fingerprint,
        "head": normalized[:5],
        "tail": normalized[-5:],
    }


def parse_ltp(path: Path) -> dict[str, Any]:
    return parse_ltp_text(path.read_text(encoding="utf-8", errors="replace"))


def cell_bucket(cell_type: str) -> str:
    lowered = cell_type.lower()
    if any(token in lowered for token in ("dff", "latch")):
        return "sequential"
    if "mem" in lowered or "ram" in lowered:
        return "memory"
    if "mux" in lowered:
        return "mux"
    if any(token in lowered for token in ("$eq", "$ne", "$lt", "$le", "$gt", "$ge")):
        return "compare"
    if any(token in lowered for token in ("$add", "$sub", "$mul", "$div", "$mod", "$alu", "$macc")):
        return "arithmetic"
    return "other"


def module_group(module: str) -> str:
    lowered = module.lower()
    if any(token in lowered for token in ("frontend", "widedecode", "la32rdecoder", "decoderename")):
        return "frontend"
    if "predictor" in lowered:
        return "predictor"
    if any(token in lowered for token in ("issuequeue", "dispatch")):
        return "IQ/dispatch"
    if any(token in lowered for token in ("reorderbuffer", "physicalregister", "renamemap")):
        return "ROB/rename"
    if any(token in lowered for token in ("loadstorequeue", "storedataqueue")):
        return "LSQ"
    if any(token in lowered for token in ("cache", "mshr")):
        return "cache/L2"
    if any(token in lowered for token in ("translation", "tlb", "csr")):
        return "translation/CSR"
    if any(token in lowered for token in ("execution", "alu", "divide", "multiply")):
        return "execution"
    return "other"


def instance_counts(modules: dict[str, dict[str, Any]], top: str) -> dict[str, int]:
    counts = {name: 0 for name in modules}

    def visit(module: str, multiplier: int, stack: tuple[str, ...]) -> None:
        if module in stack:
            raise YosysAnalysisError(f"recursive module hierarchy: {' -> '.join(stack + (module,))}")
        counts[module] = counts.get(module, 0) + multiplier
        cell_types = modules[module].get("num_cells_by_type", {})
        if not isinstance(cell_types, dict):
            raise YosysAnalysisError(f"invalid cell table for module {module}")
        for raw_type, raw_count in cell_types.items():
            child = clean_module_name(str(raw_type))
            if child in modules:
                visit(child, multiplier * int(raw_count), stack + (module,))

    if top not in modules:
        raise YosysAnalysisError(f"top module absent from Yosys stat: {top}")
    visit(top, 1, ())
    return counts


def summarize_stat(document: dict[str, Any]) -> dict[str, Any]:
    raw_modules = document.get("modules")
    design = document.get("design")
    if not isinstance(raw_modules, dict) or not isinstance(design, dict):
        raise YosysAnalysisError("Yosys stat JSON lacks modules/design")
    modules = {clean_module_name(str(name)): value for name, value in raw_modules.items()}
    if any(not isinstance(value, dict) for value in modules.values()):
        raise YosysAnalysisError("Yosys stat module record is not an object")
    counts = instance_counts(modules, TOP)
    module_rows: dict[str, Any] = {}
    group_cells: dict[str, int] = {}
    contribution_total = 0
    for name, module in modules.items():
        instances = counts.get(name, 0)
        if instances == 0:
            continue
        raw_cell_types = module.get("num_cells_by_type", {})
        if not isinstance(raw_cell_types, dict):
            raise YosysAnalysisError(f"invalid cell table for module {name}")
        primitive_types = {
            str(cell_type): int(count)
            for cell_type, count in raw_cell_types.items()
            if clean_module_name(str(cell_type)) not in modules
        }
        primitive_local = sum(primitive_types.values())
        contribution = primitive_local * instances
        buckets: dict[str, int] = {}
        for cell_type, count in primitive_types.items():
            bucket = cell_bucket(cell_type)
            buckets[bucket] = buckets.get(bucket, 0) + count * instances
        group = module_group(name)
        group_cells[group] = group_cells.get(group, 0) + contribution
        contribution_total += contribution
        module_rows[name] = {
            "instances": instances,
            "primitive_cells_local": primitive_local,
            "contribution_cells": contribution,
            "memory_bits": int(module.get("num_memory_bits", 0)) * instances,
            "cell_buckets": dict(sorted(buckets.items())),
            "group": group,
        }
    total_cells = int(design.get("num_cells", -1))
    if contribution_total != total_cells:
        raise YosysAnalysisError(
            f"hierarchy contribution mismatch: modules={contribution_total}, design={total_cells}"
        )
    return {
        "total_cells": total_cells,
        "total_memory_bits": int(design.get("num_memory_bits", 0)),
        "cell_types": design.get("num_cells_by_type", {}),
        "group_cells": dict(sorted(group_cells.items())),
        "modules": dict(sorted(module_rows.items())),
    }


def validate_report(document: dict[str, Any]) -> None:
    if document.get("schema_version") != SCHEMA_VERSION or document.get("kind") != "yosys-structural-analysis":
        raise YosysAnalysisError("unsupported Yosys analysis schema")
    for key in ("input", "tool", "analysis", "artifacts"):
        if not isinstance(document.get(key), dict):
            raise YosysAnalysisError(f"Yosys analysis lacks object: {key}")
    rtl_hash = str(document["input"].get("rtl_sha256", ""))
    if re.fullmatch(r"[0-9a-f]{64}", rtl_hash) is None:
        raise YosysAnalysisError("invalid RTL hash in Yosys analysis")
    if not isinstance(document["analysis"].get("hierarchy"), dict):
        raise YosysAnalysisError("Yosys analysis lacks hierarchy summary")


def percent_delta(baseline: int, candidate: int) -> float | None:
    return None if baseline == 0 else (candidate - baseline) * 100.0 / baseline


def compare_reports(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    validate_report(baseline)
    validate_report(candidate)
    for label, value in (
        ("tool", baseline["tool"].get("identity_sha256")),
        ("analysis configuration", baseline["analysis"].get("config_sha256")),
    ):
        other = candidate["tool" if label == "tool" else "analysis"].get(
            "identity_sha256" if label == "tool" else "config_sha256"
        )
        if value != other:
            raise YosysAnalysisError(f"{label} mismatch between reports")
    base_hierarchy = baseline["analysis"]["hierarchy"]
    cand_hierarchy = candidate["analysis"]["hierarchy"]
    base_modules = base_hierarchy.get("modules", {})
    cand_modules = cand_hierarchy.get("modules", {})
    module_rows = []
    for name in sorted(set(base_modules) | set(cand_modules)):
        before = int(base_modules.get(name, {}).get("contribution_cells", 0))
        after = int(cand_modules.get(name, {}).get("contribution_cells", 0))
        module_rows.append({
            "module": name,
            "baseline_cells": before,
            "candidate_cells": after,
            "delta_cells": after - before,
            "delta_percent": percent_delta(before, after),
        })
    module_rows.sort(key=lambda row: (-abs(row["delta_cells"]), row["module"]))
    base_paths = baseline["analysis"].get("paths", {})
    cand_paths = candidate["analysis"].get("paths", {})
    path_rows = []
    for name in sorted(set(base_paths) | set(cand_paths)):
        before = base_paths.get(name, {}).get("length")
        after = cand_paths.get(name, {}).get("length")
        path_rows.append({
            "module": name,
            "baseline_length": before,
            "candidate_length": after,
            "delta_length": None if before is None or after is None else int(after) - int(before),
        })
    before_total = int(base_hierarchy["total_cells"])
    after_total = int(cand_hierarchy["total_cells"])
    return {
        "schema_version": 1,
        "kind": "yosys-structural-comparison",
        "baseline": baseline["input"],
        "candidate": candidate["input"],
        "tool": baseline["tool"],
        "config_sha256": baseline["analysis"]["config_sha256"],
        "summary": {
            "baseline_cells": before_total,
            "candidate_cells": after_total,
            "delta_cells": after_total - before_total,
            "delta_percent": percent_delta(before_total, after_total),
            "baseline_memory_bits": int(base_hierarchy["total_memory_bits"]),
            "candidate_memory_bits": int(cand_hierarchy["total_memory_bits"]),
        },
        "module_deltas": module_rows,
        "path_deltas": path_rows,
    }


def analysis_config() -> dict[str, Any]:
    return {
        "top": TOP,
        "passes": ["read_verilog -sv", "hierarchy", "proc", "opt_clean", "check", "flatten", "ltp -noff"],
        "path_modules": list(PATH_MODULES),
        "mapping": "generic-word-level",
    }


def run_analyze(args: argparse.Namespace) -> int:
    rtl = args.rtl.resolve()
    if not rtl.is_file():
        raise YosysAnalysisError(f"RTL does not exist: {rtl}")
    out = args.out.resolve()
    try:
        out.mkdir(parents=True, exist_ok=False)
    except FileExistsError as error:
        raise YosysAnalysisError(f"analysis output already exists: {out}") from error
    input_dir = out / "input"
    input_dir.mkdir()
    snapshot = input_dir / "mycpu_top.v"
    shutil.copyfile(rtl, snapshot)
    rtl_sha256 = sha256_file(rtl)
    if sha256_file(snapshot) != rtl_sha256:
        raise YosysAnalysisError("RTL changed while creating the analysis snapshot")

    yosys_path = Path(shutil.which(args.yosys) or "")
    if not yosys_path.is_file():
        raise YosysAnalysisError(f"Yosys executable not found: {args.yosys}")
    version = subprocess.run(
        [str(yosys_path), "-V"], check=True, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, timeout=30,
    ).stdout.strip()
    config = analysis_config()
    config_sha256 = hashlib.sha256(
        json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()

    hierarchy_stat = out / "hierarchy-stat.json"
    top_ltp = out / "ltp-core_top.txt"
    module_ltp = {module: out / f"ltp-{module}.txt" for module in PATH_MODULES}
    script_lines = [
        'read_verilog -sv "input/mycpu_top.v"',
        f"hierarchy -check -top {TOP}",
        "proc",
        "opt_clean",
        "check -assert",
        f"tee -q -o {hierarchy_stat.name} stat -json -top {TOP}",
    ]
    script_lines.extend(
        f"tee -q -o {path.name} ltp -noff {module}"
        for module, path in module_ltp.items()
    )
    script_lines.extend((
        "flatten",
        "opt_clean",
        "check -assert",
        f"tee -q -o {top_ltp.name} ltp -noff",
    ))
    script = out / "analysis.ys"
    script.write_text("\n".join(script_lines) + "\n", encoding="utf-8")
    log = out / "yosys.log"
    started = time.monotonic()
    try:
        result = subprocess.run(
            [str(yosys_path), "-Q", "-q", "-l", log.name, "-s", script.name],
            cwd=out,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=args.timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise YosysAnalysisError(f"Yosys structural analysis timed out after {args.timeout}s") from error
    elapsed = time.monotonic() - started
    if result.returncode != 0:
        tail = result.stdout[-2000:] if result.stdout else ""
        raise YosysAnalysisError(f"Yosys structural analysis failed ({result.returncode}): {tail}")

    hierarchy = summarize_stat(load_json(hierarchy_stat))
    paths = {TOP: parse_ltp(top_ltp)}
    paths.update({module: parse_ltp(path) for module, path in module_ltp.items()})
    tool_identity = {
        "path": str(yosys_path.resolve()),
        "version": version,
        "binary_sha256": sha256_file(yosys_path),
    }
    tool_identity["identity_sha256"] = hashlib.sha256(
        json.dumps(tool_identity, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    artifacts = {}
    for name, path in {
        "snapshot": snapshot,
        "script": script,
        "log": log,
        "hierarchy_stat": hierarchy_stat,
        "top_ltp": top_ltp,
        **{f"ltp_{module}": path for module, path in module_ltp.items()},
    }.items():
        artifacts[name] = {
            "path": str(path.relative_to(out)),
            "sha256": sha256_file(path),
            "bytes": path.stat().st_size,
        }
    summary = {
        "schema_version": SCHEMA_VERSION,
        "kind": "yosys-structural-analysis",
        "input": {
            "label": args.label,
            "source_path": str(rtl),
            "rtl_sha256": rtl_sha256,
            "rtl_bytes": rtl.stat().st_size,
        },
        "tool": tool_identity,
        "analysis": {
            "config": config,
            "config_sha256": config_sha256,
            "elapsed_seconds": round(elapsed, 3),
            "hierarchy": hierarchy,
            "paths": paths,
            "interpretation": (
                "Generic word-level cells and LTP node counts are early structural proxies; "
                "they are not LUT/FF estimates, placement-aware delay, WNS, or a Vivado gate."
            ),
        },
        "artifacts": artifacts,
    }
    validate_report(summary)
    write_json(out / "summary.json", summary)
    print(
        f"Yosys {args.label}: cells={hierarchy['total_cells']}, "
        f"top_ltp={paths[TOP]['length']}, elapsed={elapsed:.2f}s"
    )
    print(out / "summary.json")
    return 0


def run_compare(args: argparse.Namespace) -> int:
    comparison = compare_reports(load_json(args.baseline), load_json(args.candidate))
    write_json(args.out, comparison)
    summary = comparison["summary"]
    print(
        f"Yosys cells: {summary['baseline_cells']} -> {summary['candidate_cells']} "
        f"({summary['delta_percent']:+.3f}%)"
    )
    for row in comparison["module_deltas"][:12]:
        if row["delta_cells"]:
            print(
                f"{row['module']:28s} {row['baseline_cells']:7d} -> "
                f"{row['candidate_cells']:7d} ({row['delta_cells']:+7d})"
            )
    print(args.out)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    analyze = subparsers.add_parser("analyze")
    analyze.add_argument("--rtl", type=Path, required=True)
    analyze.add_argument("--out", type=Path, required=True)
    analyze.add_argument("--label", required=True)
    analyze.add_argument("--yosys", default="/usr/bin/yosys")
    analyze.add_argument("--timeout", type=int, default=180)
    analyze.set_defaults(run=run_analyze)
    compare = subparsers.add_parser("compare")
    compare.add_argument("--baseline", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    compare.add_argument("--out", type=Path, required=True)
    compare.set_defaults(run=run_compare)
    args = parser.parse_args()
    return int(args.run(args))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.SubprocessError, YosysAnalysisError) as error:
        print(f"Yosys analysis failed: {error}", file=sys.stderr)
        raise SystemExit(1)
