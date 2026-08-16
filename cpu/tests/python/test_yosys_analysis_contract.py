from __future__ import annotations

import copy
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from yosys_analyze import (
    YosysAnalysisError,
    compare_reports,
    parse_ltp_text,
    summarize_stat,
)


def fixture_report(*, cells: int = 12, rtl_hash: str = "a" * 64) -> dict:
    return {
        "schema_version": 1,
        "kind": "yosys-structural-analysis",
        "input": {"label": "fixture", "rtl_sha256": rtl_hash},
        "tool": {"identity_sha256": "b" * 64},
        "analysis": {
            "config_sha256": "c" * 64,
            "hierarchy": {
                "total_cells": cells,
                "total_memory_bits": 64,
                "modules": {
                    "core_top": {"contribution_cells": 2},
                    "Worker": {"contribution_cells": cells - 2},
                },
            },
            "paths": {"core_top": {"length": 5}},
        },
        "artifacts": {},
    }


class YosysAnalysisContractTest(unittest.TestCase):
    def test_ltp_parser_keeps_length_and_endpoints(self) -> None:
        result = parse_ltp_text(
            """Longest topological path in ReorderBuffer (length=2):
    0: \\source [0]
    1: $logic_and$build/rtl/mycpu_top.v:10$20_Y (via $logic_and$build/rtl/mycpu_top.v:10$20)
    2: \\next [0]
   ff: \\destination [0] (via $procdff$99)
"""
        )
        self.assertEqual(result["module"], "ReorderBuffer")
        self.assertEqual(result["length"], 2)
        self.assertEqual(result["start"], "\\source [0]")
        self.assertEqual(result["endpoint"], "\\destination [0]")
        self.assertNotIn("mycpu_top.v:10", " ".join(result["head"] + result["tail"]))

    def test_hierarchy_weights_shared_module_instances(self) -> None:
        summary = summarize_stat({
            "modules": {
                "\\core_top": {
                    "num_cells": 4,
                    "num_memory_bits": 0,
                    "num_cells_by_type": {"$dff": 2, "Worker": 2},
                },
                "\\Worker": {
                    "num_cells": 5,
                    "num_memory_bits": 32,
                    "num_cells_by_type": {"$mux": 3, "$eq": 2},
                },
            },
            "design": {
                "num_cells": 12,
                "num_memory_bits": 64,
                "num_cells_by_type": {"$dff": 2, "$mux": 6, "$eq": 4},
            },
        })
        self.assertEqual(summary["total_cells"], 12)
        self.assertEqual(summary["modules"]["Worker"]["instances"], 2)
        self.assertEqual(summary["modules"]["Worker"]["contribution_cells"], 10)
        self.assertEqual(summary["modules"]["Worker"]["cell_buckets"]["mux"], 6)

    def test_comparison_reports_module_and_path_deltas(self) -> None:
        baseline = fixture_report()
        candidate = fixture_report(cells=17, rtl_hash="d" * 64)
        candidate["analysis"]["paths"]["core_top"]["length"] = 7
        result = compare_reports(baseline, candidate)
        self.assertEqual(result["summary"]["delta_cells"], 5)
        self.assertAlmostEqual(result["summary"]["delta_percent"], 100 * 5 / 12)
        self.assertEqual(result["module_deltas"][0]["module"], "Worker")
        self.assertEqual(result["path_deltas"][0]["delta_length"], 2)

    def test_comparison_rejects_tool_or_config_mismatch(self) -> None:
        baseline = fixture_report()
        wrong_tool = copy.deepcopy(baseline)
        wrong_tool["tool"]["identity_sha256"] = "e" * 64
        with self.assertRaises(YosysAnalysisError):
            compare_reports(baseline, wrong_tool)
        wrong_config = copy.deepcopy(baseline)
        wrong_config["analysis"]["config_sha256"] = "f" * 64
        with self.assertRaises(YosysAnalysisError):
            compare_reports(baseline, wrong_config)

    def test_comparison_rejects_malformed_report(self) -> None:
        malformed = fixture_report()
        del malformed["input"]["rtl_sha256"]
        with self.assertRaises(YosysAnalysisError):
            compare_reports(malformed, fixture_report())


if __name__ == "__main__":
    unittest.main()
