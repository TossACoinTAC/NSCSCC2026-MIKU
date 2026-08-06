#!/usr/bin/env python3
"""计算目录的路径敏感 SHA-256，忽略可再生输出。"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

IGNORED_PARTS = {"target", "__pycache__", ".bsp", ".metals", ".scala-build"}


def tree_hash(root: Path) -> str:
    root = root.resolve()
    digest = hashlib.sha256()
    files = sorted(
        path for path in root.rglob("*")
        if path.is_file() and not any(part in IGNORED_PARTS for part in path.relative_to(root).parts)
    )
    for path in files:
        relative = path.relative_to(root).as_posix().encode()
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        payload = path.read_bytes()
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if not args.path.is_dir():
        parser.error(f"目录不存在: {args.path}")
    print(tree_hash(args.path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
