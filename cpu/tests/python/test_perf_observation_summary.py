from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from common import ExperimentError
from perf_observation_summary import summarize_matrix

from cpu.tests.python.test_perf_observation_contract import valid_document


def write_observation_matrix(root: Path) -> Path:
    matrix = root / "matrix_perf20.csv"
    with matrix.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=(
                "benchmark",
                "memory_mode",
                "seed",
                "cpu_cycles",
                "verdict",
                "result_path",
            ),
        )
        writer.writeheader()
        for index in range(20):
            benchmark = f"bench_{index:02d}"
            relative = f"perf20__{benchmark}/seed_0/limit_1ns"
            lane = root / relative
            lane.mkdir(parents=True)
            counters = valid_document()
            counters["cycles"] = 999
            counters["retired_instructions"] = 500
            counters["retire_width_histogram"] = [499, 500, 0, 0]
            counters["unused_commit_slots"] = 2497
            counters["observed_commit_instructions"] = 500
            counters["zero_retire_loss"] = {
                "recovery": 0,
                "rob_empty": 499,
                "rob_nonempty": 0,
            }
            counters["rob"]["occupancy_histogram"] = [499, 500] + [0] * 31
            counters["rob"]["occupancy_sum"] = 500
            counters["rob"]["occupancy_max"] = 1
            counters["frontend"]["occupancy_histogram"] = [499, 500] + [0] * 15
            counters["dispatch"]["fire_histogram"] = [499, 500, 0, 0, 0]
            counters["dispatch"]["valid_sum"] = 500
            counters["dispatch"]["fire_sum"] = 500
            counters["branch"]["commit_histogram"] = [999, 0, 0, 0]
            counters["branch"].update({
                "resolve_to_recovery_cycles": 0,
                "resolve_to_recovery_max": 0,
            })
            counters["frontend"].update({
                "decode_valid_sum": 500,
                "translation_outstanding_cycles": 100,
                "cache_outstanding_cycles": 200,
            })
            counters["lsq"].update({
                "load_full_cycles": 0,
                "store_full_cycles": 0,
            })
            path = lane / "m01-counters.json"
            path.write_text(json.dumps(counters), encoding="utf-8")
            counter_hash = hashlib.sha256(path.read_bytes()).hexdigest()
            (lane / "perf20-result.json").write_text(
                json.dumps({"cpu_cycles": 1000}), encoding="utf-8"
            )
            (lane / "run-manifest.txt").write_text(
                "\n".join((
                    "format=nscscc-sim-run-v3",
                    f"cpu_commit={'a' * 40}",
                    f"chiplab_commit={'b' * 40}",
                    "profile=instrumented",
                    "suite=perf20",
                    "memory_mode=ideal",
                    f"model_sha256={'c' * 64}",
                    f"model_key={'d' * 64}",
                    f"software_key={'e' * 64}",
                    f"m01_counters_sha256={counter_hash}",
                )) + "\n",
                encoding="utf-8",
            )
            writer.writerow({
                "benchmark": benchmark,
                "memory_mode": "ideal",
                "seed": 0,
                "cpu_cycles": 1000,
                "verdict": "pass",
                "result_path": relative,
            })
    return matrix


class PerfObservationSummaryTest(unittest.TestCase):
    def test_summarizes_identity_bound_perf20_roi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix = write_observation_matrix(Path(directory))
            result = summarize_matrix(matrix)
            raw = result["summary"]["raw"]
            self.assertEqual(raw["score_cycles"], 20000)
            self.assertEqual(raw["roi_cycles"], 19980)
            self.assertEqual(raw["retired_instructions"], 10000)
            self.assertEqual(len(result["workloads"]), 20)

    def test_rejects_tampered_counter_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix = write_observation_matrix(root)
            counter = next(root.glob("perf20__*/seed_0/limit_1ns/m01-counters.json"))
            counter.write_text("{}\n", encoding="utf-8")
            with self.assertRaises(ExperimentError):
                summarize_matrix(matrix)


if __name__ == "__main__":
    unittest.main()
