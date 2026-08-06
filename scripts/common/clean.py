#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import stat


SCOPES = {
    "build": (
        "build/cpu", "build/rtl", "build/gates", "build/sim", "build/chiplab",
        "build/vivado", "build/reports", "build/tmp", "build/pre-migration-stringsearch",
    ),
    "cpu": (
        "build/cpu", "build/rtl", "build/gates", "cpu/target", "cpu/project/target",
        "cpu/project/project/target",
        "cpu/tests/python/__pycache__", "scripts/cpu/__pycache__", "scripts/common/__pycache__",
        "scripts/env/__pycache__",
    ),
    "sim": ("build/sim", "build/pre-migration-stringsearch"),
    "vivado": ("build/chiplab", "build/vivado"),
    "ide": (".bsp", ".metals", ".scala-build", "cpu/.bsp", "cpu/.metals", "cpu/.scala-build"),
}


def remove(root: Path, relative: str) -> None:
    target = (root / relative).resolve(strict=False)
    if target == root or root not in target.parents:
        raise RuntimeError(f"拒绝清理工作区外路径: {target}")
    if target.is_symlink() or target.is_file():
        target.unlink()
    elif target.is_dir():
        def make_writable(function, path, _exc):
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
            function(path)
        shutil.rmtree(target, onerror=make_writable)
    print(f"clean: {relative}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("scope", choices=tuple(SCOPES))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    for relative in SCOPES[args.scope]:
        remove(root, relative)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
