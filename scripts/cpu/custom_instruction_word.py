#!/usr/bin/env python3
"""Encode one 32-bit custom instruction from fixed bits and named fields."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from typing import Sequence


WORD_MASK = (1 << 32) - 1
OPCODE_MASK = 0xFC000000
STANDARD_OPCODES = {
    0x00,
    0x01,
    0x05,
    0x06,
    0x07,
    0x08,
    0x0A,
    0x0E,
    0x13,
    0x14,
    0x15,
    0x16,
    0x17,
    0x18,
    0x19,
    0x1A,
    0x1B,
}


@dataclass(frozen=True)
class Field:
    name: str
    lsb: int
    width: int
    value: int

    @property
    def mask(self) -> int:
        return ((1 << self.width) - 1) << self.lsb

    @property
    def encoded_value(self) -> int:
        return self.value & ((1 << self.width) - 1)


def parse_integer(value: str) -> int:
    try:
        return int(value.replace("_", ""), 0)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"invalid integer: {value}") from error


def parse_field(value: str) -> Field:
    parts = value.split(":")
    if len(parts) != 4:
        raise argparse.ArgumentTypeError(
            f"invalid field '{value}'; expected NAME:LSB:WIDTH:VALUE"
        )
    name, lsb_text, width_text, field_value_text = parts
    if not name:
        raise argparse.ArgumentTypeError("field name must not be empty")
    try:
        lsb = parse_integer(lsb_text)
        width = parse_integer(width_text)
        field_value = parse_integer(field_value_text)
    except argparse.ArgumentTypeError as error:
        raise argparse.ArgumentTypeError(f"invalid field '{value}': {error}") from error
    return Field(name=name, lsb=lsb, width=width, value=field_value)


def encode(
    base: int,
    fixed_mask: int,
    fields: Sequence[Field],
    *,
    allow_standard_opcode: bool = False,
) -> int:
    if not 0 <= base <= WORD_MASK:
        raise ValueError(f"base is outside a 32-bit word: {base}")
    if not 0 <= fixed_mask <= WORD_MASK:
        raise ValueError(f"fixed mask is outside a 32-bit word: {fixed_mask}")
    if base & ~fixed_mask:
        raise ValueError("base contains a nonzero bit outside the fixed mask")
    if fixed_mask & OPCODE_MASK != OPCODE_MASK:
        raise ValueError("custom instruction mask must fix all six opcode bits")
    opcode = (base & OPCODE_MASK) >> 26
    if opcode in STANDARD_OPCODES and not allow_standard_opcode:
        raise ValueError(f"custom instruction uses standard opcode 0x{opcode:02x}")

    occupied = 0
    result = base
    names: set[str] = set()
    for field in fields:
        if field.name in names:
            raise ValueError(f"duplicate field name: {field.name}")
        names.add(field.name)
        if not 0 <= field.lsb < 32:
            raise ValueError(f"field {field.name} has invalid lsb: {field.lsb}")
        if not 1 <= field.width <= 32 - field.lsb:
            raise ValueError(f"field {field.name} has invalid width: {field.width}")
        minimum = -(1 << (field.width - 1))
        maximum = (1 << field.width) - 1
        if not minimum <= field.value <= maximum:
            raise ValueError(
                f"field {field.name} value {field.value} does not fit {field.width} bits"
            )
        if occupied & field.mask:
            raise ValueError(f"field {field.name} overlaps an earlier field")
        if fixed_mask & field.mask:
            raise ValueError(f"field {field.name} overlaps the fixed instruction mask")
        occupied |= field.mask
        result |= field.encoded_value << field.lsb
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=parse_integer, help="fixed 32-bit value")
    parser.add_argument(
        "--mask",
        required=True,
        type=parse_integer,
        help="32-bit mask selecting every fixed bit, including fixed zeroes",
    )
    parser.add_argument(
        "--field",
        action="append",
        default=[],
        type=parse_field,
        metavar="NAME:LSB:WIDTH:VALUE",
        help="insert one named field; repeat for every variable field",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument(
        "--allow-standard-opcode",
        action="store_true",
        help="accept a standard opcode only when the official statement requires it",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        word = encode(
            args.base,
            args.mask,
            args.field,
            allow_standard_opcode=args.allow_standard_opcode,
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error

    result = {
        "word": f"0x{word:08x}",
        "assembly": f".word 0x{word:08x}",
        "little_endian_hex": word.to_bytes(4, byteorder="little").hex(),
        "fields": {field.name: field.value for field in args.field},
    }
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        for key in ("word", "assembly", "little_endian_hex"):
            print(f"{key}={result[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
