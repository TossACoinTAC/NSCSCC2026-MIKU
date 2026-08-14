#!/usr/bin/env python3
"""Validate and archive one complete Chiplab/Vivado implementation."""

from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiment"))
from common import (
    ExperimentError,
    load_json as load_experiment_json,
    validate_experiment_manifest,
)


REQUIRED_IMPL_ARTIFACTS = {
    "soc_top.bit": "soc_top.bit",
    "soc_top.ltx": "soc_top.ltx",
    "soc_top_routed.dcp": "soc_top_routed.dcp",
    "timing_summary.rpt": "timing_summary.rpt",
    "clock_timing_validation.txt": "clock_timing_validation.txt",
    "soc_top_drc_routed.rpt": "soc_top_drc_routed.rpt",
    "soc_top_utilization_placed.rpt": "soc_top_utilization_placed.rpt",
    "cpu_setup_top50.rpt": "cpu_setup_top50.rpt",
    "route_status.rpt": "route_status.rpt",
    "utilization_routed.rpt": "utilization_routed.rpt",
    "implementation.log": "runme.log",
}


class ArchiveError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_key_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def parse_drc(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace")
    error_rules = re.findall(r"^\|\s*[^|]+\|\s*Error\s*\|", text, re.MULTILINE)
    critical_rules = re.findall(
        r"^\|\s*[^|]+\|\s*Critical Warning\s*\|", text, re.MULTILINE
    )
    violation_match = re.search(r"Violations found:\s*(\d+)", text)
    fully_routed = "Design State : Fully Routed" in text
    return {
        "errors": len(error_rules),
        "critical_warnings": len(critical_rules),
        "violations": int(violation_match.group(1)) if violation_match else None,
        "fully_routed": fully_routed,
    }


def parse_utilization(path: Path) -> dict[str, float | int]:
    text = path.read_text(encoding="utf-8", errors="replace")
    rows = {
        "slice_luts": "Slice LUTs",
        "slice_registers": "Slice Registers",
        "slices": "Slice",
        "bram_tiles": "Block RAM Tile",
        "dsp": "DSPs",
    }
    result: dict[str, float | int] = {}
    for key, label in rows.items():
        match = re.search(
            rf"^\|\s*{re.escape(label)}\s*\|\s*([0-9.]+)\s*\|",
            text,
            re.MULTILINE,
        )
        if match is None:
            raise ArchiveError(f"资源报告缺少 {label}: {path}")
        raw = match.group(1)
        result[key] = float(raw) if "." in raw else int(raw)
    return result


def git_value(root: Path, *arguments: str) -> str:
    try:
        return subprocess.run(
            ["git", *arguments],
            cwd=root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unavailable"


def require_file(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise ArchiveError(f"实现产物缺失或为空: {path}")


def normalize_frequency(value: str) -> str:
    try:
        number = float(value)
    except ValueError as error:
        raise ArchiveError(f"非法时钟频率: {value}") from error
    return f"{number:g}"


def implementation_passes(
    clock: dict[str, str],
    drc: dict[str, Any],
    bitstream: Path,
    requested_cpu_mhz: float | None = None,
) -> bool:
    try:
        setup = float(clock["setup_wns_ns"])
        hold = float(clock["hold_wns_ns"])
        actual_cpu = float(clock["actual_cpu_mhz"])
        actual_sys = float(clock["actual_sys_mhz"])
        actual_ddr = float(clock["actual_ddr_mhz"])
    except (KeyError, ValueError) as error:
        raise ArchiveError("clock_timing_validation.txt 缺少合法时钟或 setup/hold WNS") from error
    cpu_target = actual_cpu if requested_cpu_mhz is None else requested_cpu_mhz
    clock_matches = (
        abs(actual_cpu - cpu_target) <= max(0.001, cpu_target * 0.01)
        and abs(actual_sys - 100.0) <= 0.001
        and abs(actual_ddr - 200.0) <= 0.001
    )
    return (
        clock_matches
        and setup >= 0.0
        and hold >= 0.0
        and drc["errors"] == 0
        and drc["critical_warnings"] == 0
        and drc["fully_routed"]
        and bitstream.is_file()
        and bitstream.stat().st_size > 0
    )


def select_archive_class(stage: str, requested: str, timing_pass: bool) -> str:
    if stage == "postroute":
        if requested == "stable":
            raise ArchiveError(
                "post-route 物理优化只用于探索，不能归档为正式 stable 竞赛产物"
            )
        return "candidate"
    if requested == "stable" and not timing_pass:
        raise ArchiveError(
            "完整实现未同时满足 setup/hold、DRC、fully-routed 和 bitstream，拒绝 stable 归档"
        )
    if requested == "auto":
        return "stable" if timing_pass else "candidate"
    return requested


def load_experiment_evidence(
    root: Path,
    manifest_path: Path,
    generation: dict[str, Any],
    chiplab_commit: str,
) -> tuple[dict[str, Any], list[Path]]:
    manifest_path = manifest_path.resolve()
    try:
        manifest_path.relative_to(root)
    except ValueError as error:
        raise ArchiveError(f"实验清单必须位于工作区内: {manifest_path}") from error
    experiment = load_experiment_json(manifest_path)
    validate_experiment_manifest(experiment, root)
    cpu = experiment["cpu"]
    expected = {
        "source_tree_sha256": generation["source_tree_sha256"],
        "raw_rtl_sha256": generation["raw_rtl_sha256"],
        "published_rtl_sha256": generation["published_rtl_sha256"],
        "generation_manifest_sha256": sha256(root / "build/rtl/generation-manifest.json"),
    }
    actual = {key: cpu.get(key) for key in expected}
    if actual != expected:
        raise ArchiveError(f"实验清单与当前生成 RTL 身份不一致: {actual} != {expected}")
    if experiment["platform"].get("chiplab_commit") != chiplab_commit:
        raise ArchiveError("实验清单与当前 Chiplab 身份不一致")
    evidence = [(root / item["path"]).resolve() for item in experiment["evidence"]]
    return experiment, evidence


def artifact_record(path: Path) -> dict[str, int | str]:
    return {"sha256": sha256(path), "bytes": path.stat().st_size}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--chiplab-dir", type=Path, required=True)
    parser.add_argument("--chiplab-commit", required=True)
    parser.add_argument("--kind", choices=("perf", "func"), required=True)
    parser.add_argument("--requested-mhz", required=True)
    parser.add_argument("--experiment-manifest", type=Path, required=True)
    parser.add_argument("--impl-dir", type=Path)
    parser.add_argument("--stage", choices=("full", "postroute"), default="full")
    parser.add_argument(
        "--class", dest="archive_class", choices=("auto", "candidate", "stable"), default="auto"
    )
    args = parser.parse_args()

    root = args.root.resolve()
    build_dir = args.build_dir.resolve()
    chiplab = args.chiplab_dir.resolve()
    impl_dir = (
        args.impl_dir.resolve()
        if args.impl_dir is not None
        else build_dir / "fpga/nscscc-team/run_vivado/project/loongson.runs/impl_1"
    )
    generation_path = root / "build/rtl/generation-manifest.json"
    published_rtl = root / "build/rtl/mycpu_top.v"
    staged_rtl = build_dir / "IP/myCPU/mycpu_top.v"
    for path in (generation_path, published_rtl, staged_rtl):
        require_file(path)

    generation = json.loads(generation_path.read_text(encoding="utf-8"))
    required_generation = {
        "source_tree_sha256", "source_commit", "raw_rtl_sha256",
        "published_rtl_sha256", "toolchain",
    }
    missing_generation = required_generation - set(generation)
    if missing_generation:
        raise ArchiveError(f"RTL 生成清单缺少字段: {sorted(missing_generation)}")
    expected_rtl = generation["published_rtl_sha256"]
    if sha256(published_rtl) != expected_rtl or sha256(staged_rtl) != expected_rtl:
        raise ArchiveError("根 RTL、生成清单与 Vivado staging RTL 身份不一致")

    actual_chiplab = git_value(chiplab, "rev-parse", "HEAD")
    if actual_chiplab != args.chiplab_commit:
        raise ArchiveError(
            f"Chiplab HEAD 与锁定提交不一致: {actual_chiplab} != {args.chiplab_commit}"
        )

    sources: dict[str, Path] = {
        "mycpu_top.v": staged_rtl,
    }
    for archive_name, implementation_name in REQUIRED_IMPL_ARTIFACTS.items():
        source = impl_dir / implementation_name
        require_file(source)
        sources[archive_name] = source
    generated_clock = build_dir / "fpga/nscscc-team/run_vivado/perf_clock_generated.txt"
    if args.kind == "perf" and generated_clock.is_file():
        sources["perf_clock_generated.txt"] = generated_clock

    clock = parse_key_values(sources["clock_timing_validation.txt"])
    drc = parse_drc(sources["soc_top_drc_routed.rpt"])
    utilization = parse_utilization(sources["soc_top_utilization_placed.rpt"])
    timing_pass = implementation_passes(
        clock, drc, sources["soc_top.bit"], float(args.requested_mhz)
    )
    selected_class = select_archive_class(args.stage, args.archive_class, timing_pass)

    archive_root = root / ("Stable_Backup" if selected_class == "stable" else "Post_Impl_Bundles")
    archive_root.mkdir(parents=True, exist_ok=True)
    frequency = normalize_frequency(args.requested_mhz)
    build_time = datetime.fromtimestamp(
        sources["clock_timing_validation.txt"].stat().st_mtime
    ).strftime("%Y%m%d-%H%M%S")
    source_commit = str(generation["source_commit"])
    name = (
        f"cpu_{source_commit[:12]}_chiplab_{args.chiplab_commit[:12]}_"
        f"{args.kind}{'_postroute' if args.stage == 'postroute' else ''}_"
        f"{frequency}mhz_{build_time}"
    )
    destination = archive_root / name

    experiment, evidence = load_experiment_evidence(
        root, args.experiment_manifest, generation, args.chiplab_commit
    )
    manifest: dict[str, Any] = {
        "schema_version": 2,
        "artifact_class": selected_class,
        "timing_status": "pass" if timing_pass else "fail",
        "build_kind": args.kind,
        "implementation_stage": args.stage,
        "competition_flow_eligible": args.stage == "full",
        "competition_eligible": args.stage == "full" and timing_pass,
        "purpose": "physical-exploration" if args.stage == "postroute" else "competition-build",
        "source": {
            "commit": source_commit,
            "tree_sha256": generation["source_tree_sha256"],
            "raw_rtl_sha256": generation["raw_rtl_sha256"],
            "published_rtl_sha256": expected_rtl,
            "generation_manifest_sha256": sha256(generation_path),
            "workspace_commit_at_archive": git_value(root, "rev-parse", "HEAD"),
        },
        "platform": {
            "chiplab_commit": args.chiplab_commit,
            "device": "xc7a200tfbg676-2",
            "vivado": "2023.2",
        },
        "clock": clock,
        "drc": drc,
        "utilization": utilization,
        "toolchain": generation["toolchain"],
        "experiment": {
            "id": experiment["experiment_id"],
            "manifest_sha256": sha256(args.experiment_manifest),
        },
        "evidence": [],
        "artifacts": {},
    }

    expected_bit_hash = sha256(sources["soc_top.bit"])
    refreshing = destination.exists()
    if refreshing:
        existing_path = destination / "manifest.json"
        require_file(existing_path)
        existing = json.loads(existing_path.read_text(encoding="utf-8"))
        same_implementation = (
            existing.get("source", {}).get("published_rtl_sha256") == expected_rtl
            and existing.get("artifacts", {}).get("soc_top.bit", {}).get("sha256")
            == expected_bit_hash
        )
        if not same_implementation:
            raise ArchiveError(f"归档目录名称冲突且内容不同: {destination}")

    temporary = archive_root / f".{name}.tmp-{os.getpid()}"
    if temporary.exists():
        shutil.rmtree(temporary)
    if refreshing:
        shutil.copytree(destination, temporary)
    else:
        temporary.mkdir()
    try:
        for archive_name, source in sources.items():
            target = temporary / archive_name
            shutil.copy2(source, target)
            manifest["artifacts"][archive_name] = artifact_record(target)

        for source in evidence:
            source_path = source.relative_to(root)
            target = temporary / "evidence" / source_path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            manifest["evidence"].append(
                {
                    "source_path": str(source_path),
                    "archive_path": str(target.relative_to(temporary)),
                    **artifact_record(target),
                }
            )

        normalized_generation = {
            **generation,
            "raw_rtl": "build/rtl/raw/core_top.v",
            "published_rtl": "build/rtl/mycpu_top.v",
            "source_manifest_sha256": sha256(generation_path),
        }
        generation_target = temporary / "generation-manifest.json"
        generation_target.write_text(
            json.dumps(normalized_generation, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        manifest["artifacts"][generation_target.name] = artifact_record(generation_target)

        experiment_target = temporary / "experiment-manifest.json"
        shutil.copy2(args.experiment_manifest, experiment_target)
        manifest["artifacts"][experiment_target.name] = artifact_record(experiment_target)

        manifest_path = temporary / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        text_lines = [
            f"artifact_class={selected_class}",
            f"timing_status={'pass' if timing_pass else 'fail'}",
            f"build_kind={args.kind}",
            f"implementation_stage={args.stage}",
            f"competition_flow_eligible={str(args.stage == 'full').lower()}",
            f"competition_eligible={str(args.stage == 'full' and timing_pass).lower()}",
            f"purpose={'physical-exploration' if args.stage == 'postroute' else 'competition-build'}",
            f"experiment_id={experiment['experiment_id']}",
            f"cpu_source_commit={source_commit}",
            f"cpu_source_tree_sha256={generation['source_tree_sha256']}",
            f"chiplab_commit={args.chiplab_commit}",
            f"requested_cpu_mhz={frequency}",
            f"setup_wns_ns={clock['setup_wns_ns']}",
            f"hold_wns_ns={clock['hold_wns_ns']}",
            f"drc_errors={drc['errors']}",
            f"drc_critical_warnings={drc['critical_warnings']}",
            f"fully_routed={str(drc['fully_routed']).lower()}",
        ]
        for filename, record in sorted(manifest["artifacts"].items()):
            text_lines.append(f"{filename.replace('.', '_')}_sha256={record['sha256']}")
        (temporary / "manifest.txt").write_text(
            "\n".join(text_lines) + "\n", encoding="utf-8"
        )
        if refreshing:
            previous = archive_root / f".{name}.previous-{os.getpid()}"
            destination.rename(previous)
            try:
                temporary.rename(destination)
            except Exception:
                previous.rename(destination)
                raise
            shutil.rmtree(previous)
        else:
            temporary.rename(destination)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise

    print(
        f"{selected_class} implementation ({manifest['timing_status']} timing) "
        f"{'refreshed' if refreshing else 'archived'}: {destination}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ArchiveError, ExperimentError, json.JSONDecodeError) as error:
        print(f"implementation archive failed: {error}", file=sys.stderr)
        raise SystemExit(1)
