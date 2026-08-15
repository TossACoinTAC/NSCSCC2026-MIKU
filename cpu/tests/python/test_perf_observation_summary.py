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
sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import ExperimentError
from perf_observation_summary import summarize_matrix
from perf_observation_summary import PERF20_BENCHMARKS

from test_perf_observation_contract import valid_document


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
        for benchmark in PERF20_BENCHMARKS:
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
            counters["rob"]["zero_retire_head_reason"] = {
                "invalid": 0,
                "payload_not_ready": 0,
                "incomplete": 0,
                "predictor_backpressure": 0,
                "ready_without_retire": 0,
            }
            counters["rob"]["incomplete_head_class"] = {
                "load": 0,
                "store": 0,
                "branch": 0,
                "system": 0,
                "other": 0,
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
            self.assertEqual(result["source_schema"], "miku-perf-observation-v8")
            self.assertEqual(raw["load_queue_capacity"], 8)
            self.assertIn("cached_store_request_fire", raw["lsq_events"])
            self.assertIn("load_queue_full", raw["lsq_events"])
            self.assertIn(
                "oldest_load_local_alias_blocked_with_alternate",
                raw["lsq_events"],
            )
            self.assertNotIn("accepted_store_valid", raw["lsq_events"])
            self.assertEqual(
                raw["rob_zero_retire_head_reason"]["incomplete"], 0
            )
            self.assertEqual(len(result["workloads"]), 20)

    def test_summarizes_existing_v6_matrix_without_v7_events(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix = write_observation_matrix(Path(directory))
            for path in Path(directory).glob("perf20__*/seed_0/limit_1ns/m01-counters.json"):
                counters = json.loads(path.read_text(encoding="utf-8"))
                counters["schema_version"] = "miku-perf-observation-v6"
                counters["lsq"]["events"] = counters["lsq"]["events"][:46]
                path.write_text(json.dumps(counters), encoding="utf-8")
                manifest = path.with_name("run-manifest.txt")
                lines = manifest.read_text(encoding="utf-8").splitlines()
                counter_hash = hashlib.sha256(path.read_bytes()).hexdigest()
                manifest.write_text(
                    "\n".join(
                        f"m01_counters_sha256={counter_hash}"
                        if line.startswith("m01_counters_sha256=")
                        else line
                        for line in lines
                    )
                    + "\n",
                    encoding="utf-8",
                )
            result = summarize_matrix(matrix)
            self.assertEqual(result["source_schema"], "miku-perf-observation-v6")
            self.assertIn("cached_store_request_fire", result["summary"]["raw"]["lsq_events"])
            self.assertNotIn("load_queue_full", result["summary"]["raw"]["lsq_events"])

    def test_summarizes_existing_v7_matrix_without_capacity_field(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix = write_observation_matrix(Path(directory))
            for path in Path(directory).glob("perf20__*/seed_0/limit_1ns/m01-counters.json"):
                counters = json.loads(path.read_text(encoding="utf-8"))
                counters["schema_version"] = "miku-perf-observation-v7"
                counters["lsq"].pop("load_capacity")
                path.write_text(json.dumps(counters), encoding="utf-8")
                manifest = path.with_name("run-manifest.txt")
                lines = manifest.read_text(encoding="utf-8").splitlines()
                counter_hash = hashlib.sha256(path.read_bytes()).hexdigest()
                manifest.write_text(
                    "\n".join(
                        f"m01_counters_sha256={counter_hash}"
                        if line.startswith("m01_counters_sha256=")
                        else line
                        for line in lines
                    )
                    + "\n",
                    encoding="utf-8",
                )
            result = summarize_matrix(matrix)
            self.assertEqual(result["source_schema"], "miku-perf-observation-v7")
            self.assertEqual(result["summary"]["raw"]["load_queue_capacity"], 8)

    def test_rejects_tampered_counter_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix = write_observation_matrix(root)
            counter = next(root.glob("perf20__*/seed_0/limit_1ns/m01-counters.json"))
            counter.write_text("{}\n", encoding="utf-8")
            with self.assertRaises(ExperimentError):
                summarize_matrix(matrix)

    def test_rejects_incomplete_perf20_matrix(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix = write_observation_matrix(root)
            rows = matrix.read_text(encoding="utf-8").splitlines()
            matrix.write_text("\n".join(rows[:-1]) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ExperimentError, "完整"):
                summarize_matrix(matrix)


if __name__ == "__main__":
    unittest.main()
