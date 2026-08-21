package miku.core

import spinal.core._

/** Standalone rriwinz example; replace this profile when the official statement is known. */
object ContestCustomInstructionProfiles {
  private val WordMask = (BigInt(1) << 32) - 1

  // rriwinz rd, rj, i16: opcode[31:26]=111000, i16[25:10], rj, rd.
  // i16[4:0]=rjBase, i16[9:5]=offset, i16[14:10]=rdBase; i16[15] is ignored.
  private val Rriwinz = CustomInstructionSpec.compute(
    name = "rriwinz-example",
    matchValue = BigInt("e0000000", 16),
    matchMask = BigInt("fc000000", 16),
    source1 = CustomRegister.Rd, // Read the old destination value for read-modify-write.
    source2 = CustomRegister.Rj,
    destination = CustomRegister.Rd,
    immediate = CustomImmediate.RawI16,
    evaluator = CustomComputeEvaluator.from { (oldRd, rj, instruction) =>
      val rjBase = instruction(14 downto 10).asUInt
      val offset = instruction(19 downto 15).asUInt
      val rdBase = instruction(24 downto 20).asUInt
      val rotateAmount = CustomBitFieldHelpers.popCountWithin(rj, rjBase, offset)
      // Both source and destination fields are independently clipped at bit 31 by the helper.
      CustomBitFieldHelpers.rotateRightWithin(oldRd, rdBase, offset, rotateAmount)
    }
  )

  private val RriwinzProfile = CustomInstructionProfile(
    "example-rriwinz",
    Vector(Rriwinz)
  )

  // These helpers are the software reference model used to construct and check directed cases.
  private def encodeRriwinz(
      rd: Int,
      rj: Int,
      rjBase: Int,
      offset: Int,
      rdBase: Int,
      ignoredBit: Int = 0
  ): BigInt = {
    require(rjBase >= 0 && rjBase < 32)
    require(offset >= 0 && offset < 32)
    require(rdBase >= 0 && rdBase < 32)
    require(ignoredBit == 0 || ignoredBit == 1)
    val i16 = BigInt(rjBase) | (BigInt(offset) << 5) | (BigInt(rdBase) << 10) |
      (BigInt(ignoredBit) << 15)
    Rriwinz.matchValue | (i16 << 10) | (BigInt(rj) << 5) | BigInt(rd)
  }

  private def clippedWidth(base: Int, requestedWidth: Int): Int =
    math.min(requestedWidth, 32 - base)

  private def lowMask(width: Int): BigInt =
    if (width == 0) BigInt(0) else (BigInt(1) << width) - 1

  private def rriwinzResult(
      oldRd: BigInt,
      rj: BigInt,
      rjBase: Int,
      offset: Int,
      rdBase: Int
  ): BigInt = {
    val rjWidth = clippedWidth(rjBase, offset)
    val rotateCount = ((rj >> rjBase) & lowMask(rjWidth)).bitCount
    val rdWidth = clippedWidth(rdBase, offset)
    if (rdWidth == 0) {
      oldRd & WordMask
    } else {
      val fieldMask = lowMask(rdWidth)
      val field = (oldRd >> rdBase) & fieldMask
      val amount = rotateCount % rdWidth
      val rotated =
        if (amount == 0) field
        else ((field >> amount) | (field << (rdWidth - amount))) & fieldMask
      val placedMask = fieldMask << rdBase
      ((oldRd & ~placedMask) | (rotated << rdBase)) & WordMask
    }
  }

  private def rriwinzCase(
      rd: Int,
      rjIndex: Int,
      oldRd: BigInt,
      rjValue: BigInt,
      rjBase: Int,
      offset: Int,
      rdBase: Int,
      ignoredBit: Int = 0
  ): CustomInstructionVerificationCase =
    CustomInstructionVerificationCase.compute(
      RriwinzProfile,
      Rriwinz,
      instruction = encodeRriwinz(rd, rjIndex, rjBase, offset, rdBase, ignoredBit),
      source1 = oldRd,
      source2 = rjValue,
      expectedResult = rriwinzResult(oldRd, rjValue, rjBase, offset, rdBase)
    )

  val Available: Vector[CustomInstructionProfile] = Vector(RriwinzProfile)
  // Cover empty/full fields, independent clipping, large popcounts, ignored i16[15], and invariants.
  val VerificationCases: Vector[CustomInstructionVerificationCase] = Vector(
    rriwinzCase(7, 8, BigInt("00000009", 16), BigInt("00000007", 16), 0, 4, 0),
    rriwinzCase(3, 4, BigInt("89abcdef", 16), BigInt("ffffffff", 16), 0, 0, 0),
    rriwinzCase(5, 6, BigInt("89abcdef", 16), BigInt("ffffffff", 16), 28, 8, 28),
    rriwinzCase(9, 10, BigInt("f1234567", 16), BigInt("f0000000", 16), 28, 8, 5),
    rriwinzCase(11, 12, BigInt("80000001", 16), BigInt("80000000", 16), 31, 31, 0),
    rriwinzCase(13, 14, BigInt("7fffffff", 16), BigInt("ffffffff", 16), 0, 31, 28),
    rriwinzCase(15, 16, BigInt("deadbeef", 16), BigInt("00000000", 16), 5, 17, 9),
    rriwinzCase(17, 18, BigInt("01234567", 16), BigInt("aaaaaaaa", 16), 1, 31, 1),
    rriwinzCase(19, 20, BigInt("ffffffff", 16), BigInt("13579bdf", 16), 3, 19, 7),
    rriwinzCase(21, 22, BigInt("00000000", 16), BigInt("ffffffff", 16), 0, 31, 0),
    rriwinzCase(23, 24, BigInt("a5a55a5a", 16), BigInt("0f0ff0f0", 16), 8, 16, 8, 1),
    rriwinzCase(25, 26, BigInt("80000001", 16), BigInt("80000000", 16), 31, 1, 31),
    rriwinzCase(27, 28, BigInt("12345678", 16), BigInt("87654321", 16), 4, 31, 31),
    rriwinzCase(29, 30, BigInt("0f1e2d3c", 16), BigInt("f0e1d2c3", 16), 16, 16, 16)
  )
}
