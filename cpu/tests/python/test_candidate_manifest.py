from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, validate_candidate_manifest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/vivado"))
from archive import (
    ArchiveError,
    implementation_passes,
    parse_drc,
    parse_key_values,
    parse_utilization,
    select_archive_class,
)


class CandidateManifestTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
