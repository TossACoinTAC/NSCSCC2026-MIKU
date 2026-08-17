#!/usr/bin/env python3
"""Run the Python contract suite and retain its complete unittest report."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evidence_identity import (
    PYTHON_CONTRACT_INPUT_SCOPE,
    TREE_HASH_ALGORITHM,
    require_tracked_test_files,
    tracked_content_tree_sha256,
)


class ContractResult(unittest.TestResult):
    def __init__(self) -> None:
        super().__init__()
        self.entries: list[str] = []

    def addSuccess(self, test: unittest.case.TestCase) -> None:
        super().addSuccess(test)
        self.entries.append(f"PASS {test.id()}")

    def addSkip(self, test: unittest.case.TestCase, reason: str) -> None:
        super().addSkip(test, reason)
        self.entries.append(f"SKIP {test.id()} | {reason}")

    def addFailure(self, test: unittest.case.TestCase, err: tuple[object, ...]) -> None:
        super().addFailure(test, err)
        self.entries.append(f"FAIL {test.id()}")

    def addError(self, test: unittest.case.TestCase, err: tuple[object, ...]) -> None:
        super().addError(test, err)
        self.entries.append(f"ERROR {test.id()}")

    def addExpectedFailure(
        self,
        test: unittest.case.TestCase,
        err: tuple[object, ...],
    ) -> None:
        super().addExpectedFailure(test, err)
        self.entries.append(f"EXPECTED_FAILURE {test.id()}")

    def addUnexpectedSuccess(self, test: unittest.case.TestCase) -> None:
        super().addUnexpectedSuccess(test)
        self.entries.append(f"UNEXPECTED_SUCCESS {test.id()}")


def render_report(
    result: ContractResult,
    input_tree_sha256: str,
    input_file_count: int,
) -> str:
    total = result.testsRun
    failures = len(result.failures)
    errors = len(result.errors)
    skipped = len(result.skipped)
    expected_failures = len(result.expectedFailures)
    unexpected_successes = len(result.unexpectedSuccesses)
    passed = total - failures - errors - skipped - expected_failures - unexpected_successes
    summary = (
        f"SUMMARY total={total} passed={passed} failures={failures} "
        f"errors={errors} skipped={skipped} "
        f"expected_failures={expected_failures} "
        f"unexpected_successes={unexpected_successes}"
    )
    outcome = "PASS" if result.wasSuccessful() else "FAIL"
    details = []
    for label, records in (("FAIL", result.failures), ("ERROR", result.errors)):
        for test, traceback in records:
            details.extend((f"DETAIL {label} {test.id()}", traceback.rstrip()))
    input_identity = (
        f"INPUT algorithm={TREE_HASH_ALGORITHM} files={input_file_count} "
        f"sha256={input_tree_sha256}"
    )
    return "\n".join(
        (input_identity, *result.entries, *details, summary, f"RESULT {outcome}")
    ) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--start-directory", type=Path, required=True)
    parser.add_argument("--pattern", default="test_*.py")
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()

    root = args.root.resolve()
    start_directory = args.start_directory.resolve()
    require_tracked_test_files(root, start_directory, args.pattern)
    input_tree_sha256, input_file_count = tracked_content_tree_sha256(
        root, PYTHON_CONTRACT_INPUT_SCOPE
    )
    suite = unittest.defaultTestLoader.discover(
        str(start_directory),
        pattern=args.pattern,
    )
    result = ContractResult()
    suite.run(result)
    output = render_report(result, input_tree_sha256, input_file_count)

    args.log.parent.mkdir(parents=True, exist_ok=True)
    args.log.write_text(output, encoding="utf-8")
    sys.stdout.write(output)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
