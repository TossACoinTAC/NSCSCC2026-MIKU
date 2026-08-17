from __future__ import annotations

from pathlib import Path
import sys
import tempfile
from typing import Optional
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/common"))
import check_docs
import evidence_identity
import write_local_evidence


SOURCE_COMMIT = "a" * 40
RTL_SHA256 = "d" * 64
INPUT_SHA256 = "1" * 64


def input_line() -> str:
    return (
        f"INPUT algorithm={evidence_identity.TREE_HASH_ALGORITHM} "
        f"files=2 sha256={INPUT_SHA256}\n"
    )


def python_expected(*, total: int = 2, passed: int = 2, skipped: int = 0) -> dict[str, object]:
    return {
        "test_count": total,
        "passed_count": passed,
        "failure_count": 0,
        "error_count": 0,
        "skipped_count": skipped,
        "contract_input_hash_algorithm": evidence_identity.TREE_HASH_ALGORITHM,
        "contract_input_file_count": 2,
        "contract_input_tree_sha256": INPUT_SHA256,
    }


def current_cpu(
    *,
    vivado: str = "not_run",
    func: str = "not_run",
    perf20: str = "not_run",
    claim: str = "none",
    evidence: Optional[dict[str, object]] = None,
) -> tuple[dict[str, object], dict[str, object]]:
    verification = {
        "local": "passed",
        "vivado_implementation": vivado,
        "fpga_func": func,
        "fpga_perf20": perf20,
        "linux_release": "not_run_for_this_cpu_source",
    }
    cpu = {
        "hardware_evidence": evidence or {},
        "performance_claim": claim,
    }
    return cpu, verification


class EvidenceContractTest(unittest.TestCase):
    def validate(
        self,
        cpu: dict[str, object],
        verification: dict[str, object],
    ) -> dict[str, dict[str, object]]:
        return check_docs.validate_current_hardware_stages(
            cpu,
            verification,
            SOURCE_COMMIT,
            RTL_SHA256,
            "disabled",
        )

    def test_all_not_run_uses_an_empty_hardware_evidence_object(self) -> None:
        cpu, verification = current_cpu()
        self.assertEqual(self.validate(cpu, verification), {})
        cpu["hardware_evidence"] = {"vivado_implementation": {}}
        with self.assertRaises(ValueError):
            self.validate(cpu, verification)

    def test_current_hardware_stays_not_run_without_imported_raw_artifacts(self) -> None:
        for field in ("vivado", "func", "perf20"):
            with self.subTest(field=field):
                arguments = {field: "passed"}
                cpu, verification = current_cpu(**arguments)
                with self.assertRaises(ValueError):
                    self.validate(cpu, verification)

        cpu, verification = current_cpu(claim="matching_hardware")
        with self.assertRaises(ValueError):
            self.validate(cpu, verification)

    def test_python_contract_log_rejects_duplicate_test_ids(self) -> None:
        expected = python_expected()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.log"
            path.write_text(
                input_line()
                + "PASS suite.test_one\n"
                "PASS suite.test_two\n"
                "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0 "
                "expected_failures=0 unexpected_successes=0\n"
                "RESULT PASS\n",
                encoding="utf-8",
            )
            check_docs.validate_python_contract_log(path, expected)
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "suite.test_two", "suite.test_one"
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                check_docs.validate_python_contract_log(path, expected)

    def test_python_contract_log_rejects_outcome_summary_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "contract.log"
            path.write_text(
                input_line()
                + "ERROR suite.test_one\n"
                "PASS suite.test_two\n"
                "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0 "
                "expected_failures=0 unexpected_successes=0\n"
                "RESULT PASS\n",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                check_docs.validate_python_contract_log(path, python_expected())

    def test_local_evidence_writer_rejects_stale_python_input_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = root / "scripts/common/run_python_contracts.py"
            runner.parent.mkdir(parents=True)
            runner.write_text("pass\n", encoding="utf-8")
            log = root / "build/evidence/python-contract.log"
            log.parent.mkdir(parents=True)
            log.write_text(
                input_line()
                + "PASS suite.test_one\n"
                "SUMMARY total=1 passed=1 failures=0 errors=0 skipped=0 "
                "expected_failures=0 unexpected_successes=0\n"
                "RESULT PASS\n",
                encoding="utf-8",
            )
            with mock.patch.object(
                write_local_evidence,
                "tracked_content_tree_sha256",
                return_value=("2" * 64, 2),
            ):
                with self.assertRaises(ValueError):
                    write_local_evidence.summarize_python_log(root, log, log)

    def test_clean_clone_log_accepts_only_generated_rtl_skip(self) -> None:
        generated_rtl_test = check_docs.GENERATED_RTL_TEST_ID
        tracked_text = (
            input_line()
            + "PASS suite.test_one\n"
            f"PASS {generated_rtl_test}\n"
            "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0 "
            "expected_failures=0 unexpected_successes=0\n"
            "RESULT PASS\n"
        )
        clean_clone_text = tracked_text.replace(
            f"PASS {generated_rtl_test}",
            f"SKIP {generated_rtl_test} | {check_docs.GENERATED_RTL_SKIP_REASON}",
        ).replace(
            "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0",
            "SUMMARY total=2 passed=1 failures=0 errors=0 skipped=1",
        )
        expected = python_expected()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tracked = root / "evidence/current/python-contract.log"
            local = root / "build/evidence/python-contract.log"
            tracked.parent.mkdir(parents=True)
            local.parent.mkdir(parents=True)
            tracked.write_text(tracked_text, encoding="utf-8")
            local.write_text(clean_clone_text, encoding="utf-8")
            expected["log_sha256"] = check_docs.sha256_file(tracked)
            with mock.patch.object(check_docs, "ROOT", root):
                check_docs.validate_optional_python_contract_log(local, expected)

    def test_clean_clone_log_rejects_an_unrelated_skip(self) -> None:
        generated_rtl_test = check_docs.GENERATED_RTL_TEST_ID
        tracked_text = (
            input_line()
            + "PASS suite.test_one\n"
            f"PASS {generated_rtl_test}\n"
            "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0 "
            "expected_failures=0 unexpected_successes=0\n"
            "RESULT PASS\n"
        )
        clean_clone_text = tracked_text.replace(
            f"PASS {generated_rtl_test}",
            "SKIP suite.other | unrelated",
        ).replace(
            "SUMMARY total=2 passed=2 failures=0 errors=0 skipped=0",
            "SUMMARY total=2 passed=1 failures=0 errors=0 skipped=1",
        )
        expected = python_expected()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tracked = root / "evidence/current/python-contract.log"
            local = root / "build/evidence/python-contract.log"
            tracked.parent.mkdir(parents=True)
            local.parent.mkdir(parents=True)
            tracked.write_text(tracked_text, encoding="utf-8")
            local.write_text(clean_clone_text, encoding="utf-8")
            expected["log_sha256"] = check_docs.sha256_file(tracked)
            with mock.patch.object(check_docs, "ROOT", root):
                with self.assertRaises(ValueError):
                    check_docs.validate_optional_python_contract_log(local, expected)

    def test_clean_clone_preserves_existing_skips(self) -> None:
        generated_rtl_test = check_docs.GENERATED_RTL_TEST_ID
        tracked_text = (
            input_line()
            + "SKIP suite.optional | unavailable\n"
            f"PASS {generated_rtl_test}\n"
            "SUMMARY total=2 passed=1 failures=0 errors=0 skipped=1 "
            "expected_failures=0 unexpected_successes=0\n"
            "RESULT PASS\n"
        )
        clean_clone_text = tracked_text.replace(
            f"PASS {generated_rtl_test}",
            f"SKIP {generated_rtl_test} | {check_docs.GENERATED_RTL_SKIP_REASON}",
        ).replace(
            "SUMMARY total=2 passed=1 failures=0 errors=0 skipped=1",
            "SUMMARY total=2 passed=0 failures=0 errors=0 skipped=2",
        )
        expected = python_expected(passed=1, skipped=1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tracked = root / "evidence/current/python-contract.log"
            local = root / "build/evidence/python-contract.log"
            tracked.parent.mkdir(parents=True)
            local.parent.mkdir(parents=True)
            tracked.write_text(tracked_text, encoding="utf-8")
            local.write_text(clean_clone_text, encoding="utf-8")
            expected["log_sha256"] = check_docs.sha256_file(tracked)
            with mock.patch.object(check_docs, "ROOT", root):
                check_docs.validate_optional_python_contract_log(local, expected)

    def test_scala_report_rejects_declared_zero_failure_with_failure_node(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "cpu/target/test-reports"
            reports.mkdir(parents=True)
            report = reports / "TEST-suite.Example.xml"
            report.write_text(
                "<testsuite name=\"suite.Example\" tests=\"1\" errors=\"0\" "
                "failures=\"0\" skipped=\"0\">"
                "<testcase name=\"fails\"><failure>boom</failure></testcase>"
                "</testsuite>",
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                evidence_identity.scala_report_snapshot(root, reports)


if __name__ == "__main__":
    unittest.main()
