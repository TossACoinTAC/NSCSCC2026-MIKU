#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from content_hash import cpu_source_hash


def file_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def probe(command: list[str]) -> str:
    try:
        return subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False).stdout.splitlines()[0]
    except (OSError, IndexError):
        return "unavailable"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--published", type=Path, required=True)
    parser.add_argument(
        "--core-variant",
        choices=(
            "default",
            "expanded-rob",
            "expanded-stores",
            "expanded-window",
        ),
        default="default",
    )
    parser.add_argument(
        "--gshare-history-bits",
        choices=(8, 10, 12, 14, 16),
        type=int,
        default=16,
    )
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    cpu = root / "cpu"
    try:
        source_commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True, stdout=subprocess.PIPE, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        source_commit = "workspace-uncommitted"
    document = {
        "schema_version": 1,
        "source_tree": "cpu/src/main + cpu/build.sbt + cpu/project",
        "source_tree_sha256": cpu_source_hash(cpu),
        "source_commit": source_commit,
        "core_variant": args.core_variant,
        "gshare_history_bits": args.gshare_history_bits,
        "raw_rtl": str(args.raw.resolve()),
        "raw_rtl_sha256": file_hash(args.raw),
        "published_rtl": str(args.published.resolve()),
        "published_rtl_sha256": file_hash(args.published),
        "toolchain": {
            "java": probe(["java", "-version"]),
            "sbt": probe(["sbt", "--version"]),
            "verilator": probe(["verilator", "--version"]),
            "yosys": probe(["yosys", "-V"]),
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
