from __future__ import annotations

import json
from pathlib import Path
import unittest

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts/cpu"))
from contracts import ContractError, parse_core_top_header, validate_port_contract


ROOT = Path(__file__).resolve().parents[3]


class CoreInterfaceTest(unittest.TestCase):
    def test_public_contract(self) -> None:
        contract = json.loads((ROOT / "cpu/reference/core-top.ports.json").read_text())
        ports = validate_port_contract(contract)
        self.assertEqual(len(ports), 49)
        self.assertEqual(sum(item["direction"] == "input" for item in ports), 17)

    def test_negative_width_is_rejected(self) -> None:
        contract = json.loads((ROOT / "cpu/reference/core-top.ports.json").read_text())
        contract["ports"][0]["width"] = 0
        with self.assertRaises(ContractError):
            validate_port_contract(contract)

    def test_generated_rtl_when_present(self) -> None:
        rtl = ROOT / "build/rtl/mycpu_top.v"
        if not rtl.is_file():
            self.skipTest("尚未生成发布 RTL")
        contract = json.loads((ROOT / "cpu/reference/core-top.ports.json").read_text())
        ports, tlbnum = parse_core_top_header(rtl.read_text())
        self.assertEqual(tlbnum, 32)
        self.assertEqual(ports, validate_port_contract(contract))


if __name__ == "__main__":
    unittest.main()
