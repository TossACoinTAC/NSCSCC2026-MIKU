from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class RepositoryContractTest(unittest.TestCase):
    def test_runtime_repositories_are_explicit(self) -> None:
        document = json.loads((ROOT / "config/repositories.lock").read_text())
        names = {item["name"] for item in document["repositories"]}
        self.assertEqual(names, {"chiplab", "linux-kernel", "fpga-lab-agent"})
        self.assertNotIn("submission", names)
        for directory in ("Post_Impl_Bundles", "Stable_Backup"):
            path = ROOT / directory
            self.assertTrue(not path.exists() or path.is_dir())
            ignored = subprocess.run(
                ["git", "check-ignore", "--quiet", "--no-index", f"{directory}/evidence"],
                cwd=ROOT,
                check=False,
            )
            self.assertEqual(ignored.returncode, 0, f"{directory} must remain protected from root Git")

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

    def test_documentation_entry_points_and_candidate_ledger(self) -> None:
        result = subprocess.run(
            ["python3", "scripts/common/check_docs.py"],
            cwd=ROOT,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_doctor_reports_a_missing_docker_without_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable_dir = Path(directory)
            for command in ("git", "make"):
                source = shutil.which(command)
                self.assertIsNotNone(source)
                (executable_dir / command).symlink_to(source)
            environment = os.environ.copy()
            environment["PATH"] = str(executable_dir)
            environment["WORKSPACE_ROOT"] = str(ROOT)
            result = subprocess.run(
                [sys.executable, "scripts/env/doctor.py"],
                cwd=ROOT,
                env=environment,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("[失败] 缺少命令: docker", result.stdout)
        self.assertNotIn("Traceback", result.stdout)

    def test_doctor_rejects_a_cli_without_a_daemon(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable_dir = Path(directory)
            for command in ("git", "make"):
                source = shutil.which(command)
                self.assertIsNotNone(source)
                (executable_dir / command).symlink_to(source)
            docker = executable_dir / "docker"
            docker.write_text("#!/bin/sh\nprintf '%s\\n' 'daemon unavailable' >&2\nexit 1\n")
            docker.chmod(0o755)
            environment = os.environ.copy()
            environment["PATH"] = str(executable_dir)
            environment["WORKSPACE_ROOT"] = str(ROOT)
            result = subprocess.run(
                [sys.executable, "scripts/env/doctor.py"],
                cwd=ROOT,
                env=environment,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("[失败] Docker daemon 不可用: daemon unavailable", result.stdout)


if __name__ == "__main__":
    unittest.main()
