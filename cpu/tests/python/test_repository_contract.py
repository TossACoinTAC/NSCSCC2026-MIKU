from __future__ import annotations

import json
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


class RepositoryContractTest(unittest.TestCase):
    def test_runtime_repositories_are_explicit(self) -> None:
        document = json.loads((ROOT / "config/repositories.lock").read_text())
        names = {item["name"] for item in document["repositories"]}
        self.assertEqual(names, {"chiplab", "linux-kernel", "fpga-lab-agent"})
        self.assertNotIn("submission", names)
        for name in ("Post_Impl_Bundles", "Stable_Backup"):
            path = ROOT / name
            if path.exists():
                self.assertTrue(path.is_dir())
            ignored = subprocess.run(
                ["git", "check-ignore", "-q", "--no-index", f"{name}/.contract-probe"],
                cwd=ROOT,
                check=False,
            )
            self.assertEqual(ignored.returncode, 0, f"{name} must remain ignored")

    def test_runtime_repositories_are_submodules(self) -> None:
        gitmodules = (ROOT / ".gitmodules").read_text(encoding="utf-8")
        document = json.loads((ROOT / "config/repositories.lock").read_text())
        for item in document["repositories"]:
            self.assertEqual(item["management"], "git-submodule")
            self.assertIn(f"path = {item['path']}", gitmodules)
            mode = subprocess.run(
                ["git", "ls-files", "--stage", "--", item["path"]],
                cwd=ROOT,
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            ).stdout.split(maxsplit=1)[0]
            self.assertEqual(mode, "160000")

    def test_cpu_source_boundary(self) -> None:
        self.assertTrue((ROOT / "cpu/build.sbt").is_file())
        self.assertTrue((ROOT / "cpu/src/main/scala").is_dir())
        self.assertTrue((ROOT / "cpu/project/build.properties").is_file())
        self.assertFalse((ROOT / "cpu/project/plugins.sbt").exists())
        self.assertFalse((ROOT / "cpu/project/project").exists())
        self.assertFalse((ROOT / "build/cpu").exists())
        self.assertFalse((ROOT / "cpu/spinal").exists())
        self.assertFalse((ROOT / "nscscc-cpu").exists())
        self.assertFalse((ROOT / "tools").exists())

        build_definition = (ROOT / "cpu/build.sbt").read_text(encoding="utf-8")
        container_runner = (ROOT / "scripts/env/run-in-container").read_text(encoding="utf-8")
        self.assertNotIn("CPU_SBT_TARGET", build_definition)
        self.assertNotIn("CPU_SBT_TARGET", container_runner)
        self.assertIn("cpu/target/spinal-sim", container_runner)

        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertNotIn("cpu-check: cpu-test-all", makefile)
        self.assertIn("cpu-check:\n\t@$(MAKE) cpu-test-all", makefile)

    def test_documentation_entry_points_and_candidate_ledger(self) -> None:
        result = subprocess.run(
            ["python3", "scripts/common/check_docs.py", "--structure-only"],
            cwd=ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
