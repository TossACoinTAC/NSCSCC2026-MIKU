from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, classify_failure, validate_sim_result


class SimulationContractTest(unittest.TestCase):
    def test_result(self) -> None:
        validate_sim_result({
            "schema_version": 2, "status": "pass", "workload": "perf20/coremark",
            "seed": 1, "cycles": 123, "model_sha256": "a" * 64,
            "model_key": "b" * 64, "software_key": "c" * 64,
            "end_reason": "test_finish",
        })

    def test_result_negative(self) -> None:
        with self.assertRaises(ContractError):
            validate_sim_result({"schema_version": 1, "status": "pass"})

    def test_failure_categories(self) -> None:
        self.assertEqual(classify_failure("missing model artifact"), "config")
        self.assertEqual(classify_failure("malformed manifest JSON"), "artifact")
        self.assertEqual(classify_failure("DUT mismatch at commit"), "dut")

    def test_cache_identity_is_required(self) -> None:
        with self.assertRaises(ContractError):
            validate_sim_result({
                "schema_version": 2, "status": "pass", "workload": "func58",
                "seed": 1, "cycles": 123, "model_sha256": "a" * 64,
                "model_key": "short", "software_key": "c" * 64,
                "end_reason": "test_finish",
            })

    def test_linux_entry_uses_the_fixed_contract_window(self) -> None:
        result = subprocess.run(
            ["make", "-n", "linux-sim"],
            cwd=ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('TIME_LIMIT="50000000"', result.stdout)
        self.assertIn("SIM_LANES=1", result.stdout)

    def test_linux_window_has_a_structured_end_reason(self) -> None:
        matrix = (ROOT / "scripts/sim/matrix").read_text(encoding="utf-8")
        self.assertIn("verdict=linux-time-window-complete", matrix)
        self.assertIn("verdict=linux-window-did-not-reach-time-limit", matrix)


if __name__ == "__main__":
    unittest.main()
