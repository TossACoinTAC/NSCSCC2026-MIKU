package miku.core

import spinal.core._

/** Standalone complex-Branch example; replace this profile when the official statement is known. */
object ContestCustomInstructionProfiles {
  private val WordMask = (BigInt(1) << 32) - 1
  private val ResetPc = BigInt("1c000000", 16)

  // bfpar.t rj, rd, i16: opcode[31:26]=111010, i16[25:10], rj, rd.
  // i16 holds base[4:0], width[9:5], sense[10], and a signed target word offset[15:11].
  private val ComplexBranch = CustomInstructionSpec.branch(
    name = "bfpar.t-example",
    matchValue = BigInt("e8000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rj,
    source2 = CustomRegister.Rd,
    immediate = CustomImmediate.RawI16,
    evaluator = Some(
      CustomBranchEvaluator.from { (rj, rd, instruction) =>
        val base = instruction(14 downto 10).asUInt
        val requestedWidth = instruction(19 downto 15).asUInt
        val selected = CustomBitFieldHelpers.extract(rj ^ rd, base, requestedWidth)
        selected.xorR === instruction(20) // Branch when field parity equals i16.sense.
      }
    ),
    targetEvaluator = Some(
      CustomBranchTargetEvaluator.from { (pc, rj, rd, immediate, _) =>
        val signedWordOffset = immediate(15 downto 11).asSInt.resize(32).asUInt
        val commonBits = (rj & rd).asUInt
        val disagreementHigh = (rj ^ rd).asUInt |>> 16
        val mixedWordOffset = commonBits ^ disagreementHigh
        val totalWordOffset = (mixedWordOffset + signedWordOffset).resize(32)
        val byteOffset = (totalWordOffset |<< 2).resize(32)
        (pc + byteOffset).resize(32) // Target is dynamic, PC-relative, and wraps at 32 bits.
      }
    )
  )

  private val ComplexBranchProfile = CustomInstructionProfile(
    "example-complex-branch",
    Vector(ComplexBranch)
  )

  // These helpers are the software reference model used to construct and check directed cases.
  private def encodeComplexBranch(
      rj: Int,
      rd: Int,
      base: Int,
      requestedWidth: Int,
      sense: Int,
      signedWordOffset: Int
  ): BigInt = {
    require(base >= 0 && base < 32)
    require(requestedWidth >= 0 && requestedWidth < 32)
    require(sense == 0 || sense == 1)
    require(signedWordOffset >= -16 && signedWordOffset <= 15)
    val i16 = BigInt(base) | (BigInt(requestedWidth) << 5) | (BigInt(sense) << 10) |
      ((BigInt(signedWordOffset) & 0x1f) << 11)
    ComplexBranch.matchValue | (i16 << 10) | (BigInt(rj) << 5) | BigInt(rd)
  }

  private def branchTaken(
      rj: BigInt,
      rd: BigInt,
      base: Int,
      requestedWidth: Int,
      sense: Int
  ): Boolean = {
    val effectiveWidth = math.min(requestedWidth, 32 - base)
    val fieldMask = if (effectiveWidth == 0) BigInt(0) else (BigInt(1) << effectiveWidth) - 1
    val selected = ((rj ^ rd) >> base) & fieldMask
    (selected.bitCount & 1) == sense
  }

  private def takenTarget(
      rj: BigInt,
      rd: BigInt,
      signedWordOffset: Int
  ): BigInt = {
    val commonBits = (rj & rd) & WordMask
    val disagreementHigh = ((rj ^ rd) >> 16) & WordMask
    val mixedWordOffset = (commonBits ^ disagreementHigh) & WordMask
    val totalWordOffset = (mixedWordOffset + signedWordOffset) & WordMask
    (ResetPc + ((totalWordOffset << 2) & WordMask)) & WordMask
  }

  private def branchCase(
      rjIndex: Int,
      rdIndex: Int,
      rjValue: BigInt,
      rdValue: BigInt,
      base: Int,
      requestedWidth: Int,
      sense: Int,
      signedWordOffset: Int
  ): CustomInstructionVerificationCase = {
    val taken = branchTaken(rjValue, rdValue, base, requestedWidth, sense)
    CustomInstructionVerificationCase.branch(
      ComplexBranchProfile,
      ComplexBranch,
      instruction = encodeComplexBranch(
        rjIndex,
        rdIndex,
        base,
        requestedWidth,
        sense,
        signedWordOffset
      ),
      source1 = rjValue,
      source2 = rdValue,
      expectedTaken = taken,
      expectedTarget = if (taken) takenTarget(rjValue, rdValue, signedWordOffset) else ResetPc + 4
    )
  }

  val Available: Vector[CustomInstructionProfile] = Vector(ComplexBranchProfile)
  // Cover both directions, clipped/empty fields, signed extremes, and dynamic-target wraparound.
  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector(
    branchCase(4, 3, BigInt("12345678", 16), BigInt("0f0f0f0f", 16), 0, 8, 0, 3),
    branchCase(4, 3, BigInt("12345678", 16), BigInt("0f0f0f0f", 16), 0, 8, 1, 3),
    branchCase(6, 5, BigInt("ffffffff", 16), BigInt("00000000", 16), 0, 0, 0, -16),
    branchCase(6, 5, BigInt("ffffffff", 16), BigInt("00000000", 16), 0, 0, 1, -16),
    branchCase(8, 7, BigInt("f0000000", 16), BigInt("80000000", 16), 28, 31, 1, 15),
    branchCase(10, 9, BigInt("80000000", 16), BigInt("00000000", 16), 31, 31, 0, -1),
    branchCase(12, 11, BigInt("aaaaaaaa", 16), BigInt("55555555", 16), 5, 13, 0, 7),
    branchCase(14, 13, BigInt("deadbeef", 16), BigInt("cafe1234", 16), 10, 9, 1, -16),
    branchCase(16, 15, BigInt("00000003", 16), BigInt("00000003", 16), 30, 5, 0, 15),
    branchCase(18, 17, BigInt("ffffffff", 16), BigInt("ffffffff", 16), 16, 16, 0, -8),
    branchCase(20, 19, BigInt("00010000", 16), BigInt("00000001", 16), 16, 1, 1, 0),
    branchCase(22, 21, BigInt("7fffffff", 16), BigInt("80000001", 16), 1, 31, 1, 5)
  )
}
