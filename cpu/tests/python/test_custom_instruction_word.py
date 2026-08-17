from __future__ import annotations

import contextlib
import io
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from scripts.cpu import custom_instruction_word


class CustomInstructionWordTests(unittest.TestCase):
    def test_encodes_fields_and_little_endian_bytes(self) -> None:
        fields = [
            custom_instruction_word.Field("rd", 0, 5, 3),
            custom_instruction_word.Field("rj", 5, 5, 4),
            custom_instruction_word.Field("imm16", 10, 16, 0x1234),
        ]
        self.assertEqual(
            custom_instruction_word.encode(0xD0000000, 0xFC000000, fields),
            0xD048D083,
        )

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = custom_instruction_word.main(
                [
                    "--base",
                    "0xd0000000",
                    "--mask",
                    "0xfc000000",
                    "--field",
                    "rd:0:5:3",
                    "--field",
                    "rj:5:5:4",
                    "--field",
                    "imm16:10:16:0x1234",
                    "--json",
                ]
            )
        self.assertEqual(result, 0)
        document = json.loads(output.getvalue())
        self.assertEqual(document["assembly"], ".word 0xd048d083")
        self.assertEqual(document["little_endian_hex"], "83d048d0")

    def test_rejects_overlaps_fixed_conflicts_and_duplicate_names(self) -> None:
        with self.assertRaisesRegex(ValueError, "overlaps an earlier field"):
            custom_instruction_word.encode(
                0xD0000000,
                0xFC000000,
                [
                    custom_instruction_word.Field("left", 0, 8, 1),
                    custom_instruction_word.Field("right", 4, 8, 1),
                ],
            )
        with self.assertRaisesRegex(ValueError, "fixed instruction mask"):
            custom_instruction_word.encode(
                0xD0000000,
                0xFC000020,
                [custom_instruction_word.Field("rj", 5, 5, 1)],
            )
        with self.assertRaisesRegex(ValueError, "duplicate field name"):
            custom_instruction_word.encode(
                0xD0000000,
                0xFC000000,
                [
                    custom_instruction_word.Field("value", 0, 5, 1),
                    custom_instruction_word.Field("value", 5, 5, 1),
                ],
            )

    def test_enforces_opcode_and_value_contracts(self) -> None:
        with self.assertRaisesRegex(ValueError, "outside the fixed mask"):
            custom_instruction_word.encode(0x20, 0, [])
        with self.assertRaisesRegex(ValueError, "six opcode bits"):
            custom_instruction_word.encode(0, 0x03FFFFFF, [])
        with self.assertRaisesRegex(ValueError, "standard opcode"):
            custom_instruction_word.encode(0, 0xFC000000, [])
        self.assertEqual(
            custom_instruction_word.encode(
                0,
                0xFC000000,
                [],
                allow_standard_opcode=True,
            ),
            0,
        )
        with self.assertRaisesRegex(ValueError, "does not fit"):
            custom_instruction_word.encode(
                0xD0000000,
                0xFC000000,
                [custom_instruction_word.Field("rd", 0, 5, 32)],
            )

    def test_encodes_negative_fields_as_twos_complement(self) -> None:
        self.assertEqual(
            custom_instruction_word.encode(
                0xD0000000,
                0xFC000000,
                [custom_instruction_word.Field("imm12", 10, 12, -4)],
            ),
            0xD03FF000,
        )


if __name__ == "__main__":
    unittest.main()
