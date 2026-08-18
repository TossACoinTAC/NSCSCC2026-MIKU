from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, validate_generation_manifest
from write_generation_manifest import normalize_custom_profile


class GenerationContractTest(unittest.TestCase):
    def test_manifest_schema(self) -> None:
        validate_generation_manifest({
            "schema_version": 1,
            "source_tree_sha256": "0" * 64,
            "raw_rtl_sha256": "1" * 64,
            "published_rtl_sha256": "2" * 64,
            "custom_profile": "disabled",
            "toolchain": {"sbt": "1.10.11"},
        })

    def test_missing_hash_is_rejected(self) -> None:
        with self.assertRaises(ContractError):
            validate_generation_manifest({"schema_version": 1, "toolchain": {}})

    def test_custom_profile_is_normalized_and_required(self) -> None:
        self.assertEqual(normalize_custom_profile(" disabled "), "disabled")
        self.assertEqual(normalize_custom_profile("OFF"), "disabled")
        self.assertEqual(normalize_custom_profile(" Final-2026 "), "final-2026")
        with self.assertRaises(ContractError):
            validate_generation_manifest({
                "schema_version": 1,
                "source_tree_sha256": "0" * 64,
                "raw_rtl_sha256": "1" * 64,
                "published_rtl_sha256": "2" * 64,
                "custom_profile": "off",
                "toolchain": {},
            })

if __name__ == "__main__":
    unittest.main()
