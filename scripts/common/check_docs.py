#!/usr/bin/env python3
"""Validate stable documentation entry points without parsing prose conclusions."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> int:
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    architecture = (ROOT / "docs/architecture.md").read_text(encoding="utf-8")
    candidates = (ROOT / "docs/optimization-candidates.md").read_text(encoding="utf-8")
    workflow = (ROOT / "docs/verification-workflow.md").read_text(encoding="utf-8")

    require("docs/architecture.md" in readme, "README must link the architecture guide")
    require("docs/optimization-candidates.md" in readme, "README must link the candidate ledger")
    require("[optimization-candidates.md](optimization-candidates.md)" in architecture,
            "architecture must link the candidate ledger")
    require("[optimization-candidates.md](optimization-candidates.md)" in workflow,
            "workflow must link the candidate ledger")
    require("| ID | 方向 |" in candidates, "candidate ledger table is missing")
    require("| 状态 | 已测效果 |" in candidates,
            "candidate ledger must expose status and measured effect")

    ids = re.findall(r"^\|\s*([A-Z]+\d+)\s*\|", candidates, flags=re.MULTILINE)
    require(ids, "candidate ledger contains no candidate IDs")
    duplicates = sorted(candidate for candidate in set(ids) if ids.count(candidate) > 1)
    require(not duplicates, f"candidate IDs are duplicated: {', '.join(duplicates)}")
    for candidate in ("C01", "C08", "E02", "FT01"):
        require(candidate in ids, f"candidate ledger is missing {candidate}")
    require("## 5. 优化候选账本" not in architecture,
            "architecture must not contain a second candidate ledger")

    print(f"documentation contract: {len(ids)} unique candidate IDs")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(f"documentation contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
