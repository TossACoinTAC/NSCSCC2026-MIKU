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


SCHEMA_VERSION = 2
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


def oversized_ltp(paths: list[Path], max_bytes: int) -> list[tuple[Path, int]]:
    oversized = []
    for path in paths:
        try:
            size = path.stat().st_size
        except FileNotFoundError:
            continue
        if size > max_bytes:
            oversized.append((path, size))
    return oversized


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


def cell_word_width(cell_type: str) -> int:
    if not cell_type.startswith("$"):
        return 1
    match = re.search(r"_(\d+)$", cell_type)
    return int(match.group(1)) if match is not None else 1


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


def summarize_stat(
    document: dict[str, Any], width_document: dict[str, Any] | None = None
) -> dict[str, Any]:
    raw_modules = document.get("modules")
    design = document.get("design")
    if not isinstance(raw_modules, dict) or not isinstance(design, dict):
        raise YosysAnalysisError("Yosys stat JSON lacks modules/design")
    modules = {clean_module_name(str(name)): value for name, value in raw_modules.items()}
    if any(not isinstance(value, dict) for value in modules.values()):
        raise YosysAnalysisError("Yosys stat module record is not an object")
    counts = instance_counts(modules, TOP)
    width_modules: dict[str, dict[str, Any]] = {}
    width_design: dict[str, Any] = {}
    if width_document is not None:
        raw_width_modules = width_document.get("modules")
        raw_width_design = width_document.get("design")
        if not isinstance(raw_width_modules, dict) or not isinstance(raw_width_design, dict):
            raise YosysAnalysisError("Yosys width stat JSON lacks modules/design")
        width_modules = {
            clean_module_name(str(name)): value for name, value in raw_width_modules.items()
        }
        if set(width_modules) != set(modules):
            raise YosysAnalysisError("Yosys raw and width stat module sets differ")
        if any(not isinstance(value, dict) for value in width_modules.values()):
            raise YosysAnalysisError("Yosys width stat module record is not an object")
        width_design = raw_width_design
    module_rows: dict[str, Any] = {}
    group_cells: dict[str, int] = {}
    group_word_bits: dict[str, int] = {}
    contribution_total = 0
    contribution_word_total = 0
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
        primitive_word_local = 0
        word_buckets: dict[str, int] = {}
        if width_document is not None:
            raw_width_types = width_modules[name].get("num_cells_by_type", {})
            if not isinstance(raw_width_types, dict):
                raise YosysAnalysisError(f"invalid width cell table for module {name}")
            width_types = {
                str(cell_type): int(count)
                for cell_type, count in raw_width_types.items()
                if clean_module_name(str(cell_type)) not in modules
            }
            for cell_type, count in width_types.items():
                weighted = count * cell_word_width(cell_type)
                primitive_word_local += weighted
                bucket = cell_bucket(cell_type)
                word_buckets[bucket] = word_buckets.get(bucket, 0) + weighted * instances
        contribution_word_bits = primitive_word_local * instances
        contribution_word_total += contribution_word_bits
        group_word_bits[group] = group_word_bits.get(group, 0) + contribution_word_bits
        module_rows[name] = {
            "instances": instances,
            "primitive_cells_local": primitive_local,
            "contribution_cells": contribution,
            "memory_bits": int(module.get("num_memory_bits", 0)) * instances,
            "cell_buckets": dict(sorted(buckets.items())),
            "primitive_word_bits_local": primitive_word_local,
            "contribution_word_bits": contribution_word_bits,
            "word_bit_buckets": dict(sorted(word_buckets.items())),
            "group": group,
        }
    total_cells = int(design.get("num_cells", -1))
    if contribution_total != total_cells:
        raise YosysAnalysisError(
            f"hierarchy contribution mismatch: modules={contribution_total}, design={total_cells}"
        )
    total_word_bits = 0
    width_cell_types: dict[str, int] = {}
    if width_document is not None:
        raw_width_types = width_design.get("num_cells_by_type", {})
        if not isinstance(raw_width_types, dict):
            raise YosysAnalysisError("invalid design width cell table")
        width_cell_types = {
            str(cell_type): int(count) for cell_type, count in raw_width_types.items()
        }
        total_word_bits = sum(
            count * cell_word_width(cell_type)
            for cell_type, count in width_cell_types.items()
        )
        if contribution_word_total != total_word_bits:
            raise YosysAnalysisError(
                "hierarchy word-bit contribution mismatch: "
                f"modules={contribution_word_total}, design={total_word_bits}"
            )
    return {
        "total_cells": total_cells,
        "total_memory_bits": int(design.get("num_memory_bits", 0)),
        "cell_types": design.get("num_cells_by_type", {}),
        "total_word_bits": total_word_bits,
        "width_cell_types": width_cell_types,
        "group_cells": dict(sorted(group_cells.items())),
        "group_word_bits": dict(sorted(group_word_bits.items())),
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
    if not isinstance(document["analysis"].get("post_flatten"), dict):
        raise YosysAnalysisError("Yosys analysis lacks post-flatten summary")


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
        before_word_bits = int(
            base_modules.get(name, {}).get("contribution_word_bits", 0)
        )
        after_word_bits = int(
            cand_modules.get(name, {}).get("contribution_word_bits", 0)
        )
        module_rows.append({
            "module": name,
            "baseline_cells": before,
            "candidate_cells": after,
            "delta_cells": after - before,
            "delta_percent": percent_delta(before, after),
            "baseline_word_bits": before_word_bits,
            "candidate_word_bits": after_word_bits,
            "delta_word_bits": after_word_bits - before_word_bits,
            "delta_word_bits_percent": percent_delta(before_word_bits, after_word_bits),
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
    before_word_bits = int(base_hierarchy.get("total_word_bits", 0))
    after_word_bits = int(cand_hierarchy.get("total_word_bits", 0))
    base_flat = baseline["analysis"].get("post_flatten")
    cand_flat = candidate["analysis"].get("post_flatten")
    if not isinstance(base_flat, dict) or not isinstance(cand_flat, dict):
        raise YosysAnalysisError("Yosys analysis lacks post-flatten summary")
    before_flat_cells = int(base_flat["total_cells"])
    after_flat_cells = int(cand_flat["total_cells"])
    before_flat_word_bits = int(base_flat.get("total_word_bits", 0))
    after_flat_word_bits = int(cand_flat.get("total_word_bits", 0))
    return {
        "schema_version": SCHEMA_VERSION,
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
            "baseline_word_bits": before_word_bits,
            "candidate_word_bits": after_word_bits,
            "delta_word_bits": after_word_bits - before_word_bits,
            "delta_word_bits_percent": percent_delta(before_word_bits, after_word_bits),
            "baseline_memory_bits": int(base_hierarchy["total_memory_bits"]),
            "candidate_memory_bits": int(cand_hierarchy["total_memory_bits"]),
            "baseline_post_flatten_cells": before_flat_cells,
            "candidate_post_flatten_cells": after_flat_cells,
            "delta_post_flatten_cells": after_flat_cells - before_flat_cells,
            "delta_post_flatten_percent": percent_delta(
                before_flat_cells, after_flat_cells
            ),
            "baseline_post_flatten_word_bits": before_flat_word_bits,
            "candidate_post_flatten_word_bits": after_flat_word_bits,
            "delta_post_flatten_word_bits": (
                after_flat_word_bits - before_flat_word_bits
            ),
            "delta_post_flatten_word_bits_percent": percent_delta(
                before_flat_word_bits, after_flat_word_bits
            ),
        },
        "module_deltas": module_rows,
        "path_deltas": path_rows,
    }


def analysis_config(max_ltp_bytes: int) -> dict[str, Any]:
    return {
        "top": TOP,
        "passes": [
            "read_verilog -sv", "hierarchy", "proc", "opt_clean", "check",
            "hierarchy stat -json", "hierarchy stat -json -width",
            "scoped flatten", "scoped ltp -noff", "flatten top", "opt_clean",
            "post-flatten stat -json", "post-flatten stat -json -width",
            "top ltp -noff",
        ],
        "path_modules": list(PATH_MODULES),
        "mapping": "generic-word-level",
        "max_ltp_bytes": max_ltp_bytes,
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
    max_ltp_bytes = args.max_ltp_mb * 1024 * 1024
    config = analysis_config(max_ltp_bytes)
    config_sha256 = hashlib.sha256(
        json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()

    hierarchy_stat = out / "hierarchy-stat.json"
    hierarchy_width_stat = out / "hierarchy-width-stat.json"
    post_flatten_stat = out / "post-flatten-stat.json"
    post_flatten_width_stat = out / "post-flatten-width-stat.json"
    top_ltp = out / "ltp-core_top.txt"
    module_ltp = {module: out / f"ltp-{module}.txt" for module in PATH_MODULES}
    script_lines = [
        'read_verilog -sv "input/mycpu_top.v"',
        f"hierarchy -check -top {TOP}",
        "proc",
        "opt_clean",
        "check -assert",
        f"tee -q -o {hierarchy_stat.name} stat -json -top {TOP}",
        f"tee -q -o {hierarchy_width_stat.name} stat -json -width -top {TOP}",
        "design -save hierarchical",
    ]
    for module, path in module_ltp.items():
        script_lines.extend((
            "design -load hierarchical",
            f"flatten {module}",
            "opt_clean",
            f"tee -q -o {path.name} ltp -noff {module}",
        ))
    script_lines.extend((
        "design -load hierarchical",
        "flatten",
        "opt_clean",
        "check -assert",
        f"tee -q -o {post_flatten_stat.name} stat -json -top {TOP}",
        f"tee -q -o {post_flatten_width_stat.name} stat -json -width -top {TOP}",
        f"tee -q -o {top_ltp.name} ltp -noff",
    ))
    script = out / "analysis.ys"
    script.write_text("\n".join(script_lines) + "\n", encoding="utf-8")
    log = out / "yosys.log"
    started = time.monotonic()
    ltp_paths = [top_ltp, *module_ltp.values()]
    process = subprocess.Popen(
            [str(yosys_path), "-Q", "-q", "-l", log.name, "-s", script.name],
            cwd=out,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    deadline = started + args.timeout
    process_output = ""
    while True:
        try:
            process_output, _ = process.communicate(timeout=1)
            break
        except subprocess.TimeoutExpired:
            oversized = oversized_ltp(ltp_paths, max_ltp_bytes)
            if oversized:
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                details = []
                for path, size in oversized:
                    details.append(f"{path.name}={size} bytes")
                    path.unlink(missing_ok=True)
                raise YosysAnalysisError(
                    "Yosys LTP artifact exceeded the hard size limit "
                    f"({max_ltp_bytes} bytes): {', '.join(details)}"
                )
            if time.monotonic() >= deadline:
                process.terminate()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                raise YosysAnalysisError(
                    f"Yosys structural analysis timed out after {args.timeout}s"
                )
    elapsed = time.monotonic() - started
    if process.returncode != 0:
        tail = process_output[-2000:] if process_output else ""
        raise YosysAnalysisError(
            f"Yosys structural analysis failed ({process.returncode}): {tail}"
        )

    hierarchy = summarize_stat(load_json(hierarchy_stat), load_json(hierarchy_width_stat))
    post_flatten = summarize_stat(
        load_json(post_flatten_stat), load_json(post_flatten_width_stat)
    )
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
        "hierarchy_width_stat": hierarchy_width_stat,
        "post_flatten_stat": post_flatten_stat,
        "post_flatten_width_stat": post_flatten_width_stat,
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
            "post_flatten": post_flatten,
            "paths": paths,
            "interpretation": (
                "Hierarchy statistics preserve ownership but can include logic later pruned; "
                "post-flatten statistics expose the optimized whole-core structure. Scoped LTP "
                "first flattens each target module so child-port feedback does not create false "
                "combinational-loop paths. Generic cells, word-width-weighted operation bits, "
                "and LTP nodes remain early structural proxies, not LUT/FF estimates, "
                "placement-aware delay, WNS, or a Vivado gate."
            ),
        },
        "artifacts": artifacts,
    }
    validate_report(summary)
    write_json(out / "summary.json", summary)
    print(
        f"Yosys {args.label}: cells={hierarchy['total_cells']}, "
        f"word_bits={hierarchy['total_word_bits']}, "
        f"post_flatten_cells={post_flatten['total_cells']}, "
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
    print(
        f"Yosys word bits: {summary['baseline_word_bits']} -> "
        f"{summary['candidate_word_bits']} "
        f"({summary['delta_word_bits_percent']:+.3f}%)"
    )
    print(
        "Yosys post-flatten cells: "
        f"{summary['baseline_post_flatten_cells']} -> "
        f"{summary['candidate_post_flatten_cells']} "
        f"({summary['delta_post_flatten_percent']:+.3f}%)"
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
    analyze.add_argument("--max-ltp-mb", type=int, default=8)
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
