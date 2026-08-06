#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    bsp = root / ".bsp"
    bsp.mkdir(parents=True, exist_ok=True)
    stale = bsp / "scala-cli.json"
    if stale.exists():
        stale.unlink()
    document = {
        "name": "MIKU SBT (Docker)",
        "argv": [str(root / "scripts/env/bsp")],
        "version": "1.0.0",
        "bspVersion": "2.1.0",
        "languages": ["scala", "java"],
    }
    (bsp / "miku-sbt.json").write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"BSP: {bsp / 'miku-sbt.json'}")
    print("Metals 工作区请导入 cpu/build.sbt；BSP 命令在 Docker 中启动锁定的 SBT。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
