from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from branch_trace_matrix import summarize


class BranchTraceMatrixTest(unittest.TestCase):
    def test_matches_m01_roi_and_replays_weak_branch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ideal = Path(directory) / "ideal"
            run = ideal / "perf20__toy" / "seed_0" / "limit_10ns"
            run.mkdir(parents=True)
            records = [
                {
                    "kind": "header",
                    "format": "miku-branch-trace-v2",
                    "schema_version": 2,
                    "pht_index_width": 12,
                    "metadata_valid_bit": 14,
                },
                {
                    "kind": "marker",
                    "seq": 0,
                    "cycle": 10,
                    "lane": 0,
                    "pc": "0x1c000000",
                    "instruction": "0x00006000",
                },
                {
                    "kind": "branch",
                    "seq": 0,
                    "cycle": 15,
                    "lane": 0,
                    "rob_pointer": 1,
                    "pc": "0x1c000010",
                    "instruction": "0x00000000",
                    "predictor_type": 0,
                    "actual_taken": 0,
                    "actual_target": "0x1c000014",
                    "predictor_metadata": "0x1000",
                    "pht_index": 0,
                    "pht_state": 1,
                    "pht_valid": 1,
                    "low_confidence_pht": True,
                },
                {
                    "kind": "marker",
                    "seq": 1,
                    "cycle": 20,
                    "lane": 0,
                    "pc": "0x1c000000",
                    "instruction": "0x00006000",
                },
            ]
            (run / "branch-trace-v2.jsonl").write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            (run / "m01-counters.json").write_text(
                json.dumps(
                    {
                        "cycles": 9,
                        "branch": {"retired": 1},
                        "roi": {"mode": "outermost-counter-read-pair"},
                    }
                ),
                encoding="utf-8",
            )
            result = summarize(ideal)

        self.assertEqual(result["trace_count"], 1)
        row = result["workloads"][0]
        self.assertEqual(row["workload"], "toy")
        self.assertEqual(row["roi"]["cycles"], 9)
        self.assertEqual(row["branch_events"], 1)
        self.assertEqual(row["weak_conditional"], 1)
        self.assertEqual(row["local_replay"]["64"]["weak_correct"], 1)

    def test_rejects_cycle_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ideal = Path(directory) / "ideal"
            run = ideal / "perf20__toy" / "seed_0" / "limit_10ns"
            run.mkdir(parents=True)
            records = [
                {
                    "kind": "header",
                    "format": "miku-branch-trace-v2",
                    "schema_version": 2,
                    "pht_index_width": 12,
                    "metadata_valid_bit": 14,
                },
                {"kind": "marker", "cycle": 1},
                {"kind": "marker", "cycle": 3},
            ]
            (run / "branch-trace-v2.jsonl").write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            (run / "m01-counters.json").write_text(
                json.dumps({"cycles": 99, "branch": {"retired": 0}}),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                summarize(ideal)


if __name__ == "__main__":
    unittest.main()
