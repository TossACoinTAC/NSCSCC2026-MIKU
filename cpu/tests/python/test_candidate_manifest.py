from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, validate_candidate_manifest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/vivado"))
from archive import (
    ArchiveError,
    expand_evidence_files,
    implementation_passes,
    load_experiment_evidence,
    parse_drc,
    parse_key_values,
    parse_utilization,
    select_archive_class,
)


class CandidateManifestTest(unittest.TestCase):
    def test_archive_expands_explicit_perf20_matrix_for_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            matrix_root = root / "build/sim/run/ideal"
            matrix_root.mkdir(parents=True)
            matrix = matrix_root / "matrix_fixture_perf20.csv"
            rows = ["benchmark,memory_mode,seed,cpu_cycles,verdict,result_path"]
            expected = {matrix.resolve()}
            for index in range(20):
                benchmark = f"bench_{index:02d}"
                lane_relative = f"perf20__{benchmark}/seed_0/limit_1ns"
                lane = matrix_root / lane_relative
                lane.mkdir(parents=True)
                manifest = lane / "run-manifest.txt"
                result = lane / "perf20-result.json"
                manifest.write_text(
                    "\n".join((
                        f"cpu_commit={'a' * 40}",
                        f"chiplab_commit={'b' * 40}",
                        "profile=clean",
                        "suite=perf20",
                        "memory_mode=ideal",
                        f"model_key={'c' * 64}",
                        f"software_key={'d' * 64}",
                    )) + "\n",
                    encoding="utf-8",
                )
                result.write_text(
                    json.dumps({"cpu_cycles": 1000 + index}), encoding="utf-8"
                )
                rows.append(
                    f"{benchmark},ideal,0,{1000 + index},pass,{lane_relative}"
                )
                expected.update((manifest.resolve(), result.resolve()))
            matrix.write_text("\n".join(rows) + "\n", encoding="utf-8")

            self.assertEqual(set(expand_evidence_files(root, [matrix])), expected)
            manifest.unlink()
            with self.assertRaises(ValueError):
                expand_evidence_files(root, [matrix])

    def test_hash_chain(self) -> None:
        validate_candidate_manifest({
            "schema_version": 1, "cpu_source_commit": "workspace:abc",
            "rtl_sha256": "a" * 64, "software_sha256": "b" * 64,
            "clock": {"requested_mhz": 100}, "results": [],
        })

    def test_hash_chain_negative(self) -> None:
        with self.assertRaises(ContractError):
            validate_candidate_manifest({"schema_version": 1, "results": []})

    def test_implementation_archive_classification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clock_path = root / "clock.txt"
            drc_path = root / "drc.rpt"
            bitstream = root / "soc_top.bit"
            clock_path.write_text(
                "setup_wns_ns=0.010\nhold_wns_ns=0.020\n"
                "actual_cpu_mhz=100.000000\nactual_sys_mhz=100.000000\n"
                "actual_ddr_mhz=200.000000\n"
            )
            drc_path.write_text(
                "Design State : Fully Routed\nViolations found: 1\n"
                "| TEST-1 | Warning | expected warning | 1 |\n"
            )
            bitstream.write_bytes(b"bitstream")
            clock = parse_key_values(clock_path)
            drc = parse_drc(drc_path)
            self.assertTrue(implementation_passes(clock, drc, bitstream, 100.0))

            wrong_clock = dict(clock)
            wrong_clock["actual_cpu_mhz"] = "90.000000"
            self.assertFalse(implementation_passes(wrong_clock, drc, bitstream, 100.0))

            clock["setup_wns_ns"] = "-0.001"
            self.assertFalse(implementation_passes(clock, drc, bitstream))

            drc_path.write_text(
                "Design State : Fully Routed\nViolations found: 2\n"
                "| TEST-1 | Error | rejected error | 2 |\n"
            )
            self.assertFalse(
                implementation_passes(parse_key_values(clock_path), parse_drc(drc_path), bitstream)
            )

            drc_path.write_text(
                "Design State : Fully Routed\nViolations found: 1\n"
                "| TEST-2 | Critical Warning | rejected critical warning | 1 |\n"
            )
            self.assertFalse(
                implementation_passes(parse_key_values(clock_path), parse_drc(drc_path), bitstream)
            )

    def test_utilization_parser(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "utilization.rpt"
            report.write_text(
                "| Slice LUTs | 89320 | 0 | 800 | 133800 | 66.76 |\n"
                "| Slice Registers | 54073 | 2 | 0 | 269200 | 20.09 |\n"
                "| Slice | 27218 | 0 | 200 | 33450 | 81.37 |\n"
                "| Block RAM Tile | 56.5 | 0 | 0 | 365 | 15.48 |\n"
                "| DSPs | 8 | 0 | 0 | 740 | 1.08 |\n"
            )
            self.assertEqual(parse_utilization(report)["bram_tiles"], 56.5)

    def test_zero_wns_is_not_a_stable_milestone(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bitstream = Path(directory) / "soc_top.bit"
            bitstream.write_bytes(b"bitstream")
            clock = {
                "setup_wns_ns": "0.000",
                "hold_wns_ns": "0.020",
                "actual_cpu_mhz": "100.000000",
                "actual_sys_mhz": "100.000000",
                "actual_ddr_mhz": "200.000000",
            }
            drc = {"errors": 0, "critical_warnings": 0, "fully_routed": True}
            self.assertFalse(implementation_passes(clock, drc, bitstream, 100.0))
            clock["setup_wns_ns"] = "0.010"
            clock["hold_wns_ns"] = "0.000"
            self.assertFalse(implementation_passes(clock, drc, bitstream, 100.0))

    def test_postroute_is_exploration_only(self) -> None:
        self.assertEqual(select_archive_class("postroute", "auto", True), "candidate")
        self.assertEqual(select_archive_class("postroute", "candidate", False), "candidate")
        with self.assertRaises(ArchiveError):
            select_archive_class("postroute", "stable", True)

    def test_only_passing_full_build_can_be_stable(self) -> None:
        self.assertEqual(select_archive_class("full", "auto", True), "stable")
        self.assertEqual(select_archive_class("full", "auto", False), "candidate")
        with self.assertRaises(ArchiveError):
            select_archive_class("full", "stable", False)

    def test_archive_only_accepts_explicit_matching_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build/rtl").mkdir(parents=True)
            generation_path = root / "build/rtl/generation-manifest.json"
            generation = {
                "source_tree_sha256": "a" * 64,
                "raw_rtl_sha256": "b" * 64,
                "published_rtl_sha256": "c" * 64,
            }
            import hashlib
            import json
            generation_path.write_text(json.dumps(generation))
            evidence = root / "result.csv"
            evidence.write_text("result\n")
            experiment = {
                "schema_version": 1,
                "experiment_id": "archive-contract",
                "workspace": {},
                "cpu": {
                    **generation,
                    "generation_manifest_sha256": hashlib.sha256(
                        generation_path.read_bytes()
                    ).hexdigest(),
                },
                "platform": {"chiplab_commit": "d" * 40},
                "toolchain": {},
                "simulations": [],
                "evidence": [{
                    "path": "result.csv",
                    "sha256": hashlib.sha256(evidence.read_bytes()).hexdigest(),
                    "bytes": evidence.stat().st_size,
                }],
            }
            experiment_path = root / "experiment.json"
            experiment_path.write_text(json.dumps(experiment))
            loaded, paths = load_experiment_evidence(
                root, experiment_path, generation, "d" * 40
            )
            self.assertEqual(loaded["experiment_id"], "archive-contract")
            self.assertEqual(paths, [evidence])
            evidence.write_text("tampered\n")
            with self.assertRaises(ValueError):
                load_experiment_evidence(root, experiment_path, generation, "d" * 40)


if __name__ == "__main__":
    unittest.main()
