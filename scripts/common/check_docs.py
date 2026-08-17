#!/usr/bin/env python3
"""Validate documentation entry points and evidence identity."""

from __future__ import annotations

import argparse
import ast
import hashlib
import io
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tarfile
from typing import Optional
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

from content_hash import IGNORED_PARTS, cpu_source_hash
from evidence_identity import (
    PYTHON_CONTRACT_INPUT_SCOPE,
    SCALA_INPUT_SCOPE,
    TREE_HASH_ALGORITHM,
    git_tracked_files,
    parse_python_contract_log as parse_python_contract_log_text,
    scala_report_snapshot as collect_scala_report_snapshot,
    tracked_content_tree_sha256,
)


ROOT = Path(__file__).resolve().parents[2]
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
CPU_INPUT_PATHS = ("cpu/build.sbt", "cpu/project", "cpu/src/main")
VERIFICATION_STATES = {"not_run", "passed", "failed"}
HARDWARE_STAGES = ("vivado_implementation", "fpga_func", "fpga_perf20")
LINUX_VERIFICATION_STATES = {
    "not_run_for_this_cpu_source",
    "passed",
    "failed",
}
MARKDOWN_LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)\n]+)\)")
GENERATED_RTL_TEST_ID = (
    "test_core_interface.CoreInterfaceTest.test_generated_rtl_when_present"
)
GENERATED_RTL_SKIP_REASON = "尚未生成发布 RTL"
def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def require_link(document: str, source: Path, target: str) -> None:
    require(f"]({target})" in document,
            f"{source.relative_to(ROOT)} must link {target}")
    resolved = (source.parent / target).resolve()
    require(resolved.is_relative_to(ROOT.resolve()),
            f"{source.relative_to(ROOT)} link escapes the repository: {target}")
    require(resolved.exists(),
            f"{source.relative_to(ROOT)} link target does not exist: {target}")


def validate_relative_markdown_links() -> None:
    documents = sorted(ROOT.glob("*.md")) + sorted((ROOT / "docs").rglob("*.md"))
    for source in documents:
        document = source.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK_PATTERN.finditer(document):
            raw_target = match.group(1).strip()
            if raw_target.startswith("<") and ">" in raw_target:
                target = raw_target[1:raw_target.index(">")]
            else:
                target = raw_target.split(maxsplit=1)[0]
            parsed = urlsplit(target)
            if parsed.scheme or target.startswith(("#", "//")):
                continue
            relative = unquote(parsed.path)
            if not relative:
                continue
            resolved = (source.parent / relative).resolve()
            require(resolved.is_relative_to(ROOT.resolve()),
                    f"{source.relative_to(ROOT)} link escapes the repository: {target}")
            require(resolved.exists(),
                    f"{source.relative_to(ROOT)} link target does not exist: {target}")


def require_hash(value: object, pattern: re.Pattern[str], label: str) -> str:
    require(isinstance(value, str) and pattern.fullmatch(value) is not None,
            f"{label} has an invalid format")
    return value


def require_int(value: object, label: str, minimum: int = 0) -> int:
    require(type(value) is int and value >= minimum,
            f"{label} must be an integer >= {minimum}")
    return value


def require_number(value: object, label: str) -> float:
    require(type(value) in (int, float), f"{label} must be numeric")
    number = float(value)
    require(math.isfinite(number), f"{label} must be finite")
    return number


def require_nonempty_string(value: object, label: str) -> str:
    require(isinstance(value, str) and bool(value.strip()),
            f"{label} must be a non-empty string")
    return value


def require_in_documents(
    documents: dict[str, str],
    value: str,
    label: str,
) -> None:
    for name, document in documents.items():
        require(value in document, f"{name} is missing {label}: {value}")


def count_python_contract_tests() -> int:
    count = 0
    for path in git_tracked_files(ROOT, ("cpu/tests/python",)):
        if not path.name.startswith("test_") or path.suffix != ".py":
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        count += sum(
            1
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        )
    return count


def git_output(arguments: list[str], label: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    detail = result.stderr.strip() or result.stdout.strip()
    require(result.returncode == 0,
            f"unable to {label}: {detail or f'git exited {result.returncode}'}")
    return result.stdout.strip()


def require_git_diff_clean(arguments: list[str], label: str) -> None:
    result = subprocess.run(
        ["git", "diff", "--quiet", *arguments],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode == 1:
        raise ValueError(f"{label} differ")
    detail = result.stderr.strip() or result.stdout.strip()
    require(result.returncode == 0,
            f"unable to compare {label}: {detail or f'git exited {result.returncode}'}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repository_file(value: object, label: str, *, tracked: bool = True) -> Path:
    relative = require_nonempty_string(value, f"{label} path")
    path = (ROOT / relative).resolve()
    require(path.is_relative_to(ROOT.resolve()), f"{label} path escapes the repository")
    require(path.is_file(), f"{label} file does not exist: {relative}")
    require(path.relative_to(ROOT).as_posix() == relative,
            f"{label} path must be normalized and repository-relative")
    if tracked:
        result = subprocess.run(
            ["git", "ls-files", "--error-unmatch", "--", relative],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        require(result.returncode == 0, f"{label} file must be tracked: {relative}")
    return path


def git_cpu_source_hash(commit: str) -> str:
    result = subprocess.run(
        ["git", "archive", "--format=tar", commit, "--", *CPU_INPUT_PATHS],
        cwd=ROOT,
        check=False,
        capture_output=True,
    )
    detail = result.stderr.decode(errors="replace").strip()
    require(result.returncode == 0,
            f"unable to read CPU inputs from {commit}: " +
            (detail or f"git exited {result.returncode}"))

    entries: list[tuple[str, bytes]] = []
    with tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:") as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            path = Path(member.name)
            require(path.parts and path.parts[0] == "cpu",
                    f"unexpected CPU archive path: {member.name}")
            relative = Path(*path.parts[1:])
            if any(part in IGNORED_PARTS for part in relative.parts):
                continue
            stream = archive.extractfile(member)
            require(stream is not None, f"unable to read CPU archive member: {member.name}")
            entries.append((relative.as_posix(), stream.read()))

    digest = hashlib.sha256()
    for relative, payload in sorted(entries):
        encoded = relative.encode()
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def validate_hardware_record(
    record: dict[str, object],
    label: str,
) -> dict[str, object]:
    repository_commit = require_hash(record.get("repository_commit"), COMMIT_PATTERN,
                                     f"{label} repository commit")
    source_commit = require_hash(record.get("source_commit"), COMMIT_PATTERN,
                                 f"{label} source commit")
    chiplab_commit = require_hash(record.get("chiplab_commit"), COMMIT_PATTERN,
                                  f"{label} Chiplab commit")
    published_rtl = require_hash(record.get("published_rtl_sha256"), SHA256_PATTERN,
                                 f"{label} published RTL SHA256")
    package_sha256 = require_hash(record.get("package_sha256"), SHA256_PATTERN,
                                  f"{label} package SHA256")
    vivado_version = require_nonempty_string(record.get("vivado_version"),
                                             f"{label} Vivado version")
    part = require_nonempty_string(record.get("part"), f"{label} FPGA part")
    profile = require_nonempty_string(record.get("profile"), f"{label} profile")
    frequency = require_number(record.get("frequency_mhz"), f"{label} frequency")
    setup_wns = require_number(record.get("setup_wns_ns"), f"{label} setup WNS")
    hold_wns = require_number(record.get("hold_wns_ns"), f"{label} hold WNS")
    drc_errors = require_int(record.get("drc_errors"), f"{label} DRC errors")
    require(frequency > 0, f"{label} frequency must be positive")

    perf20 = record.get("perf20")
    require(isinstance(perf20, dict), f"{label} perf20 must be an object")
    perf_passed = require_int(perf20.get("passed"),
                              f"{label} perf20 passed count", minimum=1)
    perf_total = require_int(perf20.get("total"),
                             f"{label} perf20 total count", minimum=1)
    cpu_count = require_int(perf20.get("cpu_count"),
                            f"{label} perf20 CPU cycles", minimum=1)
    soc_count = require_int(perf20.get("soc_count"),
                            f"{label} perf20 SoC cycles", minimum=1)
    job_id = require_nonempty_string(perf20.get("job_id"), f"{label} perf20 job ID")
    require(perf_passed <= perf_total, f"{label} perf20 passed count exceeds total count")

    return {
        "repository_commit": repository_commit,
        "source_commit": source_commit,
        "chiplab_commit": chiplab_commit,
        "published_rtl_sha256": published_rtl,
        "package_sha256": package_sha256,
        "vivado_version": vivado_version,
        "part": part,
        "profile": profile,
        "frequency_mhz": frequency,
        "setup_wns_ns": setup_wns,
        "hold_wns_ns": hold_wns,
        "drc_errors": drc_errors,
        "perf_passed": perf_passed,
        "perf_total": perf_total,
        "cpu_count": cpu_count,
        "soc_count": soc_count,
        "job_id": job_id,
    }


def validate_hardware_git_identity(
    values: dict[str, object],
    label: str,
) -> None:
    source_commit = str(values["source_commit"])
    repository_commit = str(values["repository_commit"])
    chiplab_commit = str(values["chiplab_commit"])
    require_git_diff_clean(
        [source_commit, repository_commit, "--", *CPU_INPUT_PATHS],
        f"{label} source commit and repository CPU inputs",
    )
    chiplab_entry = git_output(
        ["ls-tree", repository_commit, "--", "chiplab"],
        f"read the {label} Chiplab gitlink",
    )
    require(chiplab_entry == f"160000 commit {chiplab_commit}\tchiplab",
            f"{label} Chiplab commit does not match its repository commit")


def validate_current_hardware_stages(
    current_cpu: dict[str, object],
    verification: dict[str, object],
    current_commit: str,
    current_rtl: str,
    current_profile: str,
) -> dict[str, dict[str, object]]:
    del current_commit, current_rtl, current_profile
    hardware_evidence = current_cpu.get("hardware_evidence")
    require(hardware_evidence == {},
            "current CPU hardware evidence must remain empty until raw artifacts are imported")
    for stage in HARDWARE_STAGES:
        state = str(verification[stage])
        require(state == "not_run",
                f"current CPU {stage} must remain not_run until raw artifacts are imported")
    require(current_cpu.get("performance_claim") == "none",
            "current CPU performance claim requires imported raw hardware evidence")
    return {}


def require_passing_hardware(values: dict[str, object], label: str) -> None:
    require(float(values["setup_wns_ns"]) >= 0,
            f"{label} setup WNS must be non-negative")
    require(float(values["hold_wns_ns"]) >= 0,
            f"{label} hold WNS must be non-negative")
    require(int(values["drc_errors"]) == 0,
            f"{label} must have zero DRC errors")
    require(values["perf_passed"] == values["perf_total"],
            f"{label} requires all perf20 tests to pass")


def load_optional_json(path: Path) -> Optional[dict[str, object]]:
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict),
            f"optional artifact {path.relative_to(ROOT)} must contain an object")
    return value


def parse_python_contract_log(
    path: Path,
) -> dict[str, object]:
    text = path.read_text(encoding="utf-8")
    parsed = parse_python_contract_log_text(text)
    return {**parsed, "text": text}


def validate_python_contract_log(path: Path, expected: dict[str, object]) -> None:
    parsed = parse_python_contract_log(path)
    summary = parsed["summary"]
    require(isinstance(summary, dict), "tracked Python contract summary is invalid")
    require(summary["test_count"] == expected["test_count"] and
            summary["passed_count"] == expected["passed_count"] and
            summary["failure_count"] == expected["failure_count"] and
            summary["error_count"] == expected["error_count"] and
            summary["skipped_count"] == expected["skipped_count"],
            "tracked Python log summary does not match local evidence")
    require(summary["expected_failure_count"] == 0 and
            summary["unexpected_success_count"] == 0,
            "tracked Python log contains unsupported unittest outcomes")
    require(parsed["result"] == "PASS", "tracked Python contract log is not passing")
    expected_input = {
        "hash_algorithm": expected["contract_input_hash_algorithm"],
        "file_count": expected["contract_input_file_count"],
        "tree_sha256": expected["contract_input_tree_sha256"],
    }
    require(parsed["input"] == expected_input,
            "tracked Python log input identity does not match local evidence")


def validate_optional_python_contract_log(
    path: Path,
    expected: dict[str, object],
) -> None:
    if sha256_file(path) == expected["log_sha256"]:
        validate_python_contract_log(path, expected)
        return

    published_rtl = ROOT / "build/rtl/mycpu_top.v"
    require(not published_rtl.is_file(),
            "local Python contract log differs from tracked evidence")
    require(int(expected["passed_count"]) >= 1,
            "local Python contract log differs from tracked evidence")
    clean_clone_expected = dict(expected)
    clean_clone_expected["passed_count"] = int(expected["passed_count"]) - 1
    clean_clone_expected["skipped_count"] = int(expected["skipped_count"]) + 1
    validate_python_contract_log(path, clean_clone_expected)

    local = parse_python_contract_log(path)
    entries = local["entries"]
    local_text = local["text"]
    require(isinstance(entries, list) and isinstance(local_text, str),
            "clean-clone Python contract log is invalid")
    require(
        f"SKIP {GENERATED_RTL_TEST_ID} | {GENERATED_RTL_SKIP_REASON}" in
        local_text.splitlines(),
        "clean-clone Python contract log has an unexpected skip reason",
    )

    tracked_path = ROOT / "evidence/current/python-contract.log"
    require(tracked_path.is_file(),
            "tracked Python contract log is missing")
    tracked = parse_python_contract_log(tracked_path)
    tracked_entries = tracked["entries"]
    require(isinstance(tracked_entries, list), "tracked Python entries are invalid")
    require([test_id for _, test_id, _ in entries] ==
            [test_id for _, test_id, _ in tracked_entries],
            "clean-clone Python contract log has a different test set")
    for local_entry, tracked_entry in zip(entries, tracked_entries):
        _, test_id, _ = local_entry
        if test_id == GENERATED_RTL_TEST_ID:
            require(tracked_entry[0] == "PASS" and local_entry == (
                "SKIP", GENERATED_RTL_TEST_ID, GENERATED_RTL_SKIP_REASON
            ), "clean-clone generated RTL outcome is invalid")
        else:
            require(local_entry == tracked_entry,
                    "clean-clone Python contract log changed an unrelated outcome")


def scala_report_snapshot(report_directory: Path) -> Optional[dict[str, object]]:
    if not any(report_directory.glob("TEST-*.xml")):
        return None
    return collect_scala_report_snapshot(ROOT, report_directory)


def validate_local_evidence_reference(
    reference: object,
    current_cpu: dict[str, object],
) -> dict[str, object]:
    require(isinstance(reference, dict), "current CPU local_evidence must be an object")
    require(set(reference) == {"path", "sha256"},
            "current CPU local_evidence has an unsupported schema")
    path = repository_file(reference.get("path"), "current local verification evidence")
    expected_sha256 = require_hash(reference.get("sha256"), SHA256_PATTERN,
                                   "current local verification evidence SHA256")
    require(sha256_file(path) == expected_sha256,
            "current local verification evidence SHA256 does not match")
    document = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(document, dict), "current local verification evidence must be an object")
    require(set(document) == {
        "schema_version", "evidence_type", "scope", "cpu_identity",
        "artifact_policy", "scala", "python_contract", "rtl_generation", "gates",
    }, "current local verification evidence has an unsupported schema")
    require(document.get("schema_version") == 2,
            "current local verification evidence must use schema version 2")
    require(document.get("evidence_type") == "local_cpu_verification",
            "current local verification evidence has the wrong type")
    require(document.get("scope") == "local_only_no_vivado_or_fpga_claim",
            "current local verification evidence has the wrong scope")

    identity = document.get("cpu_identity")
    require(isinstance(identity, dict), "current local evidence has no CPU identity")
    expected_identity = {
        "source_commit": current_cpu["source_commit"],
        "profile": current_cpu["profile"],
        "source_tree_sha256": current_cpu["source_tree_sha256"],
        "raw_rtl_sha256": current_cpu["raw_rtl_sha256"],
        "published_rtl_sha256": current_cpu["published_rtl_sha256"],
    }
    require(identity == expected_identity, "current local evidence uses a different CPU identity")

    policy = document.get("artifact_policy")
    require(isinstance(policy, dict), "current local evidence has no artifact policy")
    require(policy.get("tracked") == [
        "evidence/current/local-verification.json",
        "evidence/current/python-contract.log",
    ], "current local evidence tracked artifact policy is incorrect")
    require(policy.get("ignored_rebuildable") == [
        {"path": "cpu/target/test-reports", "rebuild": "make cpu-test-all"},
        {"path": "build/rtl", "rebuild": "make cpu-generate"},
        {"path": "build/gates", "rebuild": "make cpu-locked-gates"},
        {"path": "build/evidence", "rebuild": "make cpu-check"},
    ], "current local evidence ignored artifact policy is incorrect")
    require(policy.get("tracked_summary_is_not_hardware_evidence") is True,
            "current local evidence must not claim hardware verification")

    scala = document.get("scala")
    require(isinstance(scala, dict), "current local evidence has no Scala result")
    require(scala.get("status") == "passed", "current Scala evidence is not passing")
    require(scala.get("command") == "make cpu-test-all",
            "current Scala evidence command is incorrect")
    require(scala.get("report_directory") == "cpu/target/test-reports",
            "current Scala evidence report directory is incorrect")
    require(scala.get("hash_algorithm") == "path-payload-sha256-v1",
            "current Scala evidence hash algorithm is incorrect")
    require(scala.get("raw_reports_tracked") is False,
            "current Scala raw reports must remain ignored")
    require(scala.get("run_manifest_path") == "build/evidence/scala-run.json",
            "current Scala run manifest path is incorrect")
    require_hash(scala.get("run_manifest_sha256"), SHA256_PATTERN,
                 "current Scala run manifest SHA256")
    require(scala.get("input_hash_algorithm") == TREE_HASH_ALGORITHM,
            "current Scala input hash algorithm is incorrect")
    require(scala.get("input_scope") == list(SCALA_INPUT_SCOPE),
            "current Scala input scope is incorrect")
    scala_input_sha256, scala_input_file_count = tracked_content_tree_sha256(
        ROOT, SCALA_INPUT_SCOPE
    )
    require(scala.get("input_file_count") == scala_input_file_count,
            "current Scala input file count does not match")
    require(scala.get("input_tree_sha256") == scala_input_sha256,
            "current Scala input tree SHA256 does not match")
    reports = scala.get("reports")
    require(isinstance(reports, list) and bool(reports),
            "current Scala evidence must list report hashes")
    require(len(reports) == require_int(scala.get("suite_count"), "Scala suite count", 1),
            "current Scala report list does not match suite count")
    seen_paths = set()
    seen_suites = set()
    report_totals = {"tests": 0, "errors": 0, "failures": 0, "skipped": 0}
    for entry in reports:
        require(isinstance(entry, dict), "Scala report evidence must be an object")
        report_path = require_nonempty_string(entry.get("path"), "Scala report path")
        suite_name = require_nonempty_string(entry.get("suite"), "Scala report suite")
        require(report_path == f"cpu/target/test-reports/TEST-{suite_name}.xml",
                "Scala report path does not match suite name")
        require(report_path not in seen_paths and suite_name not in seen_suites,
                "Scala report evidence contains duplicates")
        seen_paths.add(report_path)
        seen_suites.add(suite_name)
        require_hash(entry.get("sha256"), SHA256_PATTERN, "Scala report SHA256")
        for source, target in (
            ("tests", "tests"), ("errors", "errors"),
            ("failures", "failures"), ("skipped", "skipped"),
        ):
            report_totals[target] += require_int(
                entry.get(source), f"Scala report {source} count"
            )
    require(report_totals == {
        "tests": scala.get("test_count"),
        "errors": scala.get("error_count"),
        "failures": scala.get("failure_count"),
        "skipped": scala.get("skipped_count"),
    }, "Scala per-report counts do not match aggregate evidence")
    require(scala.get("error_count") == 0 and scala.get("failure_count") == 0,
            "current Scala evidence contains failures")
    require_hash(scala.get("report_tree_sha256"), SHA256_PATTERN,
                 "Scala report tree SHA256")

    python_contract = document.get("python_contract")
    require(isinstance(python_contract, dict),
            "current local evidence has no Python contract result")
    require(python_contract.get("status") == "passed",
            "current Python contract evidence is not passing")
    require(python_contract.get("command") == "make cpu-contract-test",
            "current Python contract command is incorrect")
    total = require_int(python_contract.get("test_count"), "Python test count", 1)
    passed = require_int(python_contract.get("passed_count"), "Python passed count")
    skipped = require_int(python_contract.get("skipped_count"), "Python skipped count")
    require(python_contract.get("failure_count") == 0 and
            python_contract.get("error_count") == 0,
            "current Python contract evidence contains failures")
    require(passed + skipped == total, "Python contract counts do not conserve tests")
    require(total == count_python_contract_tests(),
            "Python evidence count does not match discovered contract tests")
    log_path = repository_file(python_contract.get("log"),
                               "current Python contract log")
    require(log_path.relative_to(ROOT).as_posix() ==
            "evidence/current/python-contract.log",
            "current Python contract log path is incorrect")
    require(sha256_file(log_path) == require_hash(
        python_contract.get("log_sha256"), SHA256_PATTERN,
        "current Python contract log SHA256",
    ), "current Python contract log SHA256 does not match")
    runner_path = ROOT / "scripts/common/run_python_contracts.py"
    require(sha256_file(runner_path) == require_hash(
        python_contract.get("runner_sha256"), SHA256_PATTERN,
        "current Python contract runner SHA256",
    ), "current Python contract runner SHA256 does not match")
    require(python_contract.get("contract_input_hash_algorithm") ==
            TREE_HASH_ALGORITHM,
            "current Python contract input hash algorithm is incorrect")
    require(python_contract.get("contract_input_scope") ==
            list(PYTHON_CONTRACT_INPUT_SCOPE),
            "current Python contract input scope is incorrect")
    input_tree_sha256, input_file_count = tracked_content_tree_sha256(
        ROOT, PYTHON_CONTRACT_INPUT_SCOPE
    )
    require(python_contract.get("contract_input_file_count") == input_file_count,
            "current Python contract input file count does not match")
    require(python_contract.get("contract_input_tree_sha256") == input_tree_sha256,
            "current Python contract input tree SHA256 does not match")
    validate_python_contract_log(log_path, python_contract)

    generation = document.get("rtl_generation")
    require(isinstance(generation, dict), "current local evidence has no RTL generation")
    require(generation.get("status") == "passed",
            "current RTL generation evidence is not passing")
    require(generation.get("command") ==
            "make cpu-locked-gates CUSTOM_PROFILE=disabled",
            "current RTL generation command is incorrect")
    require(generation.get("manifest_path") == "build/rtl/generation-manifest.json",
            "current RTL generation manifest path is incorrect")
    require_hash(generation.get("manifest_sha256"), SHA256_PATTERN,
                 "current RTL generation manifest SHA256")
    require(generation.get("raw_artifacts_tracked") is False,
            "current generated RTL must remain ignored")
    toolchain = generation.get("toolchain")
    require(isinstance(toolchain, dict) and
            all(isinstance(value, str) and value for value in toolchain.values()),
            "current RTL generation toolchain is incomplete")

    gates = document.get("gates")
    require(isinstance(gates, dict) and
            set(gates) == {"port_contract", "verilator_lint", "yosys"},
            "current local evidence has an unsupported gate set")
    expected_paths = {
        "port_contract": "build/gates/port/summary.json",
        "verilator_lint": "build/gates/lint/summary.json",
        "yosys": "build/gates/yosys/summary.json",
    }
    for name, gate in gates.items():
        require(isinstance(gate, dict), f"current {name} evidence must be an object")
        require(gate.get("status") == "passed", f"current {name} evidence is not passing")
        require(gate.get("summary_path") == expected_paths[name],
                f"current {name} summary path is incorrect")
        require_hash(gate.get("summary_sha256"), SHA256_PATTERN,
                     f"current {name} summary SHA256")
        require(gate.get("published_rtl_sha256") == current_cpu["published_rtl_sha256"],
                f"current {name} evidence uses a different published RTL")
        for field in ("evaluator_sha256", "manifest_sha256", "ports_contract_sha256"):
            require_hash(gate.get(field), SHA256_PATTERN, f"current {name} {field}")
        tool = gate.get("tool")
        require(isinstance(tool, dict) and
                set(tool) == {"name", "version", "sha256"},
                f"current {name} tool identity is incomplete")
        require_nonempty_string(tool.get("name"), f"current {name} tool name")
        require_nonempty_string(tool.get("version"), f"current {name} tool version")
        require_hash(tool.get("sha256"), SHA256_PATTERN, f"current {name} tool SHA256")
    current_provenance = {
        "evaluator_sha256": sha256_file(ROOT / "scripts/cpu/rtl_contract.py"),
        "manifest_sha256": sha256_file(ROOT / "cpu/reference/manifest.lock"),
        "ports_contract_sha256": sha256_file(
            ROOT / "cpu/reference/core-top.ports.json"
        ),
    }
    for name, gate in gates.items():
        for field, expected in current_provenance.items():
            require(gate.get(field) == expected,
                    f"current {name} {field} does not match the repository")
    port_contract = gates["port_contract"].get("contract")
    require(isinstance(port_contract, dict), "current port evidence has no contract")
    require(port_contract.get("port_count") == 49 and
            port_contract.get("tlbnum_default") == 32 and
            port_contract.get("legacy_backend_absent") is True,
            "current port evidence does not match the public CPU contract")
    lint = gates["verilator_lint"]
    require(lint.get("warning_policy") == "strict-zero" and
            lint.get("warning_count") == 0,
            "current lint evidence must use strict zero warnings")

    return document


def validate_optional_local_artifacts(
    current_cpu: dict[str, object],
    local_evidence: dict[str, object],
) -> None:
    report_directory = ROOT / "cpu/target/test-reports"
    report_snapshot = scala_report_snapshot(report_directory)
    if report_snapshot is not None:
        scala = local_evidence["scala"]
        require(isinstance(scala, dict), "tracked Scala evidence is invalid")
        for field in (
            "suite_count", "test_count", "error_count", "failure_count",
            "skipped_count",
        ):
            require(report_snapshot[field] == scala[field],
                    f"local Scala {field} does not match tracked evidence")
        stable_fields = ("path", "suite", "tests", "errors", "failures", "skipped")
        local_reports = [
            {field: entry[field] for field in stable_fields}
            for entry in report_snapshot["reports"]
        ]
        tracked_reports = [
            {field: entry[field] for field in stable_fields}
            for entry in scala["reports"]
        ]
        require(local_reports == tracked_reports,
                "local Scala suite results do not match tracked evidence")
        scala_run_path = ROOT / "build/evidence/scala-run.json"
        scala_run = load_optional_json(scala_run_path)
        require(scala_run is not None, "local Scala reports have no run manifest")
        require(scala_run.get("schema_version") == 1 and
                scala_run.get("evidence_type") == "scala_test_run" and
                scala_run.get("command") == "make cpu-test-all",
                "local Scala run manifest has an unsupported schema")
        require(scala_run.get("input") == {
            "hash_algorithm": scala["input_hash_algorithm"],
            "scope": scala["input_scope"],
            "file_count": scala["input_file_count"],
            "tree_sha256": scala["input_tree_sha256"],
        }, "local Scala run manifest input does not match tracked evidence")
        require(scala_run.get("reports") == report_snapshot,
                "local Scala run manifest reports do not match local XML reports")
    else:
        require(not (ROOT / "build/evidence/scala-run.json").is_file(),
                "local Scala run manifest exists without XML reports")

    manifest = load_optional_json(ROOT / "build/rtl/generation-manifest.json")
    if manifest is not None:
        for field, expected in (
            ("source_commit", current_cpu["source_commit"]),
            ("source_tree_sha256", current_cpu["source_tree_sha256"]),
            ("custom_profile", current_cpu["profile"]),
            ("raw_rtl_sha256", current_cpu["raw_rtl_sha256"]),
            ("published_rtl_sha256", current_cpu["published_rtl_sha256"]),
        ):
            require(manifest.get(field) == expected,
                    f"local RTL generation manifest {field} does not match evidence")
        generation = local_evidence["rtl_generation"]
        require(isinstance(generation, dict), "tracked RTL generation evidence is invalid")
        require(manifest.get("toolchain") == generation.get("toolchain"),
                "local RTL generation toolchain does not match tracked evidence")
        raw_rtl = ROOT / "build/rtl/raw/core_top.v"
        published_rtl = ROOT / "build/rtl/mycpu_top.v"
        require(raw_rtl.is_file() and published_rtl.is_file(),
                "local RTL generation manifest exists without both RTL outputs")
        require(sha256_file(raw_rtl) == current_cpu["raw_rtl_sha256"],
                "local raw RTL SHA256 does not match tracked evidence")
        require(sha256_file(published_rtl) == current_cpu["published_rtl_sha256"],
                "local published RTL SHA256 does not match tracked evidence")

    gates = local_evidence["gates"]
    require(isinstance(gates, dict), "tracked gate evidence is invalid")
    gate_artifacts = {
        "port_contract": ROOT / "build/gates/port/summary.json",
        "verilator_lint": ROOT / "build/gates/lint/summary.json",
        "yosys": ROOT / "build/gates/yosys/summary.json",
    }
    for evidence_field, path in gate_artifacts.items():
        summary = load_optional_json(path)
        if summary is None:
            continue
        require(summary.get("status") == "pass",
                f"local {path.relative_to(ROOT)} is not passing")
        input_section = summary.get("input")
        require(isinstance(input_section, dict),
                f"local {path.relative_to(ROOT)} has no input identity")
        require(input_section.get("complete_rtl_sha256") ==
                current_cpu["published_rtl_sha256"],
                f"local {path.relative_to(ROOT)} uses a different published RTL")
        gate_evidence = gates[evidence_field]
        require(isinstance(gate_evidence, dict), f"tracked {evidence_field} is invalid")
        require(summary.get("scope") == "complete-spinal-rtl" and
                summary.get("target") == "core-top-compat",
                f"local {path.relative_to(ROOT)} has the wrong scope or target")
        require(input_section.get("snapshot_sha256") == current_cpu["published_rtl_sha256"] and
                input_section.get("stable") is True,
                f"local {path.relative_to(ROOT)} does not use a stable RTL snapshot")
        provenance = summary.get("provenance")
        require(isinstance(provenance, dict),
                f"local {path.relative_to(ROOT)} has no provenance")
        for source, target in (
            ("evaluator_sha256", "evaluator_sha256"),
            ("manifest_sha256", "manifest_sha256"),
            ("ports_contract_sha256", "ports_contract_sha256"),
        ):
            require(provenance.get(source) == gate_evidence.get(target),
                    f"local {path.relative_to(ROOT)} {source} does not match evidence")
        for source, current_path in (
            ("evaluator_sha256", ROOT / "scripts/cpu/rtl_contract.py"),
            ("manifest_sha256", ROOT / "cpu/reference/manifest.lock"),
            ("ports_contract_sha256", ROOT / "cpu/reference/core-top.ports.json"),
        ):
            require(provenance.get(source) == sha256_file(current_path),
                    f"local {path.relative_to(ROOT)} {source} is stale")

        tool_evidence = gate_evidence.get("tool")
        require(isinstance(tool_evidence, dict),
                f"tracked {evidence_field} tool identity is invalid")
        if evidence_field == "verilator_lint":
            tool_result = summary.get("verilator")
            require(summary.get("skip_markers") == [] and
                    summary.get("unexpected_errors") == [],
                    "local Verilator gate contains skipped or unexpected results")
        else:
            yosys_result = summary.get("yosys")
            require(isinstance(yosys_result, dict),
                    f"local {evidence_field} has no Yosys result")
            require(yosys_result.get("skip_markers") == [] and
                    yosys_result.get("returncode") == 0 and
                    yosys_result.get("timed_out") is False,
                    f"local {evidence_field} Yosys did not complete cleanly")
            tool_result = yosys_result.get("tool")
        require(isinstance(tool_result, dict),
                f"local {evidence_field} has no tool result")
        require(tool_result.get("returncode", 0) == 0 and
                tool_result.get("timed_out", False) is False,
                f"local {evidence_field} tool did not complete cleanly")
        require(tool_result.get("version") == tool_evidence.get("version") and
                tool_result.get("sha256") == tool_evidence.get("sha256"),
                f"local {evidence_field} tool identity does not match evidence")
        if evidence_field == "port_contract":
            require(input_section.get("contract") == gate_evidence.get("contract"),
                    "local port contract summary does not match tracked evidence")

    lint_summary = load_optional_json(ROOT / "build/gates/lint/summary.json")
    if lint_summary is not None:
        warning_policy = lint_summary.get("warning_policy")
        require(isinstance(warning_policy, dict),
                "local lint summary has no warning policy")
        require(warning_policy.get("mode") == "strict-zero" and
                warning_policy.get("actual_warning_count") == 0,
                "local Verilator warning count does not match evidence")

    build_python_log = ROOT / "build/evidence/python-contract.log"
    if build_python_log.is_file():
        python_contract = local_evidence["python_contract"]
        require(isinstance(python_contract, dict), "tracked Python evidence is invalid")
        validate_optional_python_contract_log(build_python_log, python_contract)

    tracked_raw = git_output(["ls-files", "--", "build", "cpu/target"],
                             "check ignored raw artifacts")
    require(not tracked_raw, "raw build or Scala report artifacts must not be tracked")
    for probe in (
        "build/rtl/.evidence-probe",
        "build/gates/.evidence-probe",
        "build/evidence/.evidence-probe",
        "cpu/target/test-reports/.evidence-probe",
    ):
        result = subprocess.run(
            ["git", "check-ignore", "-q", "--no-index", probe],
            cwd=ROOT,
            check=False,
        )
        require(result.returncode == 0, f"raw artifact path must remain ignored: {probe}")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--structure-only", action="store_true")
    args = parser.parse_args()
    readme_path = ROOT / "README.md"
    docs_index_path = ROOT / "docs/README.md"
    status_path = ROOT / "docs/status.md"
    evidence_path = ROOT / "evidence/index.json"

    readme = readme_path.read_text(encoding="utf-8")
    docs_index = docs_index_path.read_text(encoding="utf-8")
    status = status_path.read_text(encoding="utf-8")
    architecture = (ROOT / "docs/architecture.md").read_text(encoding="utf-8")
    candidates = (ROOT / "docs/optimization-candidates.md").read_text(encoding="utf-8")
    workflow = (ROOT / "docs/verification-workflow.md").read_text(encoding="utf-8")
    current_plan = (ROOT / "docs/current-optimization-plan.md").read_text(encoding="utf-8")
    custom_instructions = (ROOT / "docs/custom-instructions.md").read_text(encoding="utf-8")
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))

    validate_relative_markdown_links()
    project_mark = ROOT / "docs/assets/miku-project-mark.png"
    require(project_mark.is_file(), "README project mark does not exist")
    require('src="docs/assets/miku-project-mark.png"' in readme,
            "README must use the repository-local project mark")

    for target in (
        "docs/README.md",
        "docs/status.md",
        "docs/architecture.md",
        "docs/optimization-candidates.md",
        "docs/custom-instructions.md",
        "docs/release-checklist.md",
        "docs/third-party-sources.md",
        "CONTRIBUTING.md",
        "evidence/index.json",
        "evidence/current/local-verification.json",
    ):
        require_link(readme, readme_path, target)

    for target in (
        "status.md",
        "environment.md",
        "migration-validation.md",
        "release-checklist.md",
        "../CONTRIBUTING.md",
        "architecture.md",
        "func-perf-build-contract.md",
        "linux-system-requirements.md",
        "custom-instructions.md",
        "verification-workflow.md",
        "optimization-candidates.md",
        "timing-static-audit-r5.md",
        "current-optimization-plan.md",
        "research-20260816-execution-log.md",
        "third-party-sources.md",
        "../evidence/index.json",
        "../evidence/current/local-verification.json",
    ):
        require_link(docs_index, docs_index_path, target)

    require_link(status, status_path, "README.md")
    require_link(status, status_path, "../evidence/index.json")
    require("[optimization-candidates.md](optimization-candidates.md)" in architecture,
            "architecture must link the candidate ledger")
    require("[optimization-candidates.md](optimization-candidates.md)" in workflow,
            "workflow must link the candidate ledger")
    require("[verification-workflow.md](verification-workflow.md)" in current_plan,
            "current plan must link the stable workflow contract")
    require("[optimization-candidates.md](optimization-candidates.md)" in current_plan,
            "current plan must link the candidate ledger")
    require("| ID | 方向 |" in candidates, "candidate ledger table is missing")
    require("| 状态 | 已测效果 |" in candidates,
            "candidate ledger must expose status and measured effect")

    ids = re.findall(r"^\|\s*([A-Z]+\d+)\s*\|", candidates, flags=re.MULTILINE)
    require(ids, "candidate ledger contains no candidate IDs")
    duplicates = sorted(candidate for candidate in set(ids) if ids.count(candidate) > 1)
    require(not duplicates, f"candidate IDs are duplicated: {', '.join(duplicates)}")
    for candidate in ("C01", "C08", "E02", "FT01"):
        require(candidate in ids, f"candidate ledger is missing {candidate}")
    require("## 5. 优化候选账本" not in architecture,
            "architecture must not contain a second candidate ledger")
    require("ContestCustomInstructionProfiles.scala" in custom_instructions,
            "custom-instruction guide must identify the contest profile catalog")
    require("make custom-test" in custom_instructions,
            "custom-instruction guide must document the focused test target")
    require("make custom-check CUSTOM_PROFILE=" in custom_instructions,
            "custom-instruction guide must document profile RTL checks")

    if args.structure_only:
        print(f"documentation structure: {len(ids)} unique candidate IDs")
        return 0

    require(evidence.get("schema_version") == 2,
            "evidence index must use schema version 2")
    require(set(evidence) == {
        "schema_version", "repository", "status_document", "current_cpu",
        "latest_hardware_reference", "claim_rules",
    }, "evidence index has an unsupported top-level schema")
    require(evidence.get("repository") ==
            "https://github.com/TossACoinTAC/NSCSCC2026-MIKU.git",
            "evidence index repository is incorrect")
    require(evidence.get("status_document") == "docs/status.md",
            "evidence index must reference docs/status.md")
    claim_rules = evidence.get("claim_rules")
    require(isinstance(claim_rules, list) and len(claim_rules) >= 4 and
            all(isinstance(rule, str) and rule for rule in claim_rules),
            "evidence index claim_rules are incomplete")

    current_cpu = evidence.get("current_cpu")
    hardware_reference = evidence.get("latest_hardware_reference")
    require(isinstance(current_cpu, dict), "evidence index current_cpu must be an object")
    require(isinstance(hardware_reference, dict),
            "evidence index hardware reference must be an object")
    require(set(current_cpu) == {
        "source_commit", "profile", "source_tree_sha256", "raw_rtl_sha256",
        "published_rtl_sha256", "local_evidence", "verification",
        "hardware_evidence", "performance_claim",
    }, "evidence index current_cpu has an unsupported schema")
    require(set(hardware_reference) == {
        "evidence_level", "artifact_availability", "repository_commit",
        "source_commit", "chiplab_commit", "published_rtl_sha256",
        "vivado_version", "part", "profile", "frequency_mhz",
        "setup_wns_ns", "hold_wns_ns", "drc_errors", "perf20",
        "package_sha256", "evidence_document",
    }, "evidence index hardware reference has an unsupported schema")

    current_commit = require_hash(current_cpu.get("source_commit"), COMMIT_PATTERN,
                                  "current CPU source commit")
    current_source_hash = require_hash(current_cpu.get("source_tree_sha256"),
                                       SHA256_PATTERN,
                                       "current CPU source tree SHA256")
    current_raw_rtl = require_hash(current_cpu.get("raw_rtl_sha256"), SHA256_PATTERN,
                                   "current CPU raw RTL SHA256")
    current_rtl = require_hash(current_cpu.get("published_rtl_sha256"), SHA256_PATTERN,
                               "current CPU published RTL SHA256")
    current_profile = require_nonempty_string(current_cpu.get("profile"),
                                              "current CPU profile")

    actual_source_hash = cpu_source_hash(ROOT / "cpu")
    require(actual_source_hash == current_source_hash,
            "current CPU source tree SHA256 does not match the working tree")
    committed_source_hash = git_cpu_source_hash(current_commit)
    require(committed_source_hash == current_source_hash,
            "current CPU source tree SHA256 does not match its source commit")
    require_git_diff_clean(
        [current_commit, "--", *CPU_INPUT_PATHS],
        "working CPU inputs and current CPU source commit",
    )

    verification = current_cpu.get("verification")
    require(isinstance(verification, dict),
            "current CPU verification must be an object")
    require(set(verification) == {
        "local", "vivado_implementation", "fpga_func", "fpga_perf20",
        "linux_release",
    }, "current CPU verification has an unsupported schema")
    require(verification.get("local") == "passed",
            "current CPU local verification must be passed")
    for field in HARDWARE_STAGES:
        require(verification.get(field) in VERIFICATION_STATES,
                f"current CPU {field} has an unsupported state")
    require(verification.get("linux_release") in LINUX_VERIFICATION_STATES,
            "current CPU linux_release has an unsupported state")

    performance_claim = current_cpu.get("performance_claim")
    forbidden_top_level_hardware_fields = {
        "repository_commit",
        "chiplab_commit",
        "vivado_version",
        "part",
        "frequency_mhz",
        "setup_wns_ns",
        "hold_wns_ns",
        "drc_errors",
        "perf20",
        "job_id",
        "package_sha256",
    }
    unexpected = sorted(forbidden_top_level_hardware_fields.intersection(current_cpu))
    require(not unexpected,
            "current CPU contains hardware fields outside hardware_evidence: " +
            ", ".join(unexpected))

    local_evidence = validate_local_evidence_reference(
        current_cpu.get("local_evidence"), current_cpu
    )
    scala = local_evidence["scala"]
    python_contract = local_evidence["python_contract"]
    gates = local_evidence["gates"]
    require(isinstance(scala, dict) and isinstance(python_contract, dict) and
            isinstance(gates, dict), "current local evidence sections are invalid")
    scala_suites = int(scala["suite_count"])
    scala_tests = int(scala["test_count"])
    scala_skipped = int(scala["skipped_count"])
    python_passed = int(python_contract["passed_count"])
    python_skipped = int(python_contract["skipped_count"])
    lint_gate = gates["verilator_lint"]
    require(isinstance(lint_gate, dict), "current lint evidence is invalid")
    warning_count = int(lint_gate["warning_count"])
    current_hardware_values = validate_current_hardware_stages(
        current_cpu, verification, current_commit, current_rtl, current_profile
    )

    require(hardware_reference.get("evidence_level") == "summary_only",
            "hardware reference must be marked summary_only")
    require(hardware_reference.get("artifact_availability") == {
        "vivado_raw": "not_in_repository",
        "labagent_raw": "not_in_repository",
    }, "hardware reference artifact availability must be explicit")
    reference_values = validate_hardware_record(hardware_reference, "hardware reference")
    reference_repository_commit = str(reference_values["repository_commit"])
    reference_commit = str(reference_values["source_commit"])
    reference_chiplab = str(reference_values["chiplab_commit"])
    reference_rtl = str(reference_values["published_rtl_sha256"])
    reference_package = str(reference_values["package_sha256"])
    reference_vivado = str(reference_values["vivado_version"])
    reference_part = str(reference_values["part"])
    reference_profile = str(reference_values["profile"])
    reference_frequency = float(reference_values["frequency_mhz"])
    setup_wns = float(reference_values["setup_wns_ns"])
    hold_wns = float(reference_values["hold_wns_ns"])
    drc_errors = int(reference_values["drc_errors"])
    perf_passed = int(reference_values["perf_passed"])
    perf_total = int(reference_values["perf_total"])
    cpu_count = int(reference_values["cpu_count"])
    soc_count = int(reference_values["soc_count"])
    job_id = str(reference_values["job_id"])
    require_passing_hardware(reference_values, "hardware reference")
    evidence_document = hardware_reference.get("evidence_document")
    require(evidence_document == "docs/research-20260816-execution-log.md",
            "hardware reference evidence document is incorrect")
    evidence_document_path = (ROOT / str(evidence_document)).resolve()
    require(evidence_document_path.is_relative_to(ROOT.resolve()),
            "hardware reference evidence document escapes the repository")
    require(evidence_document_path.is_file(),
            "hardware reference evidence document does not exist")
    evidence_document_text = evidence_document_path.read_text(encoding="utf-8")

    validate_hardware_git_identity(reference_values, "hardware reference")

    current_documents = {"README.md": readme, "docs/status.md": status}
    for value, label in (
        (current_commit, "current CPU source commit"),
        (current_source_hash, "current CPU source tree SHA256"),
        (current_raw_rtl, "current CPU raw RTL SHA256"),
        (current_rtl, "current CPU published RTL SHA256"),
        (current_profile, "current CPU profile"),
    ):
        require_in_documents(current_documents, f"`{value}`", label)

    for field in (*HARDWARE_STAGES, "linux_release"):
        value = str(verification[field])
        require_in_documents(
            current_documents,
            f"| `{field}` | `{value}` |",
            f"current CPU {field} state",
        )
    require_in_documents(
        current_documents,
        f"| `performance_claim` | `{performance_claim}` |",
        "current CPU performance claim",
    )

    if performance_claim == "matching_hardware":
        vivado = current_hardware_values["vivado_implementation"]
        perf20_record = current_hardware_values["fpga_perf20"]
        vivado_result = vivado["result"]
        perf20_result = perf20_record["result"]
        require(isinstance(vivado_result, dict) and isinstance(perf20_result, dict),
                "current matching hardware result is invalid")
        for value, label in (
            (f"{float(vivado_result['frequency_mhz']):g} MHz",
             "current hardware frequency"),
            (f"`{float(vivado_result['setup_wns_ns']):+.3f} ns`",
             "current hardware setup WNS"),
            (f"`{float(vivado_result['hold_wns_ns']):+.3f} ns`",
             "current hardware hold WNS"),
            (f"{int(perf20_result['passed'])}／{int(perf20_result['total'])} passed",
             "current hardware perf20 result"),
            (f"`{int(perf20_result['cpu_count']):,}`",
             "current hardware CPU cycles"),
            (f"`{int(perf20_result['soc_count']):,}`",
             "current hardware SoC cycles"),
        ):
            require_in_documents(current_documents, value, label)

    for value, label in (
        (f"{scala_suites} suites，{scala_tests} tests passed，"
         f"{scala_skipped} skipped", "Scala test counts"),
        (f"{python_passed} passed，{python_skipped} skipped", "Python test counts"),
        (f"{warning_count} warnings", "Verilator warning count"),
    ):
        require_in_documents(current_documents, value, label)

    historical_documents = {
        "README.md": readme,
        "docs/status.md": status,
        str(evidence_document): evidence_document_text,
    }
    for marker, label in (
        ("`summary_only`", "hardware reference evidence level"),
        ("`not_in_repository`", "hardware reference raw artifact availability"),
    ):
        require_in_documents(
            {"README.md": readme, "docs/status.md": status}, marker, label
        )
    for value, label in (
        (reference_repository_commit, "hardware reference repository commit"),
        (reference_commit, "hardware reference source commit"),
        (reference_chiplab, "hardware reference Chiplab commit"),
        (reference_rtl, "hardware reference published RTL SHA256"),
        (reference_package, "hardware reference package SHA256"),
        (reference_vivado, "hardware reference Vivado version"),
        (reference_part, "hardware reference FPGA part"),
        (reference_profile, "hardware reference profile"),
        (job_id, "hardware reference job ID"),
    ):
        require_in_documents(historical_documents, f"`{value}`", label)

    for value, label in (
        (f"{reference_frequency:g} MHz", "hardware reference frequency"),
        (f"`{setup_wns:+.3f} ns`", "hardware reference setup WNS"),
        (f"`{hold_wns:+.3f} ns`", "hardware reference hold WNS"),
        (f"{perf_passed}／{perf_total} passed", "hardware reference perf20 result"),
        (f"`{cpu_count:,}`", "hardware reference CPU cycles"),
        (f"`{soc_count:,}`", "hardware reference SoC cycles"),
    ):
        require_in_documents(historical_documents, value, label)
    require_in_documents(historical_documents, f"{drc_errors} Error",
                         "hardware reference DRC result")

    validate_optional_local_artifacts(current_cpu, local_evidence)

    print(
        f"documentation contract: {len(ids)} unique candidate IDs; "
        "evidence metadata consistent"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, ET.ParseError) as error:
        print(f"documentation contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
