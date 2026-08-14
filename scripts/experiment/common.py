#!/usr/bin/env python3
"""Shared contracts for repeatable CPU experiments."""

from __future__ import annotations

import csv
import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any


class ExperimentError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ExperimentError(f"无法读取 JSON: {path}: {error}") from error
    if not isinstance(document, dict):
        raise ExperimentError(f"JSON 根必须是对象: {path}")
    return document


def parse_key_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def resolve_workspace_file(root: Path, value: str | Path) -> Path:
    candidate = Path(value)
    path = candidate.resolve() if candidate.is_absolute() else (root / candidate).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise ExperimentError(f"证据必须位于工作区内: {path}") from error
    if not path.is_file():
        raise ExperimentError(f"证据文件不存在: {path}")
    return path


def artifact_record(root: Path, path: Path) -> dict[str, Any]:
    path = resolve_workspace_file(root, path)
    return {
        "path": path.relative_to(root).as_posix(),
        "sha256": sha256_file(path),
        "bytes": path.stat().st_size,
    }


def validate_experiment_manifest(document: dict[str, Any], root: Path | None = None) -> None:
    required = {"schema_version", "experiment_id", "workspace", "cpu", "platform", "toolchain", "simulations", "evidence"}
    missing = required - set(document)
    if missing:
        raise ExperimentError(f"实验清单缺少字段: {sorted(missing)}")
    if document["schema_version"] != 1:
        raise ExperimentError("实验清单 schema_version 必须为 1")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", str(document["experiment_id"])):
        raise ExperimentError("experiment_id 只能包含字母、数字、点、下划线和连字符")
    for group in ("workspace", "cpu", "platform", "toolchain"):
        if not isinstance(document[group], dict):
            raise ExperimentError(f"实验清单 {group} 必须是对象")
    cpu = document["cpu"]
    for key in ("source_tree_sha256", "raw_rtl_sha256", "published_rtl_sha256", "generation_manifest_sha256"):
        if re.fullmatch(r"[0-9a-f]{64}", str(cpu.get(key, ""))) is None:
            raise ExperimentError(f"实验清单 CPU 哈希非法: {key}")
    chiplab = str(document["platform"].get("chiplab_commit", ""))
    if re.fullmatch(r"[0-9a-f]{40}", chiplab) is None:
        raise ExperimentError("实验清单 Chiplab commit 非法")
    evidence = document["evidence"]
    if not isinstance(evidence, list):
        raise ExperimentError("实验清单 evidence 必须是数组")
    simulations = document["simulations"]
    if not isinstance(simulations, list):
        raise ExperimentError("实验清单 simulations 必须是数组")
    for index, item in enumerate(simulations):
        required_sim = {
            "matrix_path", "matrix_sha256", "cpu_commit", "chiplab_commit", "profile",
            "suite", "memory_mode", "model_sha256", "model_key", "software_key",
        }
        if not isinstance(item, dict) or set(item) != required_sim:
            raise ExperimentError(f"simulations[{index}] schema 错误")
        for key in ("matrix_sha256", "model_sha256", "model_key", "software_key"):
            if re.fullmatch(r"[0-9a-f]{64}", str(item[key])) is None:
                raise ExperimentError(f"simulations[{index}] 哈希非法: {key}")
        if root is not None:
            matrix = resolve_workspace_file(root, item["matrix_path"])
            if sha256_file(matrix) != item["matrix_sha256"]:
                raise ExperimentError(f"simulation matrix 身份不匹配: {item['matrix_path']}")
    seen: set[str] = set()
    for index, item in enumerate(evidence):
        if not isinstance(item, dict) or set(item) != {"path", "sha256", "bytes"}:
            raise ExperimentError(f"evidence[{index}] schema 错误")
        path = str(item["path"])
        if path in seen:
            raise ExperimentError(f"实验清单证据重复: {path}")
        seen.add(path)
        if re.fullmatch(r"[0-9a-f]{64}", str(item["sha256"])) is None:
            raise ExperimentError(f"实验清单证据哈希非法: {path}")
        if not isinstance(item["bytes"], int) or item["bytes"] < 0:
            raise ExperimentError(f"实验清单证据大小非法: {path}")
        if root is not None:
            actual = artifact_record(root, path)
            if actual != item:
                raise ExperimentError(f"实验清单证据身份不匹配: {path}")


def _matching_run(matrix: Path, benchmark: str, seed: int, cycles: int) -> tuple[Path, dict[str, str]]:
    slug = benchmark if "__" in benchmark else f"perf20__{benchmark}"
    candidates = sorted(
        (matrix.parent / slug / f"seed_{seed}").glob("limit_*ns/run-manifest.txt"),
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for manifest in candidates:
        result_path = manifest.parent / "perf20-result.json"
        if not result_path.is_file():
            continue
        result = load_json(result_path)
        if result.get("cpu_cycles") == cycles:
            return manifest, parse_key_values(manifest)
    raise ExperimentError(
        f"矩阵行没有 matching run manifest: benchmark={benchmark} seed={seed} cycles={cycles}"
    )


def matrix_simulation_identity(root: Path, matrix: Path) -> dict[str, str]:
    matrix = resolve_workspace_file(root, matrix)
    manifests = sorted(matrix.parent.glob("*/seed_*/limit_*ns/run-manifest.txt"))
    if not manifests:
        raise ExperimentError(f"矩阵缺少 run manifest: {matrix}")
    keys = (
        "cpu_commit", "chiplab_commit", "profile", "suite", "memory_mode",
        "model_sha256", "model_key", "software_key",
    )
    identities = [{key: parse_key_values(path).get(key, "") for key in keys} for path in manifests]
    if any(not value for identity in identities for value in identity.values()):
        raise ExperimentError(f"矩阵 run manifest 身份字段不完整: {matrix}")
    if any(identity != identities[0] for identity in identities[1:]):
        raise ExperimentError(f"矩阵包含不一致的模型或软件身份: {matrix}")
    return {
        "matrix_path": matrix.relative_to(root).as_posix(),
        "matrix_sha256": sha256_file(matrix),
        **identities[0],
    }


def load_perf_matrix(path: Path) -> dict[str, Any]:
    path = path.resolve()
    if not path.is_file():
        raise ExperimentError(f"perf20 矩阵不存在: {path}")
    rows: dict[tuple[str, str, int], int] = {}
    identities: list[dict[str, str]] = []
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        required = {"benchmark", "memory_mode", "seed", "cpu_cycles", "verdict"}
        if set(reader.fieldnames or ()) != required:
            raise ExperimentError(f"perf20 矩阵列错误: {path}")
        for number, row in enumerate(reader, 2):
            if row["verdict"] != "pass":
                raise ExperimentError(f"perf20 矩阵包含非 pass 行 {number}: {row['verdict']}")
            try:
                seed = int(row["seed"])
                cycles = int(row["cpu_cycles"])
            except ValueError as error:
                raise ExperimentError(f"perf20 矩阵整数列错误: {path}:{number}") from error
            if seed < 0 or cycles <= 0:
                raise ExperimentError(f"perf20 矩阵 seed/cycles 非法: {path}:{number}")
            key = (row["benchmark"], row["memory_mode"], seed)
            if key in rows:
                raise ExperimentError(f"perf20 矩阵 key 重复: {key}")
            rows[key] = cycles
            _, identity = _matching_run(path, row["benchmark"], seed, cycles)
            identities.append(identity)
    if not rows:
        raise ExperimentError(f"perf20 矩阵为空: {path}")
    stable_keys = ("chiplab_commit", "profile", "suite", "memory_mode", "model_key", "software_key")
    reference = {key: identities[0].get(key, "") for key in stable_keys}
    for identity in identities:
        current = {key: identity.get(key, "") for key in stable_keys}
        if current != reference:
            raise ExperimentError(f"perf20 矩阵内部身份不一致: {path}")
    if reference["suite"] == "perf20" and len(rows) != 20:
        raise ExperimentError(f"完整 perf20 必须恰好包含 20 项，实际 {len(rows)} 项")
    return {"path": path, "rows": rows, "identity": reference}


def compare_perf_matrices(baseline_path: Path, candidate_path: Path) -> dict[str, Any]:
    baseline = load_perf_matrix(baseline_path)
    candidate = load_perf_matrix(candidate_path)
    if set(baseline["rows"]) != set(candidate["rows"]):
        raise ExperimentError("baseline 与 candidate 的 benchmark/memory/seed 集合不同")
    comparable_identity = ("chiplab_commit", "profile", "suite", "memory_mode", "software_key")
    identity_mismatch = {
        key: {"baseline": baseline["identity"][key], "candidate": candidate["identity"][key]}
        for key in comparable_identity
        if baseline["identity"][key] != candidate["identity"][key]
    }
    if identity_mismatch:
        raise ExperimentError(f"baseline 与 candidate 身份不一致: {identity_mismatch}")
    rows: list[dict[str, Any]] = []
    speedups: list[float] = []
    for key in sorted(baseline["rows"]):
        base_cycles = baseline["rows"][key]
        candidate_cycles = candidate["rows"][key]
        delta = candidate_cycles - base_cycles
        speedup = base_cycles / candidate_cycles
        speedups.append(speedup)
        rows.append({
            "benchmark": key[0],
            "memory_mode": key[1],
            "seed": key[2],
            "baseline_cycles": base_cycles,
            "candidate_cycles": candidate_cycles,
            "delta_cycles": delta,
            "change_percent": delta * 100.0 / base_cycles,
            "speedup": speedup,
        })
    total_base = sum(baseline["rows"].values())
    total_candidate = sum(candidate["rows"].values())
    geometric_speedup = math.exp(sum(math.log(value) for value in speedups) / len(speedups))
    return {
        "schema_version": 1,
        "baseline": {"path": str(baseline["path"]), "identity": baseline["identity"]},
        "candidate": {"path": str(candidate["path"]), "identity": candidate["identity"]},
        "summary": {
            "benchmarks": len(rows),
            "baseline_total_cycles": total_base,
            "candidate_total_cycles": total_candidate,
            "delta_cycles": total_candidate - total_base,
            "total_change_percent": (total_candidate - total_base) * 100.0 / total_base,
            "geometric_mean_speedup": geometric_speedup,
            "geometric_mean_change_percent": (1.0 / geometric_speedup - 1.0) * 100.0,
            "exactly_equal": all(row["delta_cycles"] == 0 for row in rows),
        },
        "rows": rows,
    }
