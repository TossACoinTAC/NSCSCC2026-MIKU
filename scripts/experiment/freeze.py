#!/usr/bin/env python3
"""Freeze source, generated RTL, platform, tool and explicit evidence identities."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from content_hash import cpu_source_hash

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (
    ExperimentError,
    artifact_record,
    load_json,
    matrix_simulation_identity,
    sha256_file,
    validate_experiment_manifest,
)


def git_output(root: Path, *arguments: str, binary: bool = False) -> str | bytes:
    try:
        result = subprocess.run(
            ["git", *arguments], cwd=root, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise ExperimentError(f"git {' '.join(arguments)} 失败: {root}") from error
    return result if binary else result.decode().strip()


def hash_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--chiplab-dir", type=Path, required=True)
    parser.add_argument("--chiplab-commit", required=True)
    parser.add_argument("--docker-image", required=True)
    parser.add_argument("--generation-manifest", type=Path, required=True)
    parser.add_argument("--evidence", action="append", default=[])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    root = args.root.resolve()
    cpu = root / "cpu"
    chiplab = args.chiplab_dir.resolve()
    generation_path = args.generation_manifest.resolve()
    generation = load_json(generation_path)
    cpu_tree = cpu_source_hash(cpu)
    if generation.get("source_tree_sha256") != cpu_tree:
        raise ExperimentError("当前 CPU 源码树与 generation manifest 不一致；请先运行 make cpu-generate")
    published = root / "build/rtl/mycpu_top.v"
    raw = root / "build/rtl/raw/core_top.v"
    if generation.get("published_rtl_sha256") != sha256_file(published):
        raise ExperimentError("发布 RTL 与 generation manifest 不一致")
    if generation.get("raw_rtl_sha256") != sha256_file(raw):
        raise ExperimentError("原始 RTL 与 generation manifest 不一致")
    actual_chiplab = str(git_output(chiplab, "rev-parse", "HEAD"))
    if actual_chiplab != args.chiplab_commit:
        raise ExperimentError(f"Chiplab HEAD 不匹配: {actual_chiplab} != {args.chiplab_commit}")

    status = git_output(root, "status", "--porcelain=v1", "-z", "--untracked-files=all", binary=True)
    diff = git_output(root, "diff", "--binary", "HEAD", binary=True)
    chiplab_status = git_output(chiplab, "status", "--porcelain=v1", "-z", "--untracked-files=all", binary=True)
    chiplab_diff = git_output(chiplab, "diff", "--binary", "HEAD", binary=True)
    evidence = [artifact_record(root, Path(value)) for value in args.evidence]
    if len({item["path"] for item in evidence}) != len(evidence):
        raise ExperimentError("--evidence 不得重复")
    simulations = [
        matrix_simulation_identity(root, item["path"])
        for item in evidence
        if Path(item["path"]).name.startswith("matrix_") and Path(item["path"]).suffix == ".csv"
    ]
    try:
        docker_id = subprocess.run(
            ["docker", "image", "inspect", "--format", "{{.Id}}", args.docker_image],
            check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        docker_id = "unavailable"

    document = {
        "schema_version": 1,
        "experiment_id": args.experiment_id,
        "workspace": {
            "branch": git_output(root, "branch", "--show-current"),
            "commit": git_output(root, "rev-parse", "HEAD"),
            "status_sha256": hash_bytes(status),
            "diff_sha256": hash_bytes(diff),
            "dirty": bool(status),
        },
        "cpu": {
            "source_commit": generation.get("source_commit"),
            "source_tree_sha256": cpu_tree,
            "raw_rtl_sha256": generation["raw_rtl_sha256"],
            "published_rtl_sha256": generation["published_rtl_sha256"],
            "generation_manifest_sha256": sha256_file(generation_path),
        },
        "platform": {
            "chiplab_commit": actual_chiplab,
            "dirty": bool(chiplab_status),
            "status_sha256": hash_bytes(chiplab_status),
            "diff_sha256": hash_bytes(chiplab_diff),
        },
        "toolchain": {
            "docker_image": args.docker_image,
            "docker_image_id": docker_id,
            **generation["toolchain"],
        },
        "simulations": simulations,
        "evidence": evidence,
    }
    validate_experiment_manifest(document, root)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print(args.out)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExperimentError as error:
        print(f"experiment freeze failed: {error}", file=sys.stderr)
        raise SystemExit(1)
