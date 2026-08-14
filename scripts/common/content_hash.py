#!/usr/bin/env python3
"""计算目录的路径敏感 SHA-256，忽略可再生输出。"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

IGNORED_PARTS = {"target", "__pycache__", ".bsp", ".metals", ".scala-build"}


def _hash_files(root: Path, files: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted(files):
        relative = path.relative_to(root).as_posix().encode()
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        payload = path.read_bytes()
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def tree_hash(root: Path) -> str:
    root = root.resolve()
    files = sorted(
        path for path in root.rglob("*")
        if path.is_file() and not any(part in IGNORED_PARTS for part in path.relative_to(root).parts)
    )
    return _hash_files(root, files)


def cpu_source_hash(root: Path) -> str:
    """Hash only inputs that can change SBT/SpinalHDL RTL elaboration."""
    root = root.resolve()
    required = (root / "build.sbt", root / "src/main")
    if not required[0].is_file() or not required[1].is_dir():
        raise ValueError(f"不是有效的 CPU SBT 源码根: {root}")
    files = [root / "build.sbt"]
    for subtree in (root / "src/main", root / "project"):
        if subtree.is_dir():
            files.extend(
                path for path in subtree.rglob("*")
                if path.is_file()
                and not any(part in IGNORED_PARTS for part in path.relative_to(root).parts)
            )
    return _hash_files(root, files)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("tree", "cpu-source"), default="tree")
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if not args.path.is_dir():
        parser.error(f"目录不存在: {args.path}")
    try:
        value = cpu_source_hash(args.path) if args.profile == "cpu-source" else tree_hash(args.path)
    except ValueError as error:
        parser.error(str(error))
    print(value)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
