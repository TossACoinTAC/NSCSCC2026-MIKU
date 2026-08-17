#!/usr/bin/env python3
"""Shared identity and result parsers for reproducible verification evidence."""

from __future__ import annotations

from collections import Counter
import hashlib
from pathlib import Path
import re
import subprocess
from typing import Any
import xml.etree.ElementTree as ET


TREE_HASH_ALGORITHM = "git-tracked-path-payload-sha256-v1"
PYTHON_CONTRACT_INPUT_SCOPE = (
    ".gitignore",
    ".gitmodules",
    "CONTRIBUTING.md",
    "Makefile",
    "README.md",
    "config",
    "cpu/reference",
    "cpu/tests",
    "docs",
    "scripts",
)
SCALA_INPUT_SCOPE = (
    "cpu/build.sbt",
    "cpu/project",
    "cpu/src/main",
    "cpu/src/test",
)
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
PYTHON_INPUT_PATTERN = re.compile(
    rf"^INPUT algorithm={TREE_HASH_ALGORITHM} files=(\d+) "
    rf"sha256=([0-9a-f]{{64}})$"
)
PYTHON_SUMMARY_PATTERN = re.compile(
    r"^SUMMARY total=(\d+) passed=(\d+) failures=(\d+) errors=(\d+) "
    r"skipped=(\d+) expected_failures=(\d+) unexpected_successes=(\d+)$"
)
PYTHON_RESULT_PATTERN = re.compile(r"^RESULT (PASS|FAIL)$")
PYTHON_ENTRY_PATTERN = re.compile(
    r"^(PASS|FAIL|ERROR|EXPECTED_FAILURE|UNEXPECTED_SUCCESS) (\S+)$"
)
PYTHON_SKIP_PATTERN = re.compile(r"^SKIP (\S+) \| (.+)$")
PYTHON_OUTCOME_FIELDS = {
    "PASS": "passed_count",
    "FAIL": "failure_count",
    "ERROR": "error_count",
    "SKIP": "skipped_count",
    "EXPECTED_FAILURE": "expected_failure_count",
    "UNEXPECTED_SUCCESS": "unexpected_success_count",
}


class EvidenceIdentityError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceIdentityError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_tracked_files(root: Path, scopes: tuple[str, ...]) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", *scopes],
        cwd=root,
        check=False,
        capture_output=True,
    )
    detail = result.stderr.decode(errors="replace").strip()
    require(
        result.returncode == 0,
        "unable to list tracked evidence inputs: "
        + (detail or f"git exited {result.returncode}"),
    )
    relatives = [
        item.decode("utf-8")
        for item in result.stdout.split(b"\0")
        if item
    ]
    require(bool(relatives), "evidence input scope contains no tracked files")
    paths = []
    for relative in sorted(set(relatives)):
        path = (root / relative).resolve()
        require(path.is_relative_to(root), f"tracked input escapes repository: {relative}")
        require(path.is_file(), f"tracked evidence input is missing: {relative}")
        paths.append(path)
    return paths


def tracked_content_tree_sha256(
    root: Path,
    scopes: tuple[str, ...],
) -> tuple[str, int]:
    root = root.resolve()
    files = git_tracked_files(root, scopes)
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(root).as_posix()
        payload = path.read_bytes()
        encoded = relative.encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest(), len(files)


def require_tracked_test_files(
    root: Path,
    start_directory: Path,
    pattern: str,
) -> None:
    root = root.resolve()
    directory = start_directory.resolve()
    require(directory.is_relative_to(root), "Python test directory escapes repository")
    tests = sorted(directory.rglob(pattern))
    require(bool(tests), "Python contract discovery found no test files")
    relative_tests = {path.relative_to(root).as_posix() for path in tests if path.is_file()}
    tracked_tests = {
        path.relative_to(root).as_posix()
        for path in git_tracked_files(root, (directory.relative_to(root).as_posix(),))
        if path.match(pattern)
    }
    require(
        relative_tests == tracked_tests,
        "Python contract discovery includes untracked or missing test files: "
        + ", ".join(sorted(relative_tests.symmetric_difference(tracked_tests))),
    )


def parse_python_contract_log(text: str) -> dict[str, Any]:
    require(text.endswith("\n"), "Python contract log must end with a newline")
    lines = text.splitlines()
    require(len(lines) >= 3, "Python contract log is incomplete")

    input_matches = [PYTHON_INPUT_PATTERN.fullmatch(line) for line in lines]
    input_matches = [match for match in input_matches if match is not None]
    require(len(input_matches) == 1 and PYTHON_INPUT_PATTERN.fullmatch(lines[0]),
            "Python contract log must start with exactly one input identity")
    input_match = input_matches[0]

    summary_matches = [PYTHON_SUMMARY_PATTERN.fullmatch(line) for line in lines]
    summary_matches = [match for match in summary_matches if match is not None]
    require(len(summary_matches) == 1 and
            PYTHON_SUMMARY_PATTERN.fullmatch(lines[-2]),
            "Python contract log must contain one summary immediately before RESULT")
    summary_match = summary_matches[0]

    result_matches = [PYTHON_RESULT_PATTERN.fullmatch(line) for line in lines]
    result_matches = [match for match in result_matches if match is not None]
    require(len(result_matches) == 1 and
            PYTHON_RESULT_PATTERN.fullmatch(lines[-1]),
            "Python contract log must end with exactly one RESULT")
    result_match = result_matches[0]

    entries = []
    for line in lines[1:-2]:
        match = PYTHON_ENTRY_PATTERN.fullmatch(line)
        if match is not None:
            entries.append((match.group(1), match.group(2), None))
            continue
        skip_match = PYTHON_SKIP_PATTERN.fullmatch(line)
        if skip_match is not None:
            entries.append(("SKIP", skip_match.group(1), skip_match.group(2)))
            continue
        require(result_match.group(1) == "FAIL",
                f"passing Python contract log contains an unsupported line: {line}")

    total, passed, failures, errors, skipped, expected, unexpected = (
        int(value) for value in summary_match.groups()
    )
    summary = {
        "test_count": total,
        "passed_count": passed,
        "failure_count": failures,
        "error_count": errors,
        "skipped_count": skipped,
        "expected_failure_count": expected,
        "unexpected_success_count": unexpected,
    }
    test_ids = [test_id for _, test_id, _ in entries]
    require(len(entries) == total, "Python result entry count does not match summary")
    require(len(set(test_ids)) == total,
            "Python contract log contains duplicate test IDs")
    actual = Counter(kind for kind, _, _ in entries)
    for kind, field in PYTHON_OUTCOME_FIELDS.items():
        require(actual[kind] == summary[field],
                f"Python {kind} entry count does not match summary")
    require(sum(summary[field] for field in PYTHON_OUTCOME_FIELDS.values()) == total,
            "Python contract summary does not conserve tests")
    expected_result = "PASS" if failures == errors == unexpected == 0 else "FAIL"
    require(result_match.group(1) == expected_result,
            "Python RESULT does not match the recorded outcomes")

    return {
        "input": {
            "hash_algorithm": TREE_HASH_ALGORITHM,
            "file_count": int(input_match.group(1)),
            "tree_sha256": input_match.group(2),
        },
        "summary": summary,
        "entries": entries,
        "result": result_match.group(1),
    }


def scala_report_snapshot(root: Path, report_directory: Path) -> dict[str, Any]:
    root = root.resolve()
    reports = sorted(report_directory.glob("TEST-*.xml"))
    require(bool(reports), f"no Scala XML reports found in {report_directory}")
    digest = hashlib.sha256()
    entries = []
    suite_names = set()
    totals = {"tests": 0, "errors": 0, "failures": 0, "skipped": 0}
    for report in reports:
        payload = report.read_bytes()
        relative = report.resolve().relative_to(root).as_posix()
        encoded = relative.encode("utf-8")
        digest.update(len(encoded).to_bytes(8, "big"))
        digest.update(encoded)
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)

        suite = ET.fromstring(payload)
        require(suite.tag == "testsuite", f"unexpected Scala XML root in {relative}")
        suite_name = suite.attrib.get("name", "")
        require(bool(suite_name), f"Scala report has no suite name: {relative}")
        require(suite_name not in suite_names, f"duplicate Scala suite: {suite_name}")
        require(report.name == f"TEST-{suite_name}.xml",
                f"Scala report filename does not match suite name: {relative}")
        suite_names.add(suite_name)

        testcases = suite.findall(".//testcase")
        actual_counts = {
            "tests": len(testcases),
            "errors": 0,
            "failures": 0,
            "skipped": 0,
        }
        for testcase in testcases:
            outcomes = [
                name for name in ("error", "failure", "skipped")
                if testcase.find(name) is not None
            ]
            require(len(outcomes) <= 1,
                    f"Scala testcase has multiple outcomes in {relative}")
            if outcomes:
                field = {"error": "errors", "failure": "failures", "skipped": "skipped"}[
                    outcomes[0]
                ]
                actual_counts[field] += 1

        declared_counts = {
            field: int(suite.attrib.get(field, "0"))
            for field in ("tests", "errors", "failures", "skipped")
        }
        for field, count in declared_counts.items():
            require(count >= 0, f"negative Scala {field} count in {relative}")
            require(count == actual_counts[field],
                    f"Scala {field} attribute does not match testcase outcomes: {relative}")
            totals[field] += count
        entries.append({
            "path": relative,
            "suite": suite_name,
            "sha256": hashlib.sha256(payload).hexdigest(),
            **declared_counts,
        })

    return {
        "suite_count": len(entries),
        "test_count": totals["tests"],
        "error_count": totals["errors"],
        "failure_count": totals["failures"],
        "skipped_count": totals["skipped"],
        "report_tree_sha256": digest.hexdigest(),
        "reports": entries,
    }
