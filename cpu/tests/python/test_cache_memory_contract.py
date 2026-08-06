from __future__ import annotations

import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]


class CacheMemoryContractTest(unittest.TestCase):
    def test_negative_cases_are_retained(self) -> None:
        document = json.loads((ROOT / "cpu/reference/cache-memory.contract.json").read_text())
        self.assertEqual(document["schema_version"], 1)
        self.assertIn("backpressure", document["negative_cases"])
        self.assertIn("cancelled-translation", document["negative_cases"])
        self.assertIn("dirty-writeback-error", document["negative_cases"])


if __name__ == "__main__":
    unittest.main()
