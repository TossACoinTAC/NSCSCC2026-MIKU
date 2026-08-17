#!/usr/bin/env python3
"""Create a portable summary of the current local CPU verification artifacts."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evidence_identity import (
    EvidenceIdentityError,
    PYTHON_CONTRACT_INPUT_SCOPE,
    SCALA_INPUT_SCOPE,
    SHA256_PATTERN,
    TREE_HASH_ALGORITHM,
    parse_python_contract_log,
    scala_report_snapshot,
    sha256_file,
    tracked_content_tree_sha256,
)


class EvidenceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def relative_path(root: Path, path: Path) -> str:
    resolved = path.resolve()
    require(resolved.is_relative_to(root), f"artifact escapes repository: {path}")
    return resolved.relative_to(root).as_posix()


def load_json(path: Path) -> dict[str, Any]:
    require(path.is_file(), f"missing artifact: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"artifact must contain an object: {path}")
    return value


def summarize_scala_run(root: Path, run_manifest_path: Path) -> dict[str, Any]:
    run_manifest = load_json(run_manifest_path)
    require(run_manifest.get("schema_version") == 1 and
            run_manifest.get("evidence_type") == "scala_test_run",
            "Scala run manifest has an unsupported schema")
    require(run_manifest.get("command") == "make cpu-test-all",
            "Scala run manifest has the wrong command")
    current_tree_sha256, current_file_count = tracked_content_tree_sha256(
        root, SCALA_INPUT_SCOPE
    )
    expected_input = {
        "hash_algorithm": TREE_HASH_ALGORITHM,
        "scope": list(SCALA_INPUT_SCOPE),
        "file_count": current_file_count,
        "tree_sha256": current_tree_sha256,
    }
    require(run_manifest.get("input") == expected_input,
            "Scala run manifest uses stale or different tracked inputs")
    report_directory = root / "cpu/target/test-reports"
    reports = scala_report_snapshot(root, report_directory)
    require(run_manifest.get("reports") == reports,
            "Scala XML reports do not match their run manifest")
    require(reports["error_count"] == 0 and reports["failure_count"] == 0,
            "Scala reports contain failures")
    return {
        "status": "passed",
        "command": "make cpu-test-all",
        "report_directory": relative_path(root, report_directory),
        "hash_algorithm": "path-payload-sha256-v1",
        "raw_reports_tracked": False,
        "run_manifest_path": relative_path(root, run_manifest_path),
        "run_manifest_sha256": sha256_file(run_manifest_path),
        "input_hash_algorithm": TREE_HASH_ALGORITHM,
        "input_scope": list(SCALA_INPUT_SCOPE),
        "input_file_count": current_file_count,
        "input_tree_sha256": current_tree_sha256,
        **reports,
    }


def summarize_python_log(
    root: Path,
    log_path: Path,
    recorded_log_path: Path,
) -> dict[str, Any]:
    require(log_path.is_file(), f"missing Python contract log: {log_path}")
    log = log_path.read_text(encoding="utf-8")
    parsed = parse_python_contract_log(log)
    summary = parsed["summary"]
    require(parsed["result"] == "PASS", "Python contract log is not passing")
    require(summary["failure_count"] == summary["error_count"] ==
            summary["expected_failure_count"] ==
            summary["unexpected_success_count"] == 0,
            "Python contract log contains a non-passing result")
    runner_path = root / "scripts/common/run_python_contracts.py"
    input_tree_sha256, input_file_count = tracked_content_tree_sha256(
        root, PYTHON_CONTRACT_INPUT_SCOPE
    )
    expected_input = {
        "hash_algorithm": TREE_HASH_ALGORITHM,
        "file_count": input_file_count,
        "tree_sha256": input_tree_sha256,
    }
    require(parsed["input"] == expected_input,
            "Python contract log uses stale or different tracked inputs")
    return {
        "status": "passed",
        "command": "make cpu-contract-test",
        "test_count": summary["test_count"],
        "passed_count": summary["passed_count"],
        "failure_count": summary["failure_count"],
        "error_count": summary["error_count"],
        "skipped_count": summary["skipped_count"],
        "log": relative_path(root, recorded_log_path),
        "log_sha256": sha256_file(log_path),
        "runner_sha256": sha256_file(runner_path),
        "contract_input_hash_algorithm": TREE_HASH_ALGORITHM,
        "contract_input_scope": list(PYTHON_CONTRACT_INPUT_SCOPE),
        "contract_input_file_count": input_file_count,
        "contract_input_tree_sha256": input_tree_sha256,
    }


def summarize_generation(root: Path, manifest_path: Path) -> tuple[dict[str, Any], dict[str, str]]:
    manifest = load_json(manifest_path)
    identity = {}
    for field in (
        "source_commit",
        "source_tree_sha256",
        "custom_profile",
        "raw_rtl_sha256",
        "published_rtl_sha256",
    ):
        value = manifest.get(field)
        require(isinstance(value, str) and bool(value),
                f"generation manifest has no {field}")
        identity[field] = value
    for field in ("source_tree_sha256", "raw_rtl_sha256", "published_rtl_sha256"):
        require(SHA256_PATTERN.fullmatch(identity[field]) is not None,
                f"generation manifest {field} is not SHA256")
    toolchain = manifest.get("toolchain")
    require(isinstance(toolchain, dict), "generation manifest has no toolchain")
    raw_rtl = root / "build/rtl/raw/core_top.v"
    published_rtl = root / "build/rtl/mycpu_top.v"
    require(sha256_file(raw_rtl) == identity["raw_rtl_sha256"],
            "raw RTL does not match generation manifest")
    require(sha256_file(published_rtl) == identity["published_rtl_sha256"],
            "published RTL does not match generation manifest")
    return ({
        "status": "passed",
        "command": "make cpu-locked-gates CUSTOM_PROFILE=disabled",
        "manifest_path": relative_path(root, manifest_path),
        "manifest_sha256": sha256_file(manifest_path),
        "toolchain": toolchain,
        "raw_artifacts_tracked": False,
    }, identity)


def summarize_gate(root: Path, path: Path, gate: str) -> dict[str, Any]:
    summary = load_json(path)
    require(summary.get("status") == "pass", f"{gate} gate did not pass")
    input_section = summary.get("input")
    require(isinstance(input_section, dict), f"{gate} gate has no input identity")
    rtl_sha256 = input_section.get("complete_rtl_sha256")
    require(isinstance(rtl_sha256, str) and SHA256_PATTERN.fullmatch(rtl_sha256),
            f"{gate} gate has invalid RTL SHA256")
    require(summary.get("scope") == "complete-spinal-rtl",
            f"{gate} gate has the wrong scope")
    require(summary.get("target") == "core-top-compat",
            f"{gate} gate has the wrong target")
    require(input_section.get("snapshot_sha256") == rtl_sha256,
            f"{gate} gate snapshot uses a different RTL")
    require(input_section.get("stable") is True, f"{gate} gate input is not stable")
    provenance = summary.get("provenance")
    require(isinstance(provenance, dict), f"{gate} gate has no provenance")
    result: dict[str, Any] = {
        "status": "passed",
        "summary_path": relative_path(root, path),
        "summary_sha256": sha256_file(path),
        "published_rtl_sha256": rtl_sha256,
        "evaluator_sha256": provenance.get("evaluator_sha256"),
        "manifest_sha256": provenance.get("manifest_sha256"),
        "ports_contract_sha256": provenance.get("ports_contract_sha256"),
    }
    if gate == "port_contract":
        contract = input_section.get("contract")
        require(isinstance(contract, dict), "port gate has no contract summary")
        result["contract"] = contract
        yosys = summary.get("yosys")
        require(isinstance(yosys, dict), "port gate has no Yosys result")
        require(yosys.get("returncode") == 0 and yosys.get("timed_out") is False,
                "port gate Yosys did not complete cleanly")
        require(yosys.get("skip_markers") == [], "port gate contains skip markers")
        tool = yosys.get("tool")
        require(isinstance(tool, dict), "port gate has no Yosys identity")
        result["tool"] = {
            "name": "yosys",
            "version": tool.get("version"),
            "sha256": tool.get("sha256"),
        }
    elif gate == "verilator_lint":
        warning_policy = summary.get("warning_policy")
        verilator = summary.get("verilator")
        require(isinstance(warning_policy, dict) and isinstance(verilator, dict),
                "lint gate has incomplete tool summary")
        require(summary.get("skip_markers") == [], "lint gate contains skip markers")
        require(summary.get("unexpected_errors") == [],
                "lint gate contains unexpected errors")
        require(verilator.get("returncode") == 0 and verilator.get("timed_out") is False,
                "Verilator did not complete cleanly")
        require(warning_policy.get("mode") == "strict-zero" and
                warning_policy.get("actual_warning_count") == 0,
                "lint gate does not use the strict zero-warning policy")
        result["warning_policy"] = "strict-zero"
        result["warning_count"] = 0
        result["tool"] = {
            "name": "verilator",
            "version": verilator.get("version"),
            "sha256": verilator.get("sha256"),
        }
    elif gate == "yosys":
        yosys = summary.get("yosys")
        require(isinstance(yosys, dict), "Yosys gate has no tool summary")
        tool = yosys.get("tool")
        require(isinstance(tool, dict), "Yosys gate has no tool identity")
        require(yosys.get("returncode") == 0 and yosys.get("timed_out") is False,
                "Yosys did not complete cleanly")
        require(yosys.get("skip_markers") == [], "Yosys gate contains skip markers")
        result["tool"] = {
            "name": "yosys",
            "version": tool.get("version"),
            "sha256": tool.get("sha256"),
        }
    for field in ("evaluator_sha256", "manifest_sha256", "ports_contract_sha256"):
        require(isinstance(result[field], str) and SHA256_PATTERN.fullmatch(result[field]),
                f"{gate} gate has invalid {field}")
    current_provenance = {
        "evaluator_sha256": sha256_file(root / "scripts/cpu/rtl_contract.py"),
        "manifest_sha256": sha256_file(root / "cpu/reference/manifest.lock"),
        "ports_contract_sha256": sha256_file(
            root / "cpu/reference/core-top.ports.json"
        ),
    }
    for field, expected in current_provenance.items():
        require(result[field] == expected,
                f"{gate} gate uses stale or different {field}")
    return result


def build_evidence(
    root: Path,
    python_log: Path,
    recorded_python_log: Path,
    scala_run_manifest: Path,
    tracked_paths: list[str],
) -> dict[str, Any]:
    generation, identity = summarize_generation(
        root, root / "build/rtl/generation-manifest.json"
    )
    gates = {
        "port_contract": summarize_gate(
            root, root / "build/gates/port/summary.json", "port_contract"
        ),
        "verilator_lint": summarize_gate(
            root, root / "build/gates/lint/summary.json", "verilator_lint"
        ),
        "yosys": summarize_gate(
            root, root / "build/gates/yosys/summary.json", "yosys"
        ),
    }
    for name, gate in gates.items():
        require(gate["published_rtl_sha256"] == identity["published_rtl_sha256"],
                f"{name} gate uses a different published RTL")
    return {
        "schema_version": 2,
        "evidence_type": "local_cpu_verification",
        "scope": "local_only_no_vivado_or_fpga_claim",
        "cpu_identity": {
            "source_commit": identity["source_commit"],
            "profile": identity["custom_profile"],
            "source_tree_sha256": identity["source_tree_sha256"],
            "raw_rtl_sha256": identity["raw_rtl_sha256"],
            "published_rtl_sha256": identity["published_rtl_sha256"],
        },
        "artifact_policy": {
            "tracked": tracked_paths,
            "ignored_rebuildable": [
                {"path": "cpu/target/test-reports", "rebuild": "make cpu-test-all"},
                {"path": "build/rtl", "rebuild": "make cpu-generate"},
                {"path": "build/gates", "rebuild": "make cpu-locked-gates"},
                {"path": "build/evidence", "rebuild": "make cpu-check"},
            ],
            "tracked_summary_is_not_hardware_evidence": True,
        },
        "scala": summarize_scala_run(root, scala_run_manifest),
        "python_contract": summarize_python_log(
            root, python_log, recorded_python_log
        ),
        "rtl_generation": generation,
        "gates": gates,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--python-log", type=Path, required=True)
    parser.add_argument("--scala-run", type=Path, required=True)
    parser.add_argument("--recorded-python-log", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--index", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.out.resolve()
    require(output.is_relative_to(root), "output must stay inside the repository")
    source_log = args.python_log.resolve()
    scala_run_manifest = args.scala_run.resolve()
    require(scala_run_manifest.is_relative_to(root),
            "Scala run manifest must stay inside the repository")
    recorded_log = (
        args.recorded_python_log.resolve()
        if args.recorded_python_log is not None else source_log
    )
    require(recorded_log.is_relative_to(root),
            "recorded Python log must stay inside the repository")
    tracked_paths = []
    if (output == root / "evidence/current/local-verification.json" and
            recorded_log == root / "evidence/current/python-contract.log"):
        tracked_paths = [
            "evidence/current/local-verification.json",
            "evidence/current/python-contract.log",
        ]
    document = build_evidence(
        root, source_log, recorded_log, scala_run_manifest, tracked_paths
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_output = output.with_name(f".{output.name}.tmp")
    temporary_output.write_text(
        json.dumps(document, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if recorded_log != source_log:
        recorded_log.parent.mkdir(parents=True, exist_ok=True)
        temporary_log = recorded_log.with_name(f".{recorded_log.name}.tmp")
        temporary_log.write_bytes(source_log.read_bytes())
        os.replace(temporary_log, recorded_log)
    os.replace(temporary_output, output)
    if args.index is not None:
        index_path = args.index.resolve()
        require(index_path == root / "evidence/index.json",
                "evidence index path must be evidence/index.json")
        require(output == root / "evidence/current/local-verification.json",
                "index updates require the tracked local evidence path")
        index = load_json(index_path)
        current_cpu = index.get("current_cpu")
        require(isinstance(current_cpu, dict), "evidence index has no current CPU")
        reference = current_cpu.get("local_evidence")
        require(isinstance(reference, dict) and
                reference.get("path") == "evidence/current/local-verification.json",
                "evidence index has an unsupported local evidence reference")
        reference["sha256"] = sha256_file(output)
        temporary_index = index_path.with_name(f".{index_path.name}.tmp")
        temporary_index.write_text(
            json.dumps(index, indent=2, ensure_ascii=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary_index, index_path)
    print(output.relative_to(root))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (
        EvidenceError,
        EvidenceIdentityError,
        OSError,
        json.JSONDecodeError,
        ET.ParseError,
    ) as error:
        print(f"local evidence failed: {error}", file=sys.stderr)
        raise SystemExit(1)
