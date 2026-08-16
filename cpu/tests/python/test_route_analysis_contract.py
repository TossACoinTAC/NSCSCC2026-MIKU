from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from route_analyze import RouteAnalysisError, duration_seconds, parse_route_log


class RouteAnalysisContractTest(unittest.TestCase):
    def test_completed_congested_route(self) -> None:
        fixture = """Phase 4.2 Global Iteration 1
 Number of Nodes with overlaps = 117377
 Number of Nodes with overlaps = 40843
INFO: [Route 35-416] Intermediate Timing Summary | WNS=-2.057 | TNS=-1505.914| WHS=N/A    | THS=N/A    |
Phase 4.2 Global Iteration 1 | Checksum: deadbeef
Time (s): cpu = 01:09:05 ; elapsed = 00:29:44 . Memory (MB): peak = 4600
WARNING: [Route 35-447] Congestion is preventing the router from routing all nets.
Phase 4.3 Global Iteration 2
 Number of Nodes with overlaps = 35097
 Number of Nodes with overlaps = 0
Phase 4.3 Global Iteration 2 | Checksum: feedface
Time (s): cpu = 01:22:33 ; elapsed = 00:35:51 . Memory (MB): peak = 4600
INFO: [Physopt 32-1132] Very high fanout net 'u_cpu/backend/rob/allocatePointer[3]' is not considered as a candidate in VHFN optimzation. The fanout considered for this optimization is changed from 259 to 134 due to a timing constraint that prevent optimization on all of the loads.
 Number of Nodes with overlaps = 0
South Dir 4x4 Area, Max Cong = 94.3131%, Congestion bounded by tiles:
West Dir 32x32 Area, Max Cong = 85.9704%, Congestion bounded by tiles:
INFO: [Route 35-20] Post Routing Timing Summary | WNS=-1.850 | TNS=-1140.230| WHS=0.050  | THS=0.000  |
  Number of Failed Nets               = 0
route_design: Time (s): cpu = 04:27:58 ; elapsed = 01:47:09 . Memory (MB): peak = 4629
wait_on_runs: Time (s): cpu = 00:07:12 ; elapsed = 02:06:19 . Memory (MB): peak = 2135
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runme.log"
            path.write_text(fixture, encoding="utf-8")
            result = parse_route_log(path)
        self.assertEqual(result["peak_overlaps"], 117377)
        self.assertEqual(result["final_overlaps"], 0)
        self.assertEqual(result["iterations_with_overlaps"], 2)
        self.assertEqual(result["route_design_seconds"], 6429)
        self.assertEqual(result["implementation_seconds"], 7579)
        self.assertEqual(result["congestion_warning_count"], 1)
        self.assertEqual(result["congestion"]["maximum_percent"], 94.3131)
        self.assertEqual(result["very_high_fanout"]["skipped_count"], 1)
        self.assertEqual(result["very_high_fanout"]["maximum_original_fanout"], 259)
        self.assertEqual(
            result["very_high_fanout"]["skipped"][0],
            {
                "net": "u_cpu/backend/rob/allocatePointer[3]",
                "original_fanout": 259,
                "timing_eligible_fanout": 134,
            },
        )
        self.assertEqual(result["post_route_timing"]["setup_wns_ns"], -1.85)
        self.assertEqual(result["post_route_timing"]["hold_wns_ns"], 0.05)
        self.assertEqual(result["final_failed_nets"], 0)
        self.assertEqual(result["global_iterations"][0]["elapsed_seconds"], 1784)

    def test_rejects_incomplete_route_log(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runme.log"
            path.write_text(
                "Phase 4.2 Global Iteration 1\n Number of Nodes with overlaps = 3\n",
                encoding="utf-8",
            )
            with self.assertRaises(RouteAnalysisError):
                parse_route_log(path)

    def test_rejects_malformed_duration(self) -> None:
        with self.assertRaises(RouteAnalysisError):
            duration_seconds("1:2:3")


if __name__ == "__main__":
    unittest.main()
