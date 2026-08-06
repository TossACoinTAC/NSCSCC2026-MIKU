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
        self.assertTrue((ROOT / "Post_Impl_Bundles").is_dir())
        self.assertTrue((ROOT / "Stable_Backup").is_dir())

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
        self.assertFalse((ROOT / "cpu/spinal").exists())
        self.assertFalse((ROOT / "nscscc-cpu").exists())
        self.assertFalse((ROOT / "tools").exists())


if __name__ == "__main__":
    unittest.main()
