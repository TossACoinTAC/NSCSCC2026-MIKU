#!/usr/bin/env python3
"""Static contracts for the Linux SoC overlay and its VGA sibling.

This check intentionally does not invoke Vivado.  It verifies the source-level
contracts that otherwise tend to fail late during integration: top-level
ports, XDC references and critical pins, device-tree addresses and IRQs, APB
decode slots, interrupt ordering, clock declarations, and AXI burst lengths.
"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path


LINUX_TOP = Path("platform/soc-linux/overlay/chip/soc_demo/loongson/soc_top.v")
LINUX_XDC = Path("platform/soc-linux/overlay/fpga/loongson/soc_up.xdc")
VGA_TOP = Path("platform/soc-vga/overlay/chip/soc_demo/nscscc-team/soc_top.v")
VGA_XDC = Path("platform/soc-vga/overlay/fpga/nscscc-team/constraints/soc_lite.xdc")
APB_MUX = Path("platform/soc-linux/overlay/IP/APB_DEV/apb_mux2.v")
APB_TOP = Path("platform/soc-linux/overlay/IP/APB_DEV/apb_dev_top_with_nand.v")
CONFIG = Path("platform/soc-linux/overlay/chip/soc_demo/loongson/config.h")
SOURCE_MANIFEST = Path("platform/soc-linux/source-manifest.json")
DTS = Path("platform/soc-linux/overlay/dts/loongson32_ls.dts")
DEFAULT_PLL_XCI = Path("chiplab/IP/xilinx_ip/2023.2/clk_pll_33/clk_pll_33.xci")


Port = tuple[str, int]


LINUX_REQUIRED_PORTS: dict[str, Port] = {
    "resetn": ("input", 1),
    "clk": ("input", 1),
    "ddr3_dq": ("inout", 16),
    "ddr3_addr": ("output", 13),
    "ddr3_ba": ("output", 3),
    "ddr3_ras_n": ("output", 1),
    "ddr3_cas_n": ("output", 1),
    "ddr3_we_n": ("output", 1),
    "ddr3_odt": ("output", 1),
    "ddr3_reset_n": ("output", 1),
    "ddr3_cke": ("output", 1),
    "ddr3_dm": ("output", 2),
    "ddr3_dqs_p": ("inout", 2),
    "ddr3_dqs_n": ("inout", 2),
    "ddr3_ck_p": ("output", 1),
    "ddr3_ck_n": ("output", 1),
    "mtxclk_0": ("input", 1),
    "mtxen_0": ("output", 1),
    "mtxd_0": ("output", 4),
    "mtxerr_0": ("output", 1),
    "mrxclk_0": ("input", 1),
    "mrxdv_0": ("input", 1),
    "mrxd_0": ("input", 4),
    "mrxerr_0": ("input", 1),
    "mcoll_0": ("input", 1),
    "mcrs_0": ("input", 1),
    "mdc_0": ("output", 1),
    "mdio_0": ("inout", 1),
    "phy_rstn": ("output", 1),
    "PS2_clk": ("inout", 1),
    "PS2_dat": ("inout", 1),
    "UTMI_clk": ("input", 1),
    "UTMI_data": ("inout", 8),
    "UTMI_reset": ("output", 1),
    "UTMI_txready": ("input", 1),
    "UTMI_rxvalid": ("input", 1),
    "UTMI_rxactive": ("input", 1),
    "UTMI_rxerror": ("input", 1),
    "UTMI_linestate": ("input", 2),
    "UTMI_hostdisc": ("input", 1),
    "UTMI_iddig": ("input", 1),
    "UTMI_vbusvalid": ("input", 1),
    "UTMI_sessend": ("input", 1),
    "UTMI_txvalid": ("output", 1),
    "UTMI_opmode": ("output", 2),
    "UTMI_xcvrsel": ("output", 2),
    "UTMI_termsel": ("output", 1),
    "UTMI_dppulldown": ("output", 1),
    "UTMI_dmpulldown": ("output", 1),
    "UTMI_idpullup": ("output", 1),
    "UTMI_chrgvbus": ("output", 1),
    "UTMI_dischrgvbus": ("output", 1),
    "UTMI_suspend_n": ("output", 1),
    "LCD_data": ("inout", 16),
    "LCD_nrst": ("output", 1),
    "LCD_csel": ("output", 1),
    "LCD_rd": ("output", 1),
    "LCD_rs": ("output", 1),
    "LCD_wr": ("output", 1),
    "LCD_lighton": ("output", 1),
}


VGA_REQUIRED_PORTS: dict[str, Port] = {
    "resetn_fpga": ("input", 1),
    "clk": ("input", 1),
    "VGA_r": ("inout", 4),
    "VGA_g": ("inout", 4),
    "VGA_b": ("inout", 4),
    "VGA_hsync": ("output", 1),
    "VGA_vsync": ("output", 1),
}


LINUX_CRITICAL_PINS = {
    "clk": "AC19",
    "resetn": "Y3",
    "PS2_clk": "Y2",
    "PS2_dat": "AD1",
    "UTMI_chrgvbus": "AF3",
    "UTMI_clk": "AA20",
    "UTMI_data[0]": "AA3",
    "UTMI_data[1]": "AC3",
    "UTMI_data[2]": "AE1",
    "UTMI_data[3]": "AB4",
    "UTMI_data[4]": "AD3",
    "UTMI_data[5]": "AA4",
    "UTMI_data[6]": "AC4",
    "UTMI_data[7]": "AE2",
    "UTMI_dischrgvbus": "AE3",
    "UTMI_dmpulldown": "AC1",
    "UTMI_dppulldown": "AC2",
    "UTMI_hostdisc": "AD4",
    "UTMI_iddig": "W4",
    "UTMI_idpullup": "AD5",
    "UTMI_linestate[0]": "AA5",
    "UTMI_linestate[1]": "AE5",
    "UTMI_opmode[0]": "AC6",
    "UTMI_opmode[1]": "AF5",
    "UTMI_reset": "AD23",
    "UTMI_rxactive": "AB5",
    "UTMI_rxerror": "AB2",
    "UTMI_rxvalid": "AF22",
    "UTMI_sessend": "AF2",
    "UTMI_suspend_n": "AE20",
    "UTMI_termsel": "AE21",
    "UTMI_txready": "AD21",
    "UTMI_txvalid": "AF23",
    "UTMI_vbusvalid": "AB1",
    "UTMI_xcvrsel[0]": "AD20",
    "UTMI_xcvrsel[1]": "AF4",
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
    "LCD_nrst": "J25",
    "LCD_csel": "H18",
    "LCD_rd": "K8",
    "LCD_rs": "K16",
    "LCD_wr": "L8",
    "LCD_lighton": "J15",
}


VGA_CRITICAL_PINS = {
    "clk": "AC19",
    "resetn_fpga": "Y3",
    "VGA_r[3]": "U4",
    "VGA_r[2]": "U2",
    "VGA_r[1]": "T2",
    "VGA_r[0]": "T3",
    "VGA_g[3]": "R5",
    "VGA_g[2]": "U1",
    "VGA_g[1]": "R1",
    "VGA_g[0]": "R2",
    "VGA_b[3]": "P3",
    "VGA_b[2]": "P1",
    "VGA_b[1]": "N1",
    "VGA_b[0]": "P5",
    "VGA_hsync": "U5",
    "VGA_vsync": "U6",
}


DEVICE_CONTRACTS = {
    "cpu_uart0": (0x1FE001E0, 0x10, 3),
    "gmac0": (0x1FF00000, 0x10000, 2),
    "confreg": (0x1FD0E000, 0x2000, None),
    "ps2": (0x1FE04000, 0x8, 7),
    "usb0": (0x1FE0C000, 0x1000, 8),
    "lcd": (0x1FE08000, 0x8, None),
}


@dataclass
class Results:
    checks: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def require(self, condition: bool, message: str) -> bool:
        self.checks += 1
        if not condition:
            self.errors.append(message)
        return condition

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise RuntimeError(f"cannot read {path}: {exc}") from exc


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_source_manifest(results: Results, root: Path, path: Path) -> None:
    try:
        document = json.loads(read_text(path))
    except (RuntimeError, json.JSONDecodeError) as exc:
        results.require(False, f"source manifest: {exc}")
        return
    records = document.get("files")
    if not results.require(isinstance(records, list), "source manifest: files must be a list"):
        return
    platform_root = root / "platform/soc-linux"
    expected_paths = {
        str(source.relative_to(platform_root))
        for source in (platform_root / "overlay").rglob("*")
        if source.is_file()
    }
    actual_paths = {
        record.get("path") for record in records if isinstance(record, dict)
    }
    results.require(
        actual_paths == expected_paths,
        "source manifest: file set differs from the committed overlay",
    )
    for record in records:
        if not isinstance(record, dict) or not isinstance(record.get("path"), str):
            results.require(False, f"source manifest: invalid record {record!r}")
            continue
        source = platform_root / record["path"]
        results.require(source.is_file(), f"source manifest: missing {record['path']}")
        if source.is_file():
            results.require(
                record.get("sha256") == sha256(source),
                f"source manifest: SHA256 mismatch for {record['path']}",
            )
    results.notes.append(f"source manifest: {len(records)} overlay files checked")


def strip_c_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//.*", "", text)


def normalize(value: str) -> str:
    return re.sub(r"\s+", "", value)


def parse_top_ports(text: str, source: Path) -> dict[str, Port]:
    clean = strip_c_comments(text)
    match = re.search(
        r"\bmodule\s+soc_top\b(?:\s*#\s*\(.*?\))?\s*\((.*?)\)\s*;",
        clean,
        flags=re.DOTALL,
    )
    if not match:
        raise RuntimeError(f"cannot find soc_top ANSI port header in {source}")
    ports: dict[str, Port] = {}
    declaration = re.compile(
        r"\b(input|output|inout)\b\s*(?:wire\b|reg\b|logic\b)?\s*"
        r"(?:\[\s*(\d+)\s*:\s*(\d+)\s*\])?\s*([A-Za-z_]\w*)"
    )
    for direction, msb, lsb, name in declaration.findall(match.group(1)):
        width = abs(int(msb) - int(lsb)) + 1 if msb else 1
        ports[name] = (direction, width)
    if not ports:
        raise RuntimeError(f"soc_top has no parsed ports in {source}")
    return ports


def check_required_ports(
    results: Results, label: str, ports: dict[str, Port], expected: dict[str, Port]
) -> None:
    for name, contract in expected.items():
        results.require(
            ports.get(name) == contract,
            f"{label}: port {name} is {ports.get(name)!r}, expected {contract!r}",
        )


def check_interface_comparison(
    results: Results, linux_ports: dict[str, Port], vga_ports: dict[str, Port]
) -> None:
    common = sorted(set(linux_ports) & set(vga_ports))
    for name in common:
        results.require(
            linux_ports[name] == vga_ports[name],
            f"top comparison: shared port {name} differs: "
            f"Linux={linux_ports[name]!r}, VGA={vga_ports[name]!r}",
        )

    vga_only = {"VGA_r", "VGA_g", "VGA_b", "VGA_hsync", "VGA_vsync"}
    linux_only = {
        "PS2_clk",
        "PS2_dat",
        "UTMI_clk",
        "UTMI_data",
        "LCD_data",
        "mtxclk_0",
        "mrxclk_0",
        "NAND_DATA",
        "SPI_CLK",
        "EJTAG_TCK",
    }
    results.require(
        not (vga_only & set(linux_ports)),
        f"top comparison: VGA-only ports leaked into Linux top: "
        f"{sorted(vga_only & set(linux_ports))}",
    )
    results.require(
        not (linux_only & set(vga_ports)),
        f"top comparison: Linux-only ports leaked into VGA top: "
        f"{sorted(linux_only & set(vga_ports))}",
    )
    results.notes.append(
        f"top interfaces: shared={len(common)}, Linux-only={len(set(linux_ports) - set(vga_ports))}, "
        f"VGA-only={len(set(vga_ports) - set(linux_ports))}"
    )


def active_xdc(text: str) -> str:
    lines = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        lines.append(line.split("#", 1)[0])
    return "\n".join(lines)


def iter_get_ports(text: str) -> list[str]:
    targets = []
    pattern = re.compile(
        r"\[get_ports\s+(?:\{([^}]*)\}|"
        r"([A-Za-z_][A-Za-z0-9_*?]*(?:\[[0-9*]+\])?))\s*\]"
    )
    for match in pattern.finditer(text):
        target = (match.group(1) or match.group(2)).strip()
        targets.extend(token for token in target.split() if token)
    return targets


def target_matches_port(target: str, ports: dict[str, Port]) -> bool:
    bit_match = re.fullmatch(r"([A-Za-z_]\w*)\[([^]]+)\]", target)
    if bit_match:
        name, index = bit_match.groups()
        if name not in ports:
            return False
        if index == "*":
            return ports[name][1] > 1
        if not index.isdigit():
            return False
        return int(index) < ports[name][1]
    if any(character in target for character in "*?"):
        return any(fnmatch.fnmatchcase(name, target) for name in ports)
    return target in ports


def parse_package_pins(text: str) -> dict[str, str]:
    pins: dict[str, str] = {}
    pattern = re.compile(
        r"\bset_property\s+PACKAGE_PIN\s+(\S+)\s+"
        r"\[get_ports\s+(?:\{([^}]*)\}|"
        r"([A-Za-z_][A-Za-z0-9_*?]*(?:\[[0-9*]+\])?))\s*\]"
    )
    for match in pattern.finditer(text):
        pin = match.group(1).upper()
        target = (match.group(2) or match.group(3)).strip()
        if target in pins:
            raise RuntimeError(f"multiple PACKAGE_PIN assignments for {target}")
        pins[target] = pin
    return pins


def clock_period(text: str, target: str) -> float | None:
    for line in text.splitlines():
        if "create_clock" not in line or "get_ports" not in line:
            continue
        if not re.search(rf"\[get_ports\s+\{{?{re.escape(target)}\}}?\s*\]", line):
            continue
        match = re.search(r"-period\s+([0-9]+(?:\.[0-9]+)?)", line)
        if match:
            return float(match.group(1))
    return None


def check_xdc(
    results: Results,
    label: str,
    text: str,
    ports: dict[str, Port],
    critical_pins: dict[str, str],
) -> dict[str, str]:
    source = active_xdc(text)
    for target in iter_get_ports(source):
        results.require(
            target_matches_port(target, ports),
            f"{label} XDC: get_ports target {target!r} is absent or outside its bus",
        )
    try:
        pins = parse_package_pins(source)
    except RuntimeError as exc:
        results.require(False, f"{label} XDC: {exc}")
        pins = {}

    by_pin: dict[str, list[str]] = {}
    for target, pin in pins.items():
        by_pin.setdefault(pin, []).append(target)
    for pin, targets in sorted(by_pin.items()):
        results.require(
            len(targets) == 1,
            f"{label} XDC: package pin {pin} is assigned to {sorted(targets)}",
        )
    for target, expected_pin in critical_pins.items():
        results.require(
            pins.get(target) == expected_pin,
            f"{label} XDC: {target} uses {pins.get(target)!r}, expected {expected_pin}",
        )

    results.require(
        clock_period(source, "clk") == 10.0,
        f"{label} XDC: clk must have a 10.000 ns input-clock period",
    )
    results.notes.append(f"{label} XDC: {len(pins)} package pins, no duplicate assignment")
    return pins


def check_shared_xdc_pins(
    results: Results, linux_pins: dict[str, str], vga_pins: dict[str, str]
) -> None:
    for target in sorted(set(linux_pins) & set(vga_pins)):
        results.require(
            linux_pins[target] == vga_pins[target],
            f"XDC comparison: shared port {target} uses Linux pin {linux_pins[target]} "
            f"but VGA pin {vga_pins[target]}",
        )
    aliases = [("resetn", "resetn_fpga")]
    aliases.extend((f"switch[{i}]", f"switch_fpga[{i}]") for i in range(8))
    aliases.extend((f"btn_step[{i}]", f"btn_step_fpga[{i}]") for i in range(2))
    for linux_name, vga_name in aliases:
        results.require(
            linux_pins.get(linux_name) == vga_pins.get(vga_name),
            f"XDC comparison: {linux_name}/{vga_name} pin mismatch: "
            f"{linux_pins.get(linux_name)!r}/{vga_pins.get(vga_name)!r}",
        )


def extract_braced_node(text: str, label: str) -> str:
    match = re.search(rf"\b{re.escape(label)}\s*:\s*[^{{;]+\{{", text)
    if not match:
        raise RuntimeError(f"DTS node label {label} was not found")
    start = match.end() - 1
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1 : index]
    raise RuntimeError(f"DTS node label {label} has an unterminated body")


def parse_dts_cells(body: str, property_name: str) -> list[int] | None:
    match = re.search(rf"\b{re.escape(property_name)}\s*=\s*<([^;>]+)>\s*;", body)
    if not match:
        return None
    values = []
    for token in match.group(1).split():
        try:
            values.append(int(token, 0))
        except ValueError as exc:
            raise RuntimeError(
                f"DTS property {property_name} contains non-integer cell {token!r}"
            ) from exc
    return values


def check_dts(results: Results, text: str) -> int | None:
    clean = strip_c_comments(text)
    uart_clock = None
    for label, (address, size, irq) in DEVICE_CONTRACTS.items():
        try:
            body = extract_braced_node(clean, label)
            reg = parse_dts_cells(body, "reg")
            interrupts = parse_dts_cells(body, "interrupts")
        except RuntimeError as exc:
            results.require(False, str(exc))
            continue
        results.require(
            reg == [address, size],
            f"DTS {label}: reg={reg!r}, expected [{address:#x}, {size:#x}]",
        )
        expected_interrupts = None if irq is None else [irq]
        results.require(
            interrupts == expected_interrupts,
            f"DTS {label}: interrupts={interrupts!r}, expected {expected_interrupts!r}",
        )
        if label == "cpu_uart0":
            clocks = parse_dts_cells(body, "clock-frequency")
            results.require(
                clocks is not None and len(clocks) == 1 and clocks[0] > 0,
                f"DTS cpu_uart0: invalid clock-frequency {clocks!r}",
            )
            if clocks and len(clocks) == 1:
                uart_clock = clocks[0]
    results.notes.append("DTS: UART, DMFE, confreg, PS/2, USB, and LCD contracts checked")
    return uart_clock


def parse_instance_connections(text: str, module_name: str) -> dict[str, str]:
    clean = strip_c_comments(text)
    match = re.search(
        rf"\b{re.escape(module_name)}\b(?:\s*#\s*\(.*?\))?\s+"
        r"[A-Za-z_]\w*\s*\((.*?)\)\s*;",
        clean,
        flags=re.DOTALL,
    )
    if not match:
        raise RuntimeError(f"cannot find an instance of {module_name}")
    return {
        port: normalize(value)
        for port, value in re.findall(r"\.([A-Za-z_]\w*)\s*\(([^()]*)\)", match.group(1))
    }


def check_apb(results: Results, mux_text: str, top_text: str) -> None:
    macros = {
        int(slot): int(value, 16)
        for slot, value in re.findall(
            r"`define\s+APB_DEV(\d+)\s+6'h([0-9a-fA-F]+)", mux_text
        )
    }
    address_by_slot = {
        0: DEVICE_CONTRACTS["cpu_uart0"][0],
        1: 0x1FE78000,
        2: DEVICE_CONTRACTS["ps2"][0],
        3: DEVICE_CONTRACTS["lcd"][0],
        4: DEVICE_CONTRACTS["usb0"][0],
    }
    names = {0: "uart0", 1: "nand", 2: "ps2", 3: "lcd", 4: "usb"}
    for slot, address in address_by_slot.items():
        expected = (address & 0xFFFFF) >> 14
        results.require(
            macros.get(slot) == expected,
            f"APB slot {slot} ({names[slot]}): decode={macros.get(slot)!r}, expected {expected:#x}",
        )
        decoder_pattern = rf"assign\s+apb{slot}_req\s*=\s*\(\s*apb_addr\s*\[\s*ADDR_APB-1\s*:\s*14\s*\]\s*==\s*`APB_DEV{slot}\s*\)"
        results.require(
            re.search(decoder_pattern, strip_c_comments(mux_text)) is not None,
            f"APB slot {slot}: request decoder no longer uses APB_DEV{slot} and address bits [19:14]",
        )

    try:
        mux_connections = parse_instance_connections(top_text, "apb_mux2")
    except RuntimeError as exc:
        results.require(False, f"APB wiring: {exc}")
        return
    suffixes = ("req", "ack", "rw", "psel", "enab", "addr", "datai", "datao")
    for slot, name in names.items():
        for suffix in suffixes:
            expected = f"apb_{name}_{suffix}"
            results.require(
                mux_connections.get(f"apb{slot}_{suffix}") == expected,
                f"APB slot {slot}: apb{slot}_{suffix} is "
                f"{mux_connections.get(f'apb{slot}_{suffix}')!r}, expected {expected}",
            )

    controller_contracts = {
        "UART_TOP": ("PSEL", "apb_uart0_psel", "PENABLE", "apb_uart0_enab"),
        "nand_module": ("apb_psel", "apb_nand_psel", "apb_enab", "apb_nand_enab"),
        "chiplab_ps2_rx": ("apb_psel", "apb_ps2_psel", "apb_penable", "apb_ps2_enab"),
        "nt35510_apb_adapter": ("apb_psel", "apb_lcd_psel", "apb_penable", "apb_lcd_enab"),
        "apb_usbh_bridge": ("apb_psel", "apb_usb_psel", "apb_penable", "apb_usb_enab"),
    }
    for module_name, (select_port, select_net, enable_port, enable_net) in controller_contracts.items():
        try:
            connections = parse_instance_connections(top_text, module_name)
        except RuntimeError as exc:
            results.require(False, f"APB controller wiring: {exc}")
            continue
        results.require(
            connections.get(select_port) == select_net,
            f"{module_name}: {select_port} is {connections.get(select_port)!r}, expected {select_net}",
        )
        results.require(
            connections.get(enable_port) == enable_net,
            f"{module_name}: {enable_port} is {connections.get(enable_port)!r}, expected {enable_net}",
        )
    results.notes.append("APB: five decode slots and controller bindings checked")


def check_interrupts(results: Results, top_text: str) -> None:
    clean = strip_c_comments(top_text)
    match = re.search(r"assign\s+int_out\s*=\s*\{([^;]+)\}\s*;", clean)
    if not results.require(match is not None, "interrupts: int_out concatenation was not found"):
        return
    signals = [normalize(signal) for signal in match.group(1).split(",")]
    expected = [
        "usb_int_sync[1]",
        "ps2_int",
        "dma_int",
        "nand_int",
        "spi_inta_o",
        "uart0_int",
        "mac_int",
    ]
    results.require(
        signals == expected,
        f"interrupts: int_out order is {signals!r}, expected {expected!r}",
    )
    bit_by_signal = {signal: bit for bit, signal in enumerate(reversed(signals))}
    expected_irqs = {
        "mac_int": DEVICE_CONTRACTS["gmac0"][2],
        "uart0_int": DEVICE_CONTRACTS["cpu_uart0"][2],
        "ps2_int": DEVICE_CONTRACTS["ps2"][2],
        "usb_int_sync[1]": DEVICE_CONTRACTS["usb0"][2],
    }
    for signal, irq in expected_irqs.items():
        actual = bit_by_signal.get(signal)
        actual_irq = actual + 2 if actual is not None else None
        results.require(
            actual_irq == irq,
            f"interrupts: {signal} maps to hardware IRQ {actual_irq}, expected {irq}",
        )
    try:
        core = parse_instance_connections(clean, "core_top")
    except RuntimeError as exc:
        results.require(False, f"interrupts: {exc}")
        return
    results.require(
        core.get("intrpt") == "{1'b0,int_out[6:0]}",
        f"interrupts: core_top.intrpt is {core.get('intrpt')!r}, expected {{1'b0,int_out[6:0]}}",
    )
    results.notes.append("interrupts: DMFE=2, UART=3, PS/2=7, USB=8")


def parse_macro(text: str, name: str) -> int | None:
    match = re.search(rf"`define\s+{re.escape(name)}\s+(\d+)\b", text)
    return int(match.group(1)) if match else None


def parse_wire_width(text: str, signal: str, macros: dict[str, int]) -> int | None:
    clean = strip_c_comments(text)
    match = re.search(
        rf"\bwire\s*\[\s*([^:\]]+)\s*:\s*([^\]]+)\]\s*{re.escape(signal)}\b",
        clean,
    )
    if not match:
        return None

    def evaluate(expression: str) -> int:
        expression = expression.strip()
        for name, value in macros.items():
            expression = re.sub(rf"`{re.escape(name)}\b", str(value), expression)
        if not re.fullmatch(r"[0-9+\-()\s]+", expression):
            raise ValueError(expression)
        total = 0
        sign = 1
        for token in re.findall(r"[+-]|\d+", expression.replace("(", "").replace(")", "")):
            if token == "+":
                sign = 1
            elif token == "-":
                sign = -1
            else:
                total += sign * int(token)
                sign = 1
        return total

    try:
        msb = evaluate(match.group(1))
        lsb = evaluate(match.group(2))
    except ValueError:
        return None
    return abs(msb - lsb) + 1


def is_four_bit_zero_extend(value: str, signal: str) -> bool:
    match = re.fullmatch(r"\{(4)'([bdh])([0]+),([A-Za-z_]\w*)\}", normalize(value))
    return bool(match and match.group(4) == signal)


def check_axi_lengths(results: Results, config_text: str, top_text: str) -> None:
    macros = {name: parse_macro(config_text, name) for name in ("Lawlen", "Larlen")}
    for name in ("Lawlen", "Larlen"):
        results.require(
            macros[name] == 4,
            f"AXI: config.h {name} is {macros[name]!r}, expected 4",
        )
    width_macros = {name: value for name, value in macros.items() if value is not None}
    for signal in ("s0_awlen", "mac_m_awlen", "dma0_awlen"):
        results.require(
            parse_wire_width(top_text, signal, width_macros) == 4,
            f"AXI: {signal} must be a 4-bit local AXI3 burst length",
        )
    for signal in ("s0_arlen", "mac_m_arlen", "dma0_arlen"):
        results.require(
            parse_wire_width(top_text, signal, width_macros) == 4,
            f"AXI: {signal} must be a 4-bit local AXI3 burst length",
        )
    for signal in ("mig_awlen", "mig_arlen"):
        results.require(
            parse_wire_width(top_text, signal, width_macros) == 8,
            f"AXI: {signal} must be 8 bits at the MIG/interconnect boundary",
        )

    clean = strip_c_comments(top_text)
    results.require(
        re.search(r"assign\s+m0_awlen\s*=\s*cpu_awlen\s*\[3:0\]\s*;", clean)
        is not None,
        "AXI: CPU awlen must be explicitly narrowed to the legacy four-bit bus",
    )
    results.require(
        re.search(r"assign\s+m0_arlen\s*=\s*cpu_arlen\s*\[3:0\]\s*;", clean)
        is not None,
        "AXI: CPU arlen must be explicitly narrowed to the legacy four-bit bus",
    )
    results.require(
        re.search(r"wire\s+\[7:0\]\s+cpu_awlen\s*;", clean) is not None,
        "AXI: cpu_awlen must remain eight bits at the core boundary",
    )
    results.require(
        re.search(r"wire\s+\[7:0\]\s+cpu_arlen\s*;", clean) is not None,
        "AXI: cpu_arlen must remain eight bits at the core boundary",
    )

    try:
        interconnect = parse_instance_connections(top_text, "axi_interconnect_0")
    except RuntimeError as exc:
        results.require(False, f"AXI: {exc}")
        return
    expected = {
        "S00_AXI_AWLEN": "s0_awlen",
        "S00_AXI_ARLEN": "s0_arlen",
        "S01_AXI_AWLEN": "mac_m_awlen",
        "S01_AXI_ARLEN": "mac_m_arlen",
        "S02_AXI_AWLEN": "dma0_awlen",
        "S02_AXI_ARLEN": "dma0_arlen",
    }
    for port, signal in expected.items():
        value = interconnect.get(port, "")
        results.require(
            is_four_bit_zero_extend(value, signal),
            f"AXI: {port} is {value!r}; expected a four-bit zero extension of {signal}",
        )
    results.require(
        normalize(interconnect.get("M00_AXI_AWLEN", "")).strip("{}") == "mig_awlen",
        f"AXI: M00_AXI_AWLEN is {interconnect.get('M00_AXI_AWLEN')!r}, expected mig_awlen",
    )
    results.require(
        normalize(interconnect.get("M00_AXI_ARLEN", "")).strip("{}") == "mig_arlen",
        f"AXI: M00_AXI_ARLEN is {interconnect.get('M00_AXI_ARLEN')!r}, expected mig_arlen",
    )
    results.notes.append("AXI: 4-bit AXI3 lengths are zero-extended to 8-bit Xilinx ports")


def read_xci_payload(path: Path) -> str:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            members = sorted(name for name in archive.namelist() if name.lower().endswith(".xci"))
            if not members:
                raise RuntimeError(f"{path} contains no .xci member")
            return "\n".join(
                archive.read(member).decode("utf-8", errors="replace") for member in members
            )
    return read_text(path)


def xci_frequency(text: str, output: int) -> float | None:
    match = re.search(
        rf'"CLKOUT{output}_REQUESTED_OUT_FREQ"\s*:\s*\[\s*\{{[^}}]*'
        r'"value"\s*:\s*"([0-9]+(?:\.[0-9]+)?)"',
        text,
    )
    return float(match.group(1)) if match else None


def check_clocks(
    results: Results,
    top_text: str,
    xdc_text: str,
    uart_clock_hz: int | None,
    pll_path: Path,
    allow_clock_mismatch: bool,
) -> None:
    source = active_xdc(xdc_text)
    results.require(
        clock_period(source, "UTMI_clk") == 16.667,
        "Linux XDC: UTMI_clk must have a 16.667 ns input-clock period",
    )
    try:
        pll_connections = parse_instance_connections(top_text, "clk_pll_33")
    except RuntimeError as exc:
        results.require(False, f"clock contract: {exc}")
        return
    expected_connections = {
        "clk_in1": "clk",
        "clk_out1": "cpu_clk",
        "clk_out2": "uncore_clk",
    }
    for port, net in expected_connections.items():
        results.require(
            pll_connections.get(port) == net,
            f"clock contract: clk_pll_33.{port} is {pll_connections.get(port)!r}, expected {net}",
        )
    results.require(
        re.search(r"\bassign\s+aclk\s*=\s*uncore_clk\s*;", strip_c_comments(top_text))
        is not None,
        "clock contract: APB/uncore aclk is not assigned from uncore_clk",
    )
    try:
        payload = read_xci_payload(pll_path)
    except RuntimeError as exc:
        results.require(False, f"clock contract: {exc}")
        return
    cpu_mhz = xci_frequency(payload, 1)
    uncore_mhz = xci_frequency(payload, 2)
    results.require(cpu_mhz is not None, f"clock contract: CLKOUT1 frequency missing in {pll_path}")
    results.require(uncore_mhz is not None, f"clock contract: CLKOUT2 frequency missing in {pll_path}")
    if uncore_mhz is not None and uart_clock_hz is not None:
        actual_hz = int(round(uncore_mhz * 1_000_000))
        message = (
            f"clock contract: APB/UART clock from {pll_path} is {actual_hz} Hz "
            f"but DTS clock-frequency is {uart_clock_hz} Hz"
        )
        if allow_clock_mismatch and actual_hz != uart_clock_hz:
            results.warn(message + " (allowed by --allow-clock-mismatch)")
        else:
            results.require(actual_hz == uart_clock_hz, message)
    results.notes.append(
        f"clocks: PLL CPU={cpu_mhz if cpu_mhz is not None else 'unknown'} MHz, "
        f"uncore={uncore_mhz if uncore_mhz is not None else 'unknown'} MHz, "
        f"DTS UART={uart_clock_hz if uart_clock_hz is not None else 'unknown'} Hz"
    )


def resolve(root: Path, path: Path) -> Path:
    return path if path.is_absolute() else root / path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument(
        "--pll-xci",
        type=Path,
        default=DEFAULT_PLL_XCI,
        help="clk_pll_33 .xci or zipped .xcix path, relative to the repository",
    )
    parser.add_argument(
        "--allow-clock-mismatch",
        action="store_true",
        help="report the PLL/DTS UART mismatch as a warning; all other contracts remain strict",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.repo_root.resolve()
    paths = {
        "linux_top": resolve(root, LINUX_TOP),
        "linux_xdc": resolve(root, LINUX_XDC),
        "vga_top": resolve(root, VGA_TOP),
        "vga_xdc": resolve(root, VGA_XDC),
        "apb_mux": resolve(root, APB_MUX),
        "apb_top": resolve(root, APB_TOP),
        "config": resolve(root, CONFIG),
        "source_manifest": resolve(root, SOURCE_MANIFEST),
        "dts": resolve(root, DTS),
        "pll": resolve(root, args.pll_xci),
    }
    linux_required = {
        name: path
        for name, path in paths.items()
        if name not in {"vga_top", "vga_xdc"}
    }
    missing = [str(path) for path in linux_required.values() if not path.is_file()]
    if missing:
        for path in missing:
            print(f"ERROR: required file is missing: {path}", file=sys.stderr)
        return 2

    vga_present = paths["vga_top"].is_file() and paths["vga_xdc"].is_file()
    vga_partial = paths["vga_top"].is_file() != paths["vga_xdc"].is_file()
    if vga_partial:
        print(
            "ERROR: VGA overlay is only partially present; provide both the top and XDC",
            file=sys.stderr,
        )
        return 2

    try:
        text_names = {
            "linux_top",
            "linux_xdc",
            "apb_mux",
            "apb_top",
            "config",
            "dts",
        }
        if vga_present:
            text_names.update({"vga_top", "vga_xdc"})
        texts = {
            name: read_text(path)
            for name, path in paths.items()
            if name in text_names
        }
        linux_ports = parse_top_ports(texts["linux_top"], paths["linux_top"])
        vga_ports = (
            parse_top_ports(texts["vga_top"], paths["vga_top"])
            if vga_present
            else {}
        )
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    results = Results()
    check_source_manifest(results, root, paths["source_manifest"])
    check_required_ports(results, "Linux top", linux_ports, LINUX_REQUIRED_PORTS)
    linux_pins = check_xdc(
        results, "Linux", texts["linux_xdc"], linux_ports, LINUX_CRITICAL_PINS
    )
    if vga_present:
        check_required_ports(results, "VGA top", vga_ports, VGA_REQUIRED_PORTS)
        check_interface_comparison(results, linux_ports, vga_ports)
        vga_pins = check_xdc(results, "VGA", texts["vga_xdc"], vga_ports, VGA_CRITICAL_PINS)
        check_shared_xdc_pins(results, linux_pins, vga_pins)
    else:
        results.notes.append("VGA overlay absent; running the Linux-only contract set")
    uart_clock_hz = check_dts(results, texts["dts"])
    check_apb(results, texts["apb_mux"], texts["apb_top"])
    check_interrupts(results, texts["linux_top"])
    check_axi_lengths(results, texts["config"], texts["linux_top"])
    check_clocks(
        results,
        texts["linux_top"],
        texts["linux_xdc"],
        uart_clock_hz,
        paths["pll"],
        args.allow_clock_mismatch,
    )

    for note in results.notes:
        print(f"INFO: {note}")
    for warning in results.warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    if results.errors:
        for error in results.errors:
            print(f"ERROR: {error}", file=sys.stderr)
        print(
            f"FAIL: {len(results.errors)} error(s), {len(results.warnings)} warning(s), "
            f"{results.checks} checks",
            file=sys.stderr,
        )
        return 1
    print(
        f"PASS: {results.checks} checks, {len(results.warnings)} warning(s); no Vivado run performed"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
