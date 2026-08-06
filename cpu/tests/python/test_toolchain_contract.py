from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]


class ToolchainContractTest(unittest.TestCase):
    def test_lock_has_required_versions_without_user_paths(self) -> None:
        values = {}
        for line in (ROOT / "cpu/reference/manifest.lock").read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
        for key in ("scala", "sbt", "spinalhdl", "scalatest", "verilator", "yosys"):
            self.assertIn(key, values)
        self.assertFalse(any(value.startswith("/home/") for value in values.values()))


if __name__ == "__main__":
    unittest.main()
