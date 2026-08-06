from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, classify_failure, validate_sim_result


class SimulationContractTest(unittest.TestCase):
    def test_result(self) -> None:
        validate_sim_result({
            "schema_version": 1, "status": "pass", "workload": "perf20/coremark",
            "seed": 1, "cycles": 123, "model_sha256": "a" * 64, "end_reason": "test_finish",
        })

    def test_result_negative(self) -> None:
        with self.assertRaises(ContractError):
            validate_sim_result({"schema_version": 1, "status": "pass"})

    def test_failure_categories(self) -> None:
        self.assertEqual(classify_failure("missing model artifact"), "config")
        self.assertEqual(classify_failure("malformed manifest JSON"), "artifact")
        self.assertEqual(classify_failure("DUT mismatch at commit"), "dut")


if __name__ == "__main__":
    unittest.main()
