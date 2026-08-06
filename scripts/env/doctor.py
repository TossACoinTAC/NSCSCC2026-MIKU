#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(*argv: str, cwd: Path | None = None) -> tuple[int, str]:
    result = subprocess.run(argv, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return result.returncode, result.stdout.strip()


def patch_hash(repo: Path) -> str:
    code, payload = run("git", "diff", "--binary", "--no-ext-diff", cwd=repo)
    if code:
        return "unavailable"
    return hashlib.sha256(payload.encode()).hexdigest()


def root_gitlink(root: Path, path: str) -> str | None:
    code, payload = run("git", "ls-files", "--stage", "--", path, cwd=root)
    if code or not payload:
        return None
    fields = payload.split()
    if len(fields) < 2 or fields[0] != "160000":
        return None
    return fields[1]


def main() -> int:
    parser = argparse.ArgumentParser(description="只读检查本机环境和嵌套仓库")
    parser.add_argument("--require", action="append", default=[], choices=("sim", "vivado", "linux", "board"))
    args = parser.parse_args()
    root = Path(os.environ.get("WORKSPACE_ROOT", Path(__file__).resolve().parents[2])).resolve()
    lock_path = root / "config/repositories.lock"
    required = set(args.require)
    failures = 0

    print(f"[信息] 工作区 {root}")
    for command in ("git", "docker", "make"):
        path = shutil.which(command)
        if path:
            print(f"[通过] {command}: {path}")
        else:
            print(f"[失败] 缺少命令: {command}")
            failures += 1

    if not (root / "cpu/build.sbt").is_file():
        print("[失败] cpu/build.sbt 不存在")
        failures += 1

    document = json.loads(lock_path.read_text(encoding="utf-8"))
    for item in document.get("repositories", []):
        path = root / item["path"]
        needed = bool(required.intersection(item.get("required_for", [])))
        gitlink = root_gitlink(root, item["path"])
        if item.get("management") != "git-submodule" or gitlink != item["commit"]:
            level = "失败" if needed else "警告"
            print(
                f"[{level}] {item['name']}: submodule gitlink="
                f"{gitlink[:12] if gitlink else 'missing'} lock={item['commit'][:12]}"
            )
            failures += int(needed)
            continue
        if not (path / ".git").exists():
            level = "失败" if needed else "警告"
            print(f"[{level}] {item['name']}: 未初始化，运行 git submodule update --init")
            failures += int(needed)
            continue
        _, head = run("git", "rev-parse", "HEAD", cwd=path)
        _, branch = run("git", "branch", "--show-current", cwd=path)
        _, status = run("git", "status", "--porcelain=v1", "--untracked-files=all", cwd=path)
        match = head == item["commit"]
        level = "通过" if match else ("失败" if needed else "警告")
        print(
            f"[{level}] {item['name']}: branch={branch or 'detached'} head={head[:12]} "
            f"dirty={'yes' if status else 'no'} patch={patch_hash(path)[:12]}"
        )
        failures += int(needed and not match)

    image = os.environ.get("DOCKER_IMAGE", "nscscc-dev:ubuntu24.04-v1")
    code, _ = run("docker", "image", "inspect", image)
    print(f"[{'通过' if code == 0 else '警告'}] Docker 镜像: {image}")

    vivado = Path(os.environ.get("VIVADO", "/opt/Xilinx/Vivado/2023.2/bin/vivado"))
    vivado_ok = vivado.is_file() and os.access(vivado, os.X_OK)
    print(f"[{'通过' if vivado_ok else ('失败' if 'vivado' in required else '警告')}] Vivado: {vivado}")
    failures += int("vivado" in required and not vivado_ok)

    surfer = Path(os.environ.get("SURFER", "/mnt/d/Surfer/surfer.exe"))
    print(f"[{'通过' if surfer.is_file() else '警告'}] Surfer: {surfer}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
