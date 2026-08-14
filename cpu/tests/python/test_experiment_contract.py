from __future__ import annotations

import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from common import (
    ExperimentError,
    artifact_record,
    compare_perf_matrices,
    validate_experiment_manifest,
)
from test_impact import calculate_impact
from timing_analyze import classify, parse_timing_report, summarize

sys.path.insert(0, str(ROOT / "scripts/common"))
from content_hash import cpu_source_hash


BENCHMARKS = [f"bench_{index:02d}" for index in range(20)]


def write_matrix(root: Path, name: str, *, software_key: str = "b" * 64,
                 cycle_delta: int = 0, failing: bool = False) -> Path:
    matrix_root = root / name / "ideal"
    matrix_root.mkdir(parents=True)
    matrix = matrix_root / "matrix_perf20.csv"
    with matrix.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=("benchmark", "memory_mode", "seed", "cpu_cycles", "verdict"),
        )
        writer.writeheader()
        for index, benchmark in enumerate(BENCHMARKS):
            cycles = 1000 + index * 10 + cycle_delta
            writer.writerow({
                "benchmark": benchmark,
                "memory_mode": "ideal",
                "seed": 0,
                "cpu_cycles": cycles,
                "verdict": "fail" if failing and index == 0 else "pass",
            })
            lane = matrix_root / f"perf20__{benchmark}" / "seed_0" / "limit_1ns"
            lane.mkdir(parents=True)
            (lane / "perf20-result.json").write_text(
                json.dumps({"cpu_cycles": cycles}), encoding="utf-8"
            )
            (lane / "run-manifest.txt").write_text(
                "\n".join((
                    "format=nscscc-sim-run-v3",
                    f"cpu_commit={'c' * 40}",
                    f"chiplab_commit={'d' * 40}",
                    "profile=clean",
                    "suite=perf20",
                    "memory_mode=ideal",
                    f"workload=perf20/{benchmark}",
                    "axi_seed=0",
                    f"model_key={'a' * 64}",
                    f"software_key={software_key}",
                )) + "\n",
                encoding="utf-8",
            )
    return matrix


class ExperimentContractTest(unittest.TestCase):
    def test_perf20_exact_and_changed_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = write_matrix(root, "baseline")
            same = write_matrix(root, "same")
            faster = write_matrix(root, "faster", cycle_delta=-5)
            exact = compare_perf_matrices(baseline, same)
            self.assertTrue(exact["summary"]["exactly_equal"])
            self.assertEqual(exact["summary"]["baseline_total_cycles"], 21900)
            improved = compare_perf_matrices(baseline, faster)
            self.assertLess(improved["summary"]["candidate_total_cycles"], 21900)
            self.assertGreater(improved["summary"]["geometric_mean_speedup"], 1.0)

    def test_perf20_rejects_bad_fixture_and_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = write_matrix(root, "baseline")
            failing = write_matrix(root, "failing", failing=True)
            wrong_software = write_matrix(root, "wrong", software_key="e" * 64)
            with self.assertRaises(ExperimentError):
                compare_perf_matrices(baseline, failing)
            with self.assertRaises(ExperimentError):
                compare_perf_matrices(baseline, wrong_software)

    def test_manifest_rejects_tampered_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "result.csv"
            evidence.write_text("result\n", encoding="utf-8")
            document = {
                "schema_version": 1,
                "experiment_id": "R0-contract",
                "workspace": {},
                "cpu": {
                    "source_tree_sha256": "a" * 64,
                    "raw_rtl_sha256": "b" * 64,
                    "published_rtl_sha256": "c" * 64,
                    "generation_manifest_sha256": "d" * 64,
                },
                "platform": {"chiplab_commit": "e" * 40},
                "toolchain": {},
                "simulations": [],
                "evidence": [artifact_record(root, evidence)],
            }
            validate_experiment_manifest(document, root)
            evidence.write_text("tampered\n", encoding="utf-8")
            with self.assertRaises(ExperimentError):
                validate_experiment_manifest(document, root)

    def test_timing_classification(self) -> None:
        fixture = """Slack (VIOLATED) : -0.500ns
  Source: u_cpu/core/frontend/targetPredictor/btb_reg/Q
  Destination: u_cpu/core/frontend/request_reg/D
  Data Path Delay: 10.000ns (logic 4.000ns (40.000%) route 6.000ns (60.000%))
  Logic Levels: 12
Slack (MET) : 0.100ns
  Source: platform_reg/Q
  Destination: output_reg/D
  Data Path Delay: 8.000ns (logic 3.000ns (37.500%) route 5.000ns (62.500%))
  Logic Levels: 8
"""
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "timing.rpt"
            report.write_text(fixture, encoding="utf-8")
            result = summarize(parse_timing_report(report))
            self.assertEqual(result["groups"]["predictor"]["count"], 1)
            self.assertEqual(result["groups"]["platform"]["count"], 1)
            self.assertEqual(classify("u_cpu/core/issueQueues/q", "u_cpu/out"), "IQ")

    def test_impact_map_is_path_based(self) -> None:
        mapping = json.loads((ROOT / "cpu/tests/impact-rules.json").read_text(encoding="utf-8"))
        result = calculate_impact(
            ["cpu/src/main/scala/miku/backend/IssueQueue.scala"], mapping
        )
        self.assertIn("miku.backend.IssueQueueSpec", result["scala_suites"])
        self.assertNotIn("miku.backend.LoadStoreQueueSpec", result["scala_suites"])

    def test_cpu_source_hash_ignores_tests_but_tracks_generation_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cpu = Path(directory)
            (cpu / "src/main/scala").mkdir(parents=True)
            (cpu / "src/test/scala").mkdir(parents=True)
            (cpu / "project").mkdir()
            (cpu / "build.sbt").write_text("scalaVersion := \"2.13.14\"\n")
            source = cpu / "src/main/scala/Core.scala"
            test = cpu / "src/test/scala/CoreSpec.scala"
            source.write_text("object Core\n")
            test.write_text("object CoreSpec\n")
            (cpu / "project/build.properties").write_text("sbt.version=1.10.11\n")
            baseline = cpu_source_hash(cpu)
            test.write_text("object RenamedCoreSpec\n")
            self.assertEqual(cpu_source_hash(cpu), baseline)
            source.write_text("object ChangedCore\n")
            self.assertNotEqual(cpu_source_hash(cpu), baseline)


if __name__ == "__main__":
    unittest.main()
