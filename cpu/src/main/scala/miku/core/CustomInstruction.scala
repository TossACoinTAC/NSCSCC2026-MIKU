package miku.core

import spinal.core._
import spinal.lib._

sealed trait CustomInstructionKind

object CustomInstructionKind {
  case object Compute extends CustomInstructionKind
  case object Branch extends CustomInstructionKind
  case object Load extends CustomInstructionKind
  case object Store extends CustomInstructionKind
}

sealed trait CustomRegister {
  def fieldMask: BigInt
}

object CustomRegister {
  case object Unused extends CustomRegister {
    override val fieldMask: BigInt = 0
  }
  case object Rd extends CustomRegister {
    override val fieldMask: BigInt = BigInt("0000001f", 16)
  }
  case object Rj extends CustomRegister {
    override val fieldMask: BigInt = BigInt("000003e0", 16)
  }
  case object Rk extends CustomRegister {
    override val fieldMask: BigInt = BigInt("00007c00", 16)
  }
  final case class Fixed(index: Int) extends CustomRegister {
    require(index >= 0 && index < 32, s"invalid fixed GPR index: $index")
    override val fieldMask: BigInt = 0
  }
}

sealed trait CustomImmediate {
  def fieldMask: BigInt
  def decode(instruction: Bits, xlen: Int): Bits
}

object CustomImmediate {
  case object None extends CustomImmediate {
    override val fieldMask: BigInt = 0
    override def decode(instruction: Bits, xlen: Int): Bits = B(0, xlen bits)
  }

  final case class Slice(
      lsb: Int,
      width: Int,
      signed: Boolean = false,
      leftShift: Int = 0
  ) extends CustomImmediate {
    require(lsb >= 0 && lsb < 32, s"invalid immediate lsb: $lsb")
    require(width > 0 && width <= 32 - lsb, s"invalid immediate width: $width")
    require(leftShift >= 0 && leftShift < 32, s"invalid immediate left shift: $leftShift")

    override val fieldMask: BigInt = ((BigInt(1) << width) - 1) << lsb

    override def decode(instruction: Bits, xlen: Int): Bits = {
      val raw = instruction(lsb + width - 1 downto lsb)
      val extended = if (signed) raw.asSInt.resize(xlen).asBits else raw.resize(xlen)
      if (leftShift == 0) extended
      else (extended.asUInt |<< leftShift).resize(xlen).asBits
    }
  }

  val UnsignedI5: CustomImmediate = Slice(lsb = 10, width = 5)
  val RawI16: CustomImmediate = Slice(lsb = 10, width = 16)
  val SignedI12: CustomImmediate = Slice(lsb = 10, width = 12, signed = true)
  val UnsignedI12: CustomImmediate = Slice(lsb = 10, width = 12)
  val SignedI14Shift2: CustomImmediate =
    Slice(lsb = 10, width = 14, signed = true, leftShift = 2)
  val SignedI16Shift2: CustomImmediate =
    Slice(lsb = 10, width = 16, signed = true, leftShift = 2)

  case object SignedI21Shift2 extends CustomImmediate {
    override val fieldMask: BigInt = BigInt("03fffc1f", 16)

    override def decode(instruction: Bits, xlen: Int): Bits = {
      val raw = instruction(4 downto 0) ## instruction(25 downto 10)
      (raw ## B(0, 2 bits)).asSInt.resize(xlen).asBits
    }
  }

  case object SignedI26Shift2 extends CustomImmediate {
    override val fieldMask: BigInt = BigInt("03ffffff", 16)

    override def decode(instruction: Bits, xlen: Int): Bits = {
      val raw = instruction(9 downto 0) ## instruction(25 downto 10)
      (raw ## B(0, 2 bits)).asSInt.resize(xlen).asBits
    }
  }
}

object CustomMemorySize {
  val Word = 0
  val Byte = 1
  val Half = 2
}

object CustomBranchKind {
  val Always = 0
  val Equal = 1
  val NotEqual = 2
  val SignedLess = 3
  val SignedGreaterOrEqual = 4
  val UnsignedLess = 5
  val UnsignedGreaterOrEqual = 6
  val RegisterIndirect = 7
}

private final case class StandardInstructionEncoding(
    name: String,
    value: BigInt,
    mask: BigInt
) {
  def overlaps(candidateValue: BigInt, candidateMask: BigInt): Boolean = {
    val commonMask = mask & candidateMask
    (value & commonMask) == (candidateValue & commonMask)
  }
}

/** Encoding ranges recognized as legal instructions by [[miku.frontend.La32rDecoder]]. */
private object StandardInstructionEncodings {
  private val FullMask = BigInt("ffff8000", 16)
  private val MajorMask = BigInt("fc000000", 16)
  private val MajorBit25Mask = BigInt("fe000000", 16)
  private val MajorBits25To24Mask = BigInt("ff000000", 16)
  private val MajorOp25Mask = BigInt("ffc00000", 16)
  private val RdMask = CustomRegister.Rd.fieldMask
  private val RjMask = CustomRegister.Rj.fieldMask
  private val RkMask = CustomRegister.Rk.fieldMask

  private def fullValue(
      major: Int,
      op25To22: Int,
      op21To20: Int,
      op19To15: Int
  ): BigInt =
    (BigInt(major) << 26) | (BigInt(op25To22) << 22) |
      (BigInt(op21To20) << 20) | (BigInt(op19To15) << 15)

  private def full(
      name: String,
      op21To20: Int,
      op19To15: Int,
      op25To22: Int = 0,
      major: Int = 0
  ): StandardInstructionEncoding =
    StandardInstructionEncoding(
      name,
      fullValue(major, op25To22, op21To20, op19To15),
      FullMask
    )

  private def major(name: String, value: Int): StandardInstructionEncoding =
    StandardInstructionEncoding(name, BigInt(value) << 26, MajorMask)

  private def majorBit25Zero(name: String, value: Int): StandardInstructionEncoding =
    StandardInstructionEncoding(name, BigInt(value) << 26, MajorBit25Mask)

  private def majorBits25To24Zero(name: String, value: Int): StandardInstructionEncoding =
    StandardInstructionEncoding(name, BigInt(value) << 26, MajorBits25To24Mask)

  private def majorOp25(
      name: String,
      majorValue: Int,
      op25To22: Int
  ): StandardInstructionEncoding =
    StandardInstructionEncoding(
      name,
      (BigInt(majorValue) << 26) | (BigInt(op25To22) << 22),
      MajorOp25Mask
    )

  private val registerOperations = Vector(
    full("add.w", 1, 0x00),
    full("sub.w", 1, 0x02),
    full("slt", 1, 0x04),
    full("sltu", 1, 0x05),
    full("nor", 1, 0x08),
    full("and", 1, 0x09),
    full("or", 1, 0x0a),
    full("xor", 1, 0x0b),
    full("orn", 1, 0x0c),
    full("andn", 1, 0x0d),
    full("sll.w", 1, 0x0e),
    full("srl.w", 1, 0x0f),
    full("sra.w", 1, 0x10),
    full("mul.w", 1, 0x18),
    full("mulh.w", 1, 0x19),
    full("mulh.wu", 1, 0x1a),
    full("div.w", 2, 0x00),
    full("mod.w", 2, 0x01),
    full("div.wu", 2, 0x02),
    full("mod.wu", 2, 0x03),
    full("break", 2, 0x14),
    full("syscall", 2, 0x16),
    full("slli.w", 0, 0x01, op25To22 = 1),
    full("srli.w", 0, 0x09, op25To22 = 1),
    full("srai.w", 0, 0x11, op25To22 = 1)
  )

  private val immediateOperations = Vector(
    0x08 -> "slti",
    0x09 -> "sltui",
    0x0a -> "addi.w",
    0x0d -> "andi",
    0x0e -> "ori",
    0x0f -> "xori"
  ).map { case (op25, name) => majorOp25(name, 0, op25) }

  private val memoryOperations = Vector(
    0x00 -> "ld.b",
    0x01 -> "ld.h",
    0x02 -> "ld.w",
    0x04 -> "st.b",
    0x05 -> "st.h",
    0x06 -> "st.w",
    0x08 -> "ld.bu",
    0x09 -> "ld.hu",
    0x0b -> "preld"
  ).map { case (op25, name) => majorOp25(name, 0x0a, op25) }

  private val branchOperations = Vector(
    0x13 -> "jirl",
    0x14 -> "b",
    0x15 -> "bl",
    0x16 -> "beq",
    0x17 -> "bne",
    0x18 -> "blt",
    0x19 -> "bge",
    0x1a -> "bltu",
    0x1b -> "bgeu"
  ).map { case (opcode, name) => major(name, opcode) }

  private val counterBase = fullValue(0, 0, 0, 0)
  private val counterOperations = Vector(
    StandardInstructionEncoding(
      "rdcntid.w",
      counterBase | (BigInt(0x18) << 10),
      FullMask | RkMask | RdMask
    ),
    StandardInstructionEncoding(
      "rdcntvl.w",
      counterBase | (BigInt(0x18) << 10),
      FullMask | RkMask | RjMask
    ),
    StandardInstructionEncoding(
      "rdcntvh.w",
      counterBase | (BigInt(0x19) << 10),
      FullMask | RkMask | RjMask
    ),
    StandardInstructionEncoding(
      "cpucfg",
      counterBase | (BigInt(0x1b) << 10),
      FullMask | RkMask
    )
  )

  private val privilegedBase = fullValue(1, 9, 0, 0x10)
  private val privilegedOperations = Vector(
    0x0a -> "tlbsrch",
    0x0b -> "tlbrd",
    0x0c -> "tlbwr",
    0x0d -> "tlbfill",
    0x0e -> "ertn"
  ).map { case (rk, name) =>
    StandardInstructionEncoding(
      name,
      privilegedBase | (BigInt(rk) << 10),
      FullMask | RkMask | RjMask | RdMask
    )
  }

  private val invalidateTlbBase = fullValue(1, 9, 0, 0x13)
  private val invalidateTlbOperations = (0 to 6).map { operation =>
    StandardInstructionEncoding(
      s"invtlb-$operation",
      invalidateTlbBase | BigInt(operation),
      FullMask | RdMask
    )
  }.toVector

  val All: Vector[StandardInstructionEncoding] =
    registerOperations ++ immediateOperations ++ memoryOperations ++ branchOperations ++
      counterOperations ++ privilegedOperations ++ invalidateTlbOperations ++ Vector(
        full("idle", 0, 0x11, op25To22 = 9, major = 1),
        full("dbar", 3, 4, op25To22 = 1, major = 0x0e),
        full("ibar", 3, 5, op25To22 = 1, major = 0x0e),
        majorOp25("cacop", 1, 8),
        majorBit25Zero("lu12i.w", 5),
        majorBit25Zero("pcaddi", 6),
        majorBit25Zero("pcaddu12i", 7),
        majorBits25To24Zero("csr", 1),
        majorBit25Zero("ll.w/sc.w", 8)
      )
}

object CustomEncoding {
  val OpcodeMask: BigInt = BigInt("fc000000", 16)
  private val OperationBase = 0x3000
  private val OperationCapacity = 0x1000
  private val StandardOpcodes: Set[Int] = Set(
    0x00,
    0x01,
    0x05,
    0x06,
    0x07,
    0x08,
    0x0a,
    0x0e,
    0x13,
    0x14,
    0x15,
    0x16,
    0x17,
    0x18,
    0x19,
    0x1a,
    0x1b
  )

  private def overlaps(
      leftValue: BigInt,
      leftMask: BigInt,
      rightValue: BigInt,
      rightMask: BigInt
  ): Boolean = {
    val commonMask = leftMask & rightMask
    (leftValue & commonMask) == (rightValue & commonMask)
  }

  def operation(index: Int): Int = {
    require(index >= 0 && index < OperationCapacity, s"custom instruction index out of range: $index")
    OperationBase | index
  }

  def validate(
      name: String,
      matchValue: BigInt,
      matchMask: BigInt,
      allowStandardOpcode: Boolean
  ): Unit = {
    require(
      (matchMask & OpcodeMask) == OpcodeMask,
      s"custom instruction $name must fix all six opcode bits"
    )
    if (!allowStandardOpcode) {
      val opcode = ((matchValue & OpcodeMask) >> 26).toInt
      require(
        !StandardOpcodes.contains(opcode),
        f"custom instruction $name uses standard opcode 0x$opcode%02x"
      )
      StandardInstructionEncodings.All.find(_.overlaps(matchValue, matchMask)).foreach { standard =>
        throw new IllegalArgumentException(
          s"custom instruction $name overlaps standard instruction ${standard.name}"
        )
      }
    }
  }

  def encodingsOverlap(left: CustomInstructionSpec, right: CustomInstructionSpec): Boolean =
    overlaps(left.matchValue, left.matchMask, right.matchValue, right.matchMask)
}

trait CustomComputeEvaluator {
  def apply(source1: Bits, source2: Bits, instruction: Bits): Bits
}

object CustomComputeEvaluator {
  def from(function: (Bits, Bits, Bits) => Bits): CustomComputeEvaluator =
    new CustomComputeEvaluator {
      override def apply(source1: Bits, source2: Bits, instruction: Bits): Bits =
        function(source1, source2, instruction)
    }
}

trait CustomBranchEvaluator {
  def apply(source1: Bits, source2: Bits, instruction: Bits): Bool
}

object CustomBranchEvaluator {
  def from(function: (Bits, Bits, Bits) => Bool): CustomBranchEvaluator =
    new CustomBranchEvaluator {
      override def apply(source1: Bits, source2: Bits, instruction: Bits): Bool =
        function(source1, source2, instruction)
    }
}

object CustomBitFieldHelpers {
  private def controlWidth(dataWidth: Int): Int = {
    require(dataWidth > 0, s"bit-field data width must be positive: $dataWidth")
    log2Up(dataWidth + 1)
  }

  private def requireControlFits(control: UInt, dataWidth: Int, name: String): Unit =
    require(
      control.getWidth <= controlWidth(dataWidth),
      s"$name is wider than the ${controlWidth(dataWidth)}-bit control range for $dataWidth bits"
    )

  def clippedWidth(base: UInt, requestedWidth: UInt, dataWidth: Int): UInt = {
    val width = controlWidth(dataWidth)
    requireControlFits(base, dataWidth, "bit-field base")
    requireControlFits(requestedWidth, dataWidth, "bit-field width")
    val extendedBase = base.resize(width)
    val extendedRequest = requestedWidth.resize(width)
    val remaining = UInt(width bits)
    remaining := 0
    when(extendedBase < dataWidth) {
      remaining := U(dataWidth, width bits) - extendedBase
    }
    Mux(extendedRequest < remaining, extendedRequest, remaining)
  }

  def lowMask(requestedWidth: UInt, dataWidth: Int): Bits = {
    val width = controlWidth(dataWidth)
    requireControlFits(requestedWidth, dataWidth, "bit-field width")
    val boundedWidth = UInt(width bits)
    boundedWidth := requestedWidth.resized
    when(requestedWidth.resize(width) > dataWidth) {
      boundedWidth := dataWidth
    }
    ((U(1, (dataWidth + 1) bits) |<< boundedWidth) - 1).resize(dataWidth).asBits
  }

  def extract(value: Bits, base: UInt, requestedWidth: UInt): Bits = {
    val effectiveWidth = clippedWidth(base, requestedWidth, value.getWidth)
    ((value.asUInt |>> base) & lowMask(effectiveWidth, value.getWidth).asUInt).asBits
  }

  def insert(original: Bits, field: Bits, base: UInt, requestedWidth: UInt): Bits = {
    val dataWidth = original.getWidth
    val effectiveWidth = clippedWidth(base, requestedWidth, dataWidth)
    val fieldMask = lowMask(effectiveWidth, dataWidth).asUInt
    val placedMask = (fieldMask |<< base).resize(dataWidth)
    ((original.asUInt & ~placedMask) |
      (((field.resize(dataWidth).asUInt & fieldMask) |<< base).resize(dataWidth) &
        placedMask)).asBits
  }

  def popCount(value: Bits): UInt = {
    val resultWidth = controlWidth(value.getWidth)
    val terms = (0 until value.getWidth).map { index =>
      value(index).asUInt.resize(resultWidth)
    }.toVector

    def balancedSum(values: Vector[UInt]): UInt =
      if (values.size == 1) values.head
      else {
        val next = values.grouped(2).map {
          case Vector(left, right) => (left + right).resize(resultWidth)
          case Vector(left)        => left
          case _                   => throw new IllegalStateException("invalid popcount reduction")
        }.toVector
        balancedSum(next)
      }

    balancedSum(terms)
  }

  def popCountWithin(value: Bits, base: UInt, requestedWidth: UInt): UInt =
    popCount(extract(value, base, requestedWidth))

  private def normalizedShift(shift: UInt, activeWidth: UInt, dataWidth: Int): UInt = {
    val width = controlWidth(dataWidth)
    requireControlFits(shift, dataWidth, "bit-field rotate amount")
    requireControlFits(activeWidth, dataWidth, "active bit-field width")
    val extendedWidth = width * 2
    var remainder = shift.resize(width)
    for (bit <- (width - 1) to 0 by -1) {
      val next = UInt(width bits)
      val multiple = activeWidth.resize(extendedWidth) |<< bit
      val canSubtract = activeWidth =/= 0 && remainder.resize(extendedWidth) >= multiple
      next := Mux(
        canSubtract,
        (remainder.resize(extendedWidth) - multiple).resize(width),
        remainder
      )
      remainder = next
    }
    Mux(activeWidth === 0, U(0, width bits), remainder)
  }

  def rotateRightWithin(
      original: Bits,
      base: UInt,
      requestedWidth: UInt,
      shift: UInt
  ): Bits = {
    val dataWidth = original.getWidth
    val effectiveWidth = clippedWidth(base, requestedWidth, dataWidth)
    val fieldMask = lowMask(effectiveWidth, dataWidth).asUInt
    val field = extract(original, base, requestedWidth).asUInt
    val amount = normalizedShift(shift, effectiveWidth, dataWidth)
    val inverseAmount = effectiveWidth - amount
    val rotated = ((field |>> amount) | (field |<< inverseAmount)) & fieldMask
    insert(original, rotated.asBits, base, effectiveWidth)
  }
}

object CustomComputeEvaluators {
  val passSource1: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, _, _) => source1 }

  val add: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, source2, _) =>
    (source1.asUInt + source2.asUInt).resize(source1.getWidth).asBits
  }

  val subtract: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, source2, _) =>
      (source1.asUInt - source2.asUInt).resize(source1.getWidth).asBits
    }

  val xor: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, source2, _) => source1 ^ source2 }

  val popCount: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, _, _) =>
    CustomBitFieldHelpers.popCount(source1).resize(source1.getWidth).asBits
  }

  val countLeadingZeros: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, _, _) =>
      val countWidth = log2Up(source1.getWidth + 1)
      val result = UInt(countWidth bits)
      result := source1.getWidth
      when(source1.orR) {
        result := OHToUInt(OHMasking.first(source1.reversed)).resize(countWidth)
      }
      result.resize(source1.getWidth).asBits
    }

  val countTrailingZeros: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, _, _) =>
      val countWidth = log2Up(source1.getWidth + 1)
      val result = UInt(countWidth bits)
      result := source1.getWidth
      when(source1.orR) {
        result := OHToUInt(OHMasking.first(source1)).resize(countWidth)
      }
      result.resize(source1.getWidth).asBits
    }

  val parity: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, _, _) => source1.xorR.asBits.resize(source1.getWidth) }

  val rotateRight: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, source2, _) =>
      val shiftWidth = log2Up(source1.getWidth)
      val shift = source2(shiftWidth - 1 downto 0).asUInt
      val inverse = U(source1.getWidth, (shiftWidth + 1) bits) - shift.resize(shiftWidth + 1)
      val right = source1.asUInt |>> shift
      val left = source1.asUInt |<< inverse
      (right | left).resize(source1.getWidth).asBits
    }

  val byteSwap: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, _, _) =>
    source1(7 downto 0) ## source1(15 downto 8) ## source1(23 downto 16) ##
      source1(31 downto 24)
  }

  val bitReverse: CustomComputeEvaluator =
    CustomComputeEvaluator.from { (source1, _, _) => source1.reversed }

  val signedMin: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, source2, _) =>
    Mux(source1.asSInt < source2.asSInt, source1, source2)
  }

  val signedMax: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, source2, _) =>
    Mux(source1.asSInt > source2.asSInt, source1, source2)
  }

  val unsignedMin: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, source2, _) =>
    Mux(source1.asUInt < source2.asUInt, source1, source2)
  }

  val unsignedMax: CustomComputeEvaluator = CustomComputeEvaluator.from { (source1, source2, _) =>
    Mux(source1.asUInt > source2.asUInt, source1, source2)
  }
}

object CustomBranchEvaluators {
  val source1Zero: CustomBranchEvaluator =
    CustomBranchEvaluator.from { (source1, _, _) => source1 === 0 }

  val source1NonZero: CustomBranchEvaluator =
    CustomBranchEvaluator.from { (source1, _, _) => source1 =/= 0 }

  val selectedBitSet: CustomBranchEvaluator =
    CustomBranchEvaluator.from { (source1, source2, _) =>
      source1(source2(log2Up(source1.getWidth) - 1 downto 0).asUInt)
    }
}

final case class CustomInstructionSpec(
    name: String,
    matchValue: BigInt,
    matchMask: BigInt,
    kind: CustomInstructionKind,
    source1: CustomRegister = CustomRegister.Unused,
    source2: CustomRegister = CustomRegister.Unused,
    destination: CustomRegister = CustomRegister.Unused,
    immediate: CustomImmediate = CustomImmediate.None,
    source2IsImmediate: Boolean = false,
    memorySize: Int = CustomMemorySize.Word,
    memorySignExtend: Boolean = false,
    branchKind: Int = CustomBranchKind.Always,
    source1IsPc: Boolean = false,
    source2IsFour: Boolean = false,
    allowStandardOpcode: Boolean = false,
    computeEvaluator: Option[CustomComputeEvaluator] = None,
    branchEvaluator: Option[CustomBranchEvaluator] = None
) {
  require(name.nonEmpty, "a custom instruction needs a name")
  require(matchValue >= 0 && matchValue <= 0xffffffffL, s"invalid match value for $name")
  require(matchMask >= 0 && matchMask <= 0xffffffffL, s"invalid match mask for $name")
  require(matchMask != 0, s"custom instruction $name needs at least one fixed encoding bit")
  require((matchValue & ~matchMask) == 0, s"match value has unmasked bits for $name")
  CustomEncoding.validate(name, matchValue, matchMask, allowStandardOpcode)

  val branchLink: Boolean =
    kind == CustomInstructionKind.Branch && destination != CustomRegister.Unused
  val writesGpr: Boolean = kind match {
    case CustomInstructionKind.Compute => true
    case CustomInstructionKind.Load    => true
    case CustomInstructionKind.Branch  => branchLink
    case CustomInstructionKind.Store   => false
  }

  require(memorySize >= 0 && memorySize <= 2, s"invalid memory size for $name")
  require(branchKind >= 0 && branchKind < 8, s"invalid branch kind for $name")
  require(
    !source1IsPc || source1 == CustomRegister.Unused,
    s"$name selects both PC and a GPR as source1"
  )
  require(
    !source2IsImmediate || source2 == CustomRegister.Unused,
    s"$name selects both an immediate and a GPR as source2"
  )
  require(
    !source2IsFour || source2 == CustomRegister.Unused,
    s"$name selects both constant four and a GPR as source2"
  )
  require(!(source2IsImmediate && source2IsFour), s"$name has two source2 selectors")
  require(
    !source2IsImmediate || immediate != CustomImmediate.None,
    s"$name selects an immediate source but defines no immediate field"
  )
  require(
    kind == CustomInstructionKind.Compute ||
      (!source1IsPc && !source2IsImmediate && !source2IsFour),
    s"only compute instruction $name may select PC, immediate, or constant four operands"
  )
  require(
    kind != CustomInstructionKind.Compute || computeEvaluator.nonEmpty,
    s"compute instruction $name needs an evaluator"
  )
  require(
    kind == CustomInstructionKind.Compute || computeEvaluator.isEmpty,
    s"only compute instruction $name may define a compute evaluator"
  )
  require(
    kind == CustomInstructionKind.Branch || branchEvaluator.isEmpty,
    s"only branch instruction $name may define a branch evaluator"
  )
  require(
    branchEvaluator.isEmpty ||
      branchKind == CustomBranchKind.Always ||
      branchKind == CustomBranchKind.RegisterIndirect,
    s"custom branch evaluator $name conflicts with a standard comparison branch kind"
  )
  require(
    !writesGpr || destination != CustomRegister.Unused,
    s"instruction $name writes a GPR but has no destination"
  )
  require(
    kind != CustomInstructionKind.Store || destination == CustomRegister.Unused,
    s"store instruction $name cannot define a destination"
  )
  require(
    (kind != CustomInstructionKind.Load && kind != CustomInstructionKind.Store) ||
      source1 != CustomRegister.Unused,
    s"memory instruction $name needs a base-register source"
  )
  require(
    kind != CustomInstructionKind.Store || source2 != CustomRegister.Unused,
    s"store instruction $name needs a data-register source"
  )
  require(
    kind != CustomInstructionKind.Branch ||
      branchEvaluator.nonEmpty ||
      branchKind == CustomBranchKind.Always ||
      source1 != CustomRegister.Unused,
    s"conditional or indirect branch $name needs a first register source"
  )
  require(
    kind != CustomInstructionKind.Branch ||
      branchKind != CustomBranchKind.RegisterIndirect ||
      source1 != CustomRegister.Unused,
    s"indirect branch $name needs a target-register source"
  )
  require(
    kind != CustomInstructionKind.Branch ||
      branchEvaluator.nonEmpty ||
      branchKind == CustomBranchKind.Always ||
      branchKind == CustomBranchKind.RegisterIndirect ||
      source2 != CustomRegister.Unused,
    s"conditional branch $name needs a second register source"
  )

  private val sourceFieldMasks =
    Vector(source1.fieldMask, source2.fieldMask).filter(_ != 0)
  require(
    sourceFieldMasks.distinct.size == sourceFieldMasks.size,
    s"custom instruction $name uses the same encoded register field for both sources"
  )

  private val variableFields =
    Vector(source1, source2) ++ (if (writesGpr) Vector(destination) else Vector.empty)
  private val registerMasks = variableFields.map(_.fieldMask).filter(_ != 0).distinct
  private val operandMasks =
    registerMasks ++ (if (immediate.fieldMask != 0) Vector(immediate.fieldMask) else Vector.empty)
  operandMasks.foreach { fieldMask =>
    require(
      (matchMask & fieldMask) == 0,
      s"custom instruction $name fixes bits used by an operand field"
    )
  }
  operandMasks.combinations(2).foreach { pair =>
    require(
      (pair.head & pair(1)) == 0,
      s"custom instruction $name has overlapping operand fields"
    )
  }
}

object CustomInstructionSpec {
  def compute(
      name: String,
      matchValue: BigInt,
      matchMask: BigInt,
      evaluator: CustomComputeEvaluator,
      source1: CustomRegister = CustomRegister.Rj,
      source2: CustomRegister = CustomRegister.Rk,
      destination: CustomRegister = CustomRegister.Rd,
      immediate: CustomImmediate = CustomImmediate.None,
      source2IsImmediate: Boolean = false,
      source1IsPc: Boolean = false,
      source2IsFour: Boolean = false,
      allowStandardOpcode: Boolean = false
  ): CustomInstructionSpec =
    CustomInstructionSpec(
      name = name,
      matchValue = matchValue,
      matchMask = matchMask,
      kind = CustomInstructionKind.Compute,
      source1 = source1,
      source2 = source2,
      destination = destination,
      immediate = immediate,
      source2IsImmediate = source2IsImmediate,
      source1IsPc = source1IsPc,
      source2IsFour = source2IsFour,
      allowStandardOpcode = allowStandardOpcode,
      computeEvaluator = Some(evaluator)
    )

  def branch(
      name: String,
      matchValue: BigInt,
      matchMask: BigInt,
      immediate: CustomImmediate,
      source1: CustomRegister = CustomRegister.Unused,
      source2: CustomRegister = CustomRegister.Unused,
      destination: CustomRegister = CustomRegister.Unused,
      branchKind: Int = CustomBranchKind.Always,
      evaluator: Option[CustomBranchEvaluator] = None,
      allowStandardOpcode: Boolean = false
  ): CustomInstructionSpec =
    CustomInstructionSpec(
      name = name,
      matchValue = matchValue,
      matchMask = matchMask,
      kind = CustomInstructionKind.Branch,
      source1 = source1,
      source2 = source2,
      destination = destination,
      immediate = immediate,
      branchKind = branchKind,
      allowStandardOpcode = allowStandardOpcode,
      branchEvaluator = evaluator
    )

  def load(
      name: String,
      matchValue: BigInt,
      matchMask: BigInt,
      immediate: CustomImmediate,
      base: CustomRegister = CustomRegister.Rj,
      destination: CustomRegister = CustomRegister.Rd,
      memorySize: Int = CustomMemorySize.Word,
      signExtend: Boolean = false,
      allowStandardOpcode: Boolean = false
  ): CustomInstructionSpec =
    CustomInstructionSpec(
      name = name,
      matchValue = matchValue,
      matchMask = matchMask,
      kind = CustomInstructionKind.Load,
      source1 = base,
      destination = destination,
      immediate = immediate,
      memorySize = memorySize,
      memorySignExtend = signExtend,
      allowStandardOpcode = allowStandardOpcode
    )

  def store(
      name: String,
      matchValue: BigInt,
      matchMask: BigInt,
      immediate: CustomImmediate,
      base: CustomRegister = CustomRegister.Rj,
      data: CustomRegister = CustomRegister.Rd,
      memorySize: Int = CustomMemorySize.Word,
      allowStandardOpcode: Boolean = false
  ): CustomInstructionSpec =
    CustomInstructionSpec(
      name = name,
      matchValue = matchValue,
      matchMask = matchMask,
      kind = CustomInstructionKind.Store,
      source1 = base,
      source2 = data,
      immediate = immediate,
      memorySize = memorySize,
      allowStandardOpcode = allowStandardOpcode
    )
}

final case class CustomInstructionProfile(
    name: String,
    specifications: Vector[CustomInstructionSpec]
) {
  require(name.nonEmpty, "a custom instruction profile needs a name")
  require(
    name.matches("[a-z0-9][a-z0-9._-]*"),
    s"custom instruction profile name '$name' must use lowercase letters, digits, '.', '_', or '-'"
  )
  require(
    specifications.nonEmpty || name.equalsIgnoreCase("disabled"),
    s"custom instruction profile $name is empty; use the disabled profile instead"
  )
  require(
    specifications.isEmpty || (name != "disabled" && name != "off"),
    "the disabled and off custom instruction profile names cannot contain instructions"
  )
  require(
    specifications.map(_.name).distinct.size == specifications.size,
    s"custom instruction names must be unique in profile $name"
  )
  val indexedSpecifications: Vector[(CustomInstructionSpec, Int)] =
    specifications.zipWithIndex.map { case (specification, index) =>
      specification -> CustomEncoding.operation(index)
    }
  specifications.combinations(2).foreach { pair =>
    require(
      !CustomEncoding.encodingsOverlap(pair.head, pair(1)),
      s"custom instruction encodings overlap: ${pair.head.name} and ${pair(1).name}"
    )
  }

  val computeSpecifications: Vector[CustomInstructionSpec] =
    specifications.filter(_.kind == CustomInstructionKind.Compute)
  val branchSpecifications: Vector[CustomInstructionSpec] =
    specifications.filter(_.kind == CustomInstructionKind.Branch)
}

object CustomInstructionRouting {
  def portNeedsInstruction(config: OooCoreConfig, portIndex: Int): Boolean = {
    val capabilities = config.executionPorts(portIndex).capabilities
    (config.customInstructionProfile.computeSpecifications.nonEmpty &&
      portIndex == config.customComputePort) ||
    (config.customInstructionProfile.branchSpecifications.exists(_.branchEvaluator.nonEmpty) &&
      capabilities.contains(ExecutionUnitKind.Branch))
  }
}

object CustomInstructionProfile {
  val Disabled: CustomInstructionProfile = CustomInstructionProfile("disabled", Vector.empty)

  val Available: Vector[CustomInstructionProfile] =
    Disabled +: ContestCustomInstructionProfiles.Available
  val availableNames: Vector[String] = Available.map(_.name)
  require(
    availableNames.map(_.toLowerCase).distinct.size == availableNames.size,
    "custom instruction profile names must be unique"
  )

  private val byName: Map[String, CustomInstructionProfile] =
    Available.map(profile => profile.name.toLowerCase -> profile).toMap

  def fromName(value: String): CustomInstructionProfile = {
    val normalized = value.trim.toLowerCase
    if (normalized.isEmpty || normalized == "off") Disabled
    else {
      byName.getOrElse(
        normalized,
        throw new IllegalArgumentException(
          s"unknown custom instruction profile '$value'; expected ${availableNames.mkString(" or ")}"
        )
      )
    }
  }
}
