from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/experiment"))
from branch_trace_summary import summarize


class BranchTraceSummaryTest(unittest.TestCase):
    def test_groups_low_confidence_events_by_predictor_type(self) -> None:
        records = [
            {
                "kind": "header",
                "format": "miku-branch-trace-v1",
                "schema_version": 1,
                "pht_index_width": 12,
                "metadata_valid_bit": 14,
            },
            {
                "kind": "branch",
                "predictor_type": 0,
                "pht_index": 7,
                "pht_state": 1,
                "pht_valid": 1,
                "low_confidence_pht": True,
                "pc": "0x1c000010",
            },
            {
                "kind": "branch",
                "predictor_type": 1,
                "pht_index": 7,
                "pht_state": 3,
                "pht_valid": 1,
                "low_confidence_pht": False,
                "pc": "0x1c000014",
            },
            {
                "kind": "branch",
                "predictor_type": 4,
                "pht_index": 4095,
                "pht_state": 2,
                "pht_valid": 1,
                "low_confidence_pht": True,
                "pc": "0x1c000018",
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.jsonl"
            trace.write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            result = summarize(trace)

        self.assertEqual(result["branch_events"], 3)
        self.assertEqual(result["low_confidence_pht_events"], 2)
        self.assertAlmostEqual(result["low_confidence_pht_ratio"], 2 / 3)
        self.assertEqual(result["by_type"]["conditional"], 1)
        self.assertEqual(result["low_confidence_pht_by_type"]["call"], 1)
        self.assertEqual(result["unique_low_confidence_pht_indices"], 2)

    def test_rejects_missing_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.jsonl"
            trace.write_text(
                json.dumps({"kind": "branch"}) + "\n", encoding="utf-8"
            )
            with self.assertRaises(ValueError):
                summarize(trace)


if __name__ == "__main__":
    unittest.main()
