from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, validate_candidate_manifest


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


if __name__ == "__main__":
    unittest.main()
