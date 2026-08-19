#!/usr/bin/env python3
"""Validate and describe one complete Linux SoC implementation."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Any


EXPECTED_PART = "xc7a200tfbg676-2"
EXPECTED_PINS = {
    "clk": "AC19",
    "resetn": "Y3",
    "PS2_clk": "Y2",
    "PS2_dat": "AD1",
    "UTMI_clk": "AA20",
    "UTMI_reset": "AD23",
    "UTMI_data[0]": "AA3",
    "UTMI_data[1]": "AC3",
    "UTMI_data[2]": "AE1",
    "UTMI_data[3]": "AB4",
    "UTMI_data[4]": "AD3",
    "UTMI_data[5]": "AA4",
    "UTMI_data[6]": "AC4",
    "UTMI_data[7]": "AE2",
    "LCD_csel": "H18",
    "LCD_nrst": "J25",
    "LCD_lighton": "J15",
    "LCD_data[0]": "H9",
    "LCD_data[1]": "K17",
    "LCD_data[2]": "J20",
    "LCD_data[3]": "M17",
    "LCD_data[4]": "L17",
    "LCD_data[5]": "L18",
    "LCD_data[6]": "L15",
    "LCD_data[7]": "M15",
    "LCD_data[8]": "M16",
    "LCD_data[9]": "L14",
    "LCD_data[10]": "M14",
    "LCD_data[11]": "F22",
    "LCD_data[12]": "G22",
    "LCD_data[13]": "G21",
    "LCD_data[14]": "H24",
    "LCD_data[15]": "J16",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_record(path: Path) -> dict[str, object]:
    return {
        "path": path.name,
        "size": path.stat().st_size,
        "sha256": sha256(path),
    }


def require_file(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit(f"required Linux implementation artifact is missing or empty: {path}")


def parse_float(text: str, key: str, source: Path) -> float:
    match = re.search(
        rf"^{re.escape(key)}=([-+0-9.eE]+)$", text, re.MULTILINE
    )
    if match is None:
        raise SystemExit(f"missing {key} in {source}")
    value = float(match.group(1))
    if not math.isfinite(value):
        raise SystemExit(f"non-finite {key} in {source}: {value}")
    return value


def parse_route_status(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace")
    patterns = {
        "routing_errors": r"# of nets with routing errors\.*\s*:\s+([0-9]+)",
        "fully_routed_nets": r"# of fully routed nets\.*\s*:\s+([0-9]+)",
        "routable_nets": r"# of routable nets\.*\s*:\s+([0-9]+)",
    }
    values: dict[str, int] = {}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match is None:
            raise SystemExit(f"unable to parse {key} from route report: {path}")
        values[key] = int(match.group(1))
    if (
        values["routing_errors"] != 0
        or values["fully_routed_nets"] == 0
        or values["fully_routed_nets"] != values["routable_nets"]
    ):
        raise SystemExit(f"Linux implementation is not fully routed: {values}")
    return {**values, "design_state": "Fully Routed"}


def parse_drc(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace")
    violations = re.search(r"Violations found:\s+([0-9]+)", text)
    if violations is None:
        raise SystemExit(f"unable to parse DRC violation count: {path}")
    error_rules = [
        value.strip()
        for value in re.findall(
            r"^\|\s*([^|]+?)\s*\|\s*Error\s*\|", text, re.MULTILINE
        )
    ]
    critical_warning_rules = [
        value.strip()
        for value in re.findall(
            r"^\|\s*([^|]+?)\s*\|\s*Critical Warning\s*\|",
            text,
            re.MULTILINE,
        )
    ]
    if error_rules:
        raise SystemExit(f"DRC Error rules are present: {', '.join(error_rules)}")
    return {
        "violations": int(violations.group(1)),
        "error_rules": error_rules,
        "critical_warning_rules": critical_warning_rules,
    }


def parse_pins(path: Path) -> dict[str, dict[str, str]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    pattern = re.compile(
        r"^port=(\S+) package_pin=(\S+) iostandard=(\S+)$", re.MULTILINE
    )
    pins = {
        port: {"package_pin": package_pin, "iostandard": iostandard}
        for port, package_pin, iostandard in pattern.findall(text)
    }
    for port, expected_pin in EXPECTED_PINS.items():
        actual = pins.get(port)
        if actual is None:
            raise SystemExit(f"routed pin report is missing {port}: {path}")
        if (
            actual["package_pin"].upper() != expected_pin
            or actual["iostandard"].upper() != "LVCMOS33"
        ):
            raise SystemExit(
                f"routed pin mismatch for {port}: {actual}; "
                f"expected {expected_pin}/LVCMOS33"
            )
    return pins


def require_hex40(value: str, label: str) -> None:
    if re.fullmatch(r"[0-9a-f]{40}", value) is None:
        raise SystemExit(f"invalid {label}: {value}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--overlay-tree", required=True)
    parser.add_argument("--chiplab-commit", required=True)
    parser.add_argument("--chiplab-tree", required=True)
    parser.add_argument("--cpu-rtl", type=Path, required=True)
    parser.add_argument("--generation-manifest", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--vivado", required=True)
    parser.add_argument("--vivado-version-file", type=Path, required=True)
    parser.add_argument("--cpu-mhz", type=float, required=True)
    parser.add_argument("--uncore-mhz", type=float, required=True)
    parser.add_argument("--jobs", type=int, required=True)
    args = parser.parse_args()

    args.root = args.root.resolve()
    args.artifact_dir = args.artifact_dir.resolve()
    require_hex40(args.source_commit, "source commit")
    require_hex40(args.overlay_tree, "overlay tree")
    require_hex40(args.chiplab_commit, "Chiplab commit")
    require_hex40(args.chiplab_tree, "Chiplab tree")
    if args.cpu_mhz <= 0.0 or args.uncore_mhz <= 0.0 or args.jobs <= 0:
        raise SystemExit("clock frequencies and job count must be positive")

    required = {
        "bitstream": args.artifact_dir / "soc_top.bit",
        "routed_checkpoint": args.artifact_dir / "soc_top-routed.dcp",
        "clock_validation": args.artifact_dir / "clock-timing-validation.txt",
        "route_report": args.artifact_dir / "route-status.rpt",
        "drc_report": args.artifact_dir / "drc.rpt",
        "timing_report": args.artifact_dir / "timing-summary.rpt",
        "utilization_report": args.artifact_dir / "utilization.rpt",
        "clock_utilization_report": args.artifact_dir / "clock-utilization.rpt",
        "clock_interaction_report": args.artifact_dir / "clock-interaction.rpt",
        "methodology_report": args.artifact_dir / "methodology.rpt",
        "ip_status": args.artifact_dir / "ip-status.txt",
        "pin_report": args.artifact_dir / "routed-pins.txt",
        "vivado_log": args.artifact_dir / "vivado.log",
        "vivado_journal": args.artifact_dir / "vivado.jou",
        "vivado_version": args.vivado_version_file,
        "cpu_rtl": args.cpu_rtl,
        "generation_manifest": args.generation_manifest,
        "source_manifest": args.source_manifest,
    }
    for path in required.values():
        require_file(path)

    version = args.vivado_version_file.read_text(
        encoding="utf-8", errors="replace"
    )
    if (
        re.search(r"\bVivado v2023[.]2\b", version, re.IGNORECASE) is None
        or re.search(r"\bSW Build 4029153\b", version, re.IGNORECASE) is None
    ):
        raise SystemExit("Vivado version or build does not match 2023.2 build 4029153")

    generation = json.loads(
        args.generation_manifest.read_text(encoding="utf-8")
    )
    cpu_rtl_sha256 = sha256(args.cpu_rtl)
    if generation.get("source_dirty") is not False:
        raise SystemExit("CPU generation manifest does not certify clean source")
    if generation.get("source_commit") != args.source_commit:
        raise SystemExit("CPU generation manifest source commit mismatch")
    if generation.get("published_rtl_sha256") != cpu_rtl_sha256:
        raise SystemExit("CPU generation manifest RTL SHA256 mismatch")

    source_manifest = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    if source_manifest.get("profile") != "soc-linux-legacy":
        raise SystemExit("Linux source manifest has an unexpected profile")
    if source_manifest.get("base_chiplab_commit") != args.chiplab_commit:
        raise SystemExit("Linux source manifest Chiplab commit mismatch")

    clock_path = required["clock_validation"]
    clock_text = clock_path.read_text(encoding="utf-8", errors="replace")
    clocks = {
        key: parse_float(clock_text, key, clock_path)
        for key in (
            "requested_cpu_mhz",
            "requested_uncore_mhz",
            "actual_cpu_mhz",
            "actual_uncore_mhz",
            "setup_wns_ns",
            "setup_tns_ns",
            "hold_wns_ns",
            "hold_ths_ns",
        )
    }
    expected_clocks = {
        "requested_cpu_mhz": args.cpu_mhz,
        "requested_uncore_mhz": args.uncore_mhz,
        "actual_cpu_mhz": args.cpu_mhz,
        "actual_uncore_mhz": args.uncore_mhz,
    }
    for key, expected in expected_clocks.items():
        tolerance = max(0.001, expected * 0.01)
        if abs(clocks[key] - expected) > tolerance:
            raise SystemExit(
                f"{key} is {clocks[key]:.6f}; expected "
                f"{expected:.6f} (+/- {tolerance:.6f})"
            )
    if clocks["setup_wns_ns"] < 0.0 or clocks["hold_wns_ns"] < 0.0:
        raise SystemExit(
            "Linux implementation does not meet setup and hold timing: "
            f"{clocks['setup_wns_ns']}, {clocks['hold_wns_ns']}"
        )

    route = parse_route_status(required["route_report"])
    drc = parse_drc(required["drc_report"])
    pins = parse_pins(required["pin_report"])

    files = {name: file_record(path) for name, path in required.items()}
    for path in sorted(args.artifact_dir.glob("*.ltx")):
        files[f"ltx:{path.name}"] = file_record(path)

    document = {
        "schema_version": 1,
        "profile": "soc-linux-legacy",
        "source_commit": args.source_commit,
        "overlay_tree": args.overlay_tree,
        "chiplab_commit": args.chiplab_commit,
        "chiplab_tree": args.chiplab_tree,
        "cpu_source_tree_sha256": generation.get("source_tree_sha256"),
        "cpu_raw_rtl_sha256": generation.get("raw_rtl_sha256"),
        "cpu_published_rtl_sha256": cpu_rtl_sha256,
        "vivado": args.vivado,
        "vivado_version": "2023.2",
        "vivado_build": "4029153",
        "part": EXPECTED_PART,
        "jobs": args.jobs,
        "requested_clocks_mhz": {
            "cpu": args.cpu_mhz,
            "uncore": args.uncore_mhz,
        },
        "actual_clocks_mhz": {
            "cpu": clocks["actual_cpu_mhz"],
            "uncore": clocks["actual_uncore_mhz"],
        },
        "timing_ns": {
            "setup_wns": clocks["setup_wns_ns"],
            "setup_tns": clocks["setup_tns_ns"],
            "hold_wns": clocks["hold_wns_ns"],
            "hold_ths": clocks["hold_ths_ns"],
        },
        "route": route,
        "drc": drc,
        "critical_pins": {port: pins[port] for port in EXPECTED_PINS},
        "files": files,
        "claims": {
            "implementation_complete": True,
            "timing_met": True,
            "fully_routed": True,
            "board_validated": False,
            "linux_boot_validated": False,
            "lcd_observed": False,
            "usb_observed": False,
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
