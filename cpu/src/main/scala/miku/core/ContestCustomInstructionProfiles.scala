package miku.core

import spinal.core._

/** Standalone complex-Load example; replace this profile when the official statement is known. */
object ContestCustomInstructionProfiles {
  private val WordMask = (BigInt(1) << 32) - 1
  private val ComplexLoadImmediate = CustomImmediate.Slice(lsb = 15, width = 11, signed = true)

  // ldxmix.w rd, rj, rk, i11: opcode[31:26]=111001, i11[25:15], rk, rj, rd.
  // The custom AGU aligns rj, folds and scales rk, then adds a signed word displacement.
  private val ComplexLoad = CustomInstructionSpec.load(
    name = "ldxmix.w-example",
    matchValue = BigInt("e4000000", 16),
    matchMask = BigInt("fc000000", 16),
    base = CustomRegister.Rj,
    addressSource2 = CustomRegister.Rk, // Requests the second GPR specifically for address generation.
    destination = CustomRegister.Rd,
    immediate = ComplexLoadImmediate,
    memorySize = CustomMemorySize.Word,
    addressEvaluator = Some(
      CustomMemoryAddressEvaluator.from { (rj, rk, immediate, instruction) =>
        val scale = instruction(16 downto 15).asUInt // i11[1:0], scaling rk by 1/2/4/8.
        val foldedIndex = rk.asUInt ^ (rk.asUInt |>> 16)
        val scaledIndex = (foldedIndex |<< scale).resize(32)
        val alignedBase = rj.asUInt & U(BigInt("fffffff0", 16), 32 bits)
        val byteOffset = ((immediate.asSInt |>> 2) |<< 2).resize(32).asUInt
        (alignedBase + scaledIndex + byteOffset).resize(32)
      }
    )
  )

  private val ComplexLoadProfile = CustomInstructionProfile(
    "example-complex-load",
    Vector(ComplexLoad)
  )

  // These helpers are the software reference model used to construct and check directed cases.
  private def encodeComplexLoad(
      rd: Int,
      rj: Int,
      rk: Int,
      signedWordOffset: Int,
      scale: Int
  ): BigInt = {
    require(signedWordOffset >= -256 && signedWordOffset <= 255)
    require(scale >= 0 && scale < 4)
    val i11 = ((BigInt(signedWordOffset) & 0x1ff) << 2) | BigInt(scale)
    ComplexLoad.matchValue | (i11 << 15) | (BigInt(rk) << 10) |
      (BigInt(rj) << 5) | BigInt(rd)
  }

  private def complexLoadAddress(
      rj: BigInt,
      rk: BigInt,
      signedWordOffset: Int,
      scale: Int
  ): BigInt = {
    val alignedBase = rj & BigInt("fffffff0", 16)
    val foldedIndex = (rk ^ (rk >> 16)) & WordMask
    (alignedBase + ((foldedIndex << scale) & WordMask) + signedWordOffset * 4) & WordMask
  }

  private def loadCase(
      rd: Int,
      rjIndex: Int,
      rkIndex: Int,
      rjValue: BigInt,
      rkValue: BigInt,
      signedWordOffset: Int,
      scale: Int
  ): CustomInstructionVerificationCase =
    CustomInstructionVerificationCase.memory(
      ComplexLoadProfile,
      ComplexLoad,
      instruction = encodeComplexLoad(rd, rjIndex, rkIndex, signedWordOffset, scale),
      source1 = rjValue,
      source2 = rkValue,
      expectedAddress = complexLoadAddress(rjValue, rkValue, signedWordOffset, scale),
      expectedByteMask = 0xf
    )

  val Available: Vector[CustomInstructionProfile] = Vector(ComplexLoadProfile)
  // Exercise all four scales, signed-offset edges, base alignment, folding, and 32-bit wraparound.
  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector(
    loadCase(3, 4, 5, BigInt("00001003", 16), BigInt("00010004", 16), -3, 2),
    loadCase(7, 8, 9, BigInt("fffffff9", 16), BigInt("80018001", 16), 5, 1),
    loadCase(11, 12, 13, BigInt("00002007", 16), BigInt("12345678", 16), 127, 0),
    loadCase(14, 15, 16, BigInt("7ffffffd", 16), BigInt("ffffffff", 16), -256, 3),
    loadCase(17, 18, 19, BigInt("00000000", 16), BigInt("00000000", 16), 255, 0),
    loadCase(20, 21, 22, BigInt("fffffff0", 16), BigInt("00000001", 16), 0, 3),
    loadCase(23, 24, 25, BigInt("0000000f", 16), BigInt("0000ffff", 16), -1, 2),
    loadCase(26, 27, 28, BigInt("80000007", 16), BigInt("7fff0001", 16), -256, 1),
    loadCase(29, 30, 31, BigInt("00000000", 16), BigInt("00000000", 16), -1, 0),
    loadCase(1, 2, 3, BigInt("deadbeef", 16), BigInt("13572468", 16), 255, 3)
  )
}
