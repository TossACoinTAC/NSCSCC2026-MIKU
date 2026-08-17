#!/usr/bin/env python3
"""Validate documentation entry points and evidence identity."""

from __future__ import annotations

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


ROOT = Path(__file__).resolve().parents[2]
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
CPU_INPUT_PATHS = ("cpu/build.sbt", "cpu/project", "cpu/src/main")
VERIFICATION_STATES = {"not_run", "passed", "failed"}
LINUX_VERIFICATION_STATES = {
    "not_run_for_this_cpu_source",
    "passed",
    "failed",
}
MARKDOWN_LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)\n]+)\)")


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
    for path in sorted((ROOT / "cpu/tests/python").glob("test_*.py")):
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


def validate_optional_local_artifacts(
    current_cpu: dict[str, object],
    verification: dict[str, object],
) -> None:
    report_directory = ROOT / "cpu/target/test-reports"
    reports = sorted(report_directory.glob("TEST-*.xml"))
    if reports:
        suite_count = len(reports)
        test_count = 0
        error_count = 0
        failure_count = 0
        skipped_count = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            test_count += int(suite.attrib.get("tests", "0"))
            error_count += int(suite.attrib.get("errors", "0"))
            failure_count += int(suite.attrib.get("failures", "0"))
            skipped_count += int(suite.attrib.get("skipped", "0"))
        require(suite_count == verification["scala_suites"],
                "local Scala XML suite count does not match evidence")
        require(test_count == verification["scala_tests"],
                "local Scala XML test count does not match evidence")
        require(skipped_count == verification["scala_skipped"],
                "local Scala XML skipped count does not match evidence")
        require(error_count == 0 and failure_count == 0,
                "local Scala XML reports contain failures")

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
        require(verification.get("rtl_generation") == "passed",
                "an RTL generation manifest is present but evidence is not passed")

    gate_artifacts = (
        ("port_contract", ROOT / "build/gates/port/summary.json"),
        ("verilator_lint", ROOT / "build/gates/lint/summary.json"),
        ("yosys", ROOT / "build/gates/yosys/summary.json"),
    )
    for evidence_field, path in gate_artifacts:
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
        require(verification.get(evidence_field) == "passed",
                f"local {path.relative_to(ROOT)} passes but evidence does not")

    lint_summary = load_optional_json(ROOT / "build/gates/lint/summary.json")
    if lint_summary is not None:
        warning_policy = lint_summary.get("warning_policy")
        require(isinstance(warning_policy, dict),
                "local lint summary has no warning policy")
        require(warning_policy.get("actual_warning_count") ==
                verification["verilator_lint_warning_count"],
                "local Verilator warning count does not match evidence")

def main() -> int:
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

    require(evidence.get("schema_version") == 1,
            "evidence index must use schema version 1")
    require(evidence.get("repository") ==
            "https://github.com/TossACoinTAC/NSCSCC2026-MIKU.git",
            "evidence index repository is incorrect")
    require(evidence.get("status_document") == "docs/status.md",
            "evidence index must reference docs/status.md")

    current_cpu = evidence.get("current_cpu")
    hardware_reference = evidence.get("last_measured_hardware_reference")
    require(isinstance(current_cpu, dict), "evidence index current_cpu must be an object")
    require(isinstance(hardware_reference, dict),
            "evidence index hardware reference must be an object")

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
    scala_suites = require_int(verification.get("scala_suites"),
                               "Scala suite count", minimum=1)
    scala_tests = require_int(verification.get("scala_tests"),
                              "Scala test count", minimum=1)
    scala_skipped = require_int(verification.get("scala_skipped"),
                                "Scala skipped count")
    python_passed = require_int(verification.get("python_contract_passed"),
                                "Python passed count", minimum=1)
    python_skipped = require_int(verification.get("python_contract_skipped"),
                                 "Python skipped count")
    discovered_python_tests = count_python_contract_tests()
    require(python_passed + python_skipped == discovered_python_tests,
            "Python passed and skipped counts do not match discovered contract tests")
    warning_count = require_int(verification.get("verilator_lint_warning_count"),
                                "Verilator lint warning count")
    for field in ("scala_status", "rtl_generation", "port_contract",
                  "verilator_lint", "yosys"):
        require(verification.get(field) == "passed",
                f"current CPU {field} must match the recorded passing evidence")
    hardware_state_fields = ("vivado_implementation", "fpga_func", "fpga_perf20")
    for field in hardware_state_fields:
        require(verification.get(field) in VERIFICATION_STATES,
                f"current CPU {field} has an unsupported state")
    require(verification.get("linux_release") in LINUX_VERIFICATION_STATES,
            "current CPU linux_release has an unsupported state")

    performance_claim = current_cpu.get("performance_claim")
    require(performance_claim in {"none", "matching_hardware"},
            "current CPU performance claim has an unsupported state")
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

    current_hardware = current_cpu.get("hardware_evidence")
    current_hardware_values: Optional[dict[str, object]] = None
    if performance_claim == "none":
        require(current_hardware is None,
                "current CPU with no performance claim must not contain hardware_evidence")
        passed_hardware_states = [
            field for field in hardware_state_fields
            if verification.get(field) == "passed"
        ]
        require(not passed_hardware_states,
                "passed hardware states require matching hardware evidence: " +
                ", ".join(passed_hardware_states))
    else:
        require(isinstance(current_hardware, dict),
                "matching hardware performance requires a hardware_evidence object")
        require(verification.get("vivado_implementation") == "passed",
                "matching hardware performance requires passed Vivado implementation")
        require(verification.get("fpga_perf20") == "passed",
                "matching hardware performance requires passed FPGA perf20")
        current_hardware_values = validate_hardware_record(
            current_hardware, "current CPU hardware evidence")
        require(current_hardware_values["source_commit"] == current_commit,
                "current hardware evidence uses a different CPU source commit")
        require(current_hardware_values["published_rtl_sha256"] == current_rtl,
                "current hardware evidence uses a different published RTL")
        require_passing_hardware(current_hardware_values,
                                 "current CPU hardware evidence")
        validate_hardware_git_identity(current_hardware_values,
                                       "current CPU hardware evidence")

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
    require(current_commit != reference_commit,
            "current CPU and historical hardware reference must remain distinct")
    require(current_rtl != reference_rtl,
            "current and historical published RTL hashes must remain distinct")
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

    for field in (*hardware_state_fields, "linux_release"):
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

    if current_hardware_values is not None:
        current_hardware_documents = (
            (str(current_hardware_values["repository_commit"]),
             "current hardware repository commit"),
            (str(current_hardware_values["source_commit"]),
             "current hardware source commit"),
            (str(current_hardware_values["chiplab_commit"]),
             "current hardware Chiplab commit"),
            (str(current_hardware_values["published_rtl_sha256"]),
             "current hardware published RTL SHA256"),
            (str(current_hardware_values["package_sha256"]),
             "current hardware package SHA256"),
            (str(current_hardware_values["job_id"]),
             "current hardware job ID"),
        )
        for value, label in current_hardware_documents:
            require_in_documents(current_documents, f"`{value}`", label)
        for value, label in (
            (f"{float(current_hardware_values['frequency_mhz']):g} MHz",
             "current hardware frequency"),
            (f"`{float(current_hardware_values['setup_wns_ns']):+.3f} ns`",
             "current hardware setup WNS"),
            (f"`{float(current_hardware_values['hold_wns_ns']):+.3f} ns`",
             "current hardware hold WNS"),
            (f"{int(current_hardware_values['perf_passed'])}／"
             f"{int(current_hardware_values['perf_total'])} passed",
             "current hardware perf20 result"),
            (f"`{int(current_hardware_values['cpu_count']):,}`",
             "current hardware CPU cycles"),
            (f"`{int(current_hardware_values['soc_count']):,}`",
             "current hardware SoC cycles"),
            (f"{int(current_hardware_values['drc_errors'])} Error",
             "current hardware DRC result"),
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

    validate_optional_local_artifacts(current_cpu, verification)

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
