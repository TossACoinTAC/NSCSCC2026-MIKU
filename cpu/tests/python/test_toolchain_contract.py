from __future__ import annotations

from pathlib import Path
import re
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

    def test_container_uses_utf8_for_unicode_workspace_paths(self) -> None:
        wrapper = (ROOT / "scripts/env/run-in-container").read_text()
        self.assertIn("-e LANG=C.UTF-8", wrapper)
        self.assertIn("-e LC_ALL=C.UTF-8", wrapper)

    def test_custom_profile_default_ignores_shell_environment(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertRegex(
            makefile,
            re.compile(r"^CUSTOM_PROFILE := example-complex-branch$", re.MULTILINE),
        )


if __name__ == "__main__":
    unittest.main()
