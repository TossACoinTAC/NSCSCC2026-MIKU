from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
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

    def test_func_entry_disables_difftest_and_prints_result_summary(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        matrix = (ROOT / "scripts/sim/matrix").read_text(encoding="utf-8")
        prepare = (ROOT / "scripts/sim/prepare").read_text(encoding="utf-8")
        self.assertIn("SIM_DIFFTEST=0", makefile)
        self.assertIn("--difftest 0", makefile)
        self.assertIn("SIM_FORCE_COLOR", makefile)
        self.assertIn("FUNC58 SIMULATION: PASS", matrix)
        self.assertIn("FORCE_COLOR", matrix)
        self.assertIn("NO_COLOR", matrix)
        self.assertIn("color_pass=$'\\033[1;32m'", matrix)
        self.assertIn("color_fail=$'\\033[1;31m'", matrix)
        self.assertIn("DIFFTEST_EN=\"$difftest_en\"", prepare)
        self.assertIn("disable-difftest.patch", prepare)

    def test_func_release_freezes_and_archives_with_default_manifest(self) -> None:
        result = subprocess.run(
            ["make", "-n", "func-release", "EXPERIMENT_ID=finals-func"],
            cwd=ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        manifest = ROOT / "build/reports/experiments/finals-func/experiment-manifest.json"
        self.assertIn(f'--out "{manifest}"', result.stdout)
        self.assertIn(f'--experiment-manifest "{manifest}"', result.stdout)
        self.assertNotIn("--evidence", result.stdout)

    def test_commit_endpoint_observer_accepts_every_lane_and_rejects_false_hits(self) -> None:
        source = r'''
#include <cassert>
#include <limits>
#include <string>
#include "commit_endpoint_observer.h"

int main() {
  miku_sim::configureCommitEndpoint(0x1c000100);
  miku_sim::observeCommit(0, false, 0x1c000100);
  miku_sim::observeCommit(1, true, 0x1c000104);
  assert(!miku_sim::commitEndpointReached());
  for (unsigned lane = 0; lane < 3; ++lane) {
    miku_sim::configureCommitEndpoint(0x1c000100);
    assert(!miku_sim::commitEndpointReached());
    assert(miku_sim::commitEndpointLane() == std::numeric_limits<unsigned>::max());
    miku_sim::observeCommit(lane, true, 0x1c000100);
    assert(miku_sim::commitEndpointReached());
    assert(miku_sim::commitEndpointLane() == lane);
  }
  miku_sim::configureCommitEndpoint(0);
  miku_sim::observeCommit(2, true, 0);
  assert(!miku_sim::commitEndpointReached());
  assert(!miku_sim::simulationCompletionReached(1, 0, 1, 1));
  assert(!miku_sim::simulationCompletionReached(1, 123, 0, 1));
  assert(miku_sim::simulationCompletionReached(1, 123, 1, 1));
  assert(std::string(miku_sim::completionSourceName()) == "perf20-pins");
  miku_sim::configureCommitEndpoint(0);
  assert(!miku_sim::simulationCompletionReached(2, 123, 1, 1));
  assert(miku_sim::simulationCompletionReached(2, 0x3a00003a, 1, 1));
  assert(std::string(miku_sim::completionSourceName()) == "func58-pins");
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "endpoint.cpp"
            executable = root / "endpoint"
            fixture.write_text(source, encoding="utf-8")
            compile_result = subprocess.run(
                ["g++", "-std=c++14", "-Wall", "-Wextra", "-Werror",
                 "-I", str(ROOT / "scripts/sim"), str(fixture), "-o", str(executable)],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout)
            run_result = subprocess.run(
                [str(executable)], check=False, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            )
            self.assertEqual(run_result.returncode, 0, run_result.stdout)

    def test_perf20_endpoint_uses_the_elf_symbol_without_lane_offset(self) -> None:
        matrix = (ROOT / "scripts/sim/matrix").read_text(encoding="utf-8")
        self.assertIn("end_pc_strategy=test_finish", matrix)
        self.assertNotIn("test_finish_loop_lane0_load", matrix)
        self.assertNotIn("test_finish + 24", matrix)
        self.assertIn("completion_args=(--completion-profile 1)", matrix)
        self.assertIn("completion_args=(--completion-profile 2)", matrix)


if __name__ == "__main__":
    unittest.main()
