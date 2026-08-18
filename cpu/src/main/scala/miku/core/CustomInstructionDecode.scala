package miku.core

import spinal.core._

object CustomInstructionDecode {
  def matches(instruction: Bits, specification: CustomInstructionSpec): Bool =
    (instruction.asUInt & U(specification.matchMask, 32 bits)) ===
      U(specification.matchValue, 32 bits)

  def any(instruction: Bits, specifications: Seq[CustomInstructionSpec]): Bool =
    if (specifications.isEmpty) False
    else specifications.map(matches(instruction, _)).reduce(_ || _)

  def compute(instruction: Bits, profile: CustomInstructionProfile): Bool =
    any(instruction, profile.computeSpecifications)
}
