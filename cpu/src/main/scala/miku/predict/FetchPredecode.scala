package miku.predict

import miku.core._
import spinal.core._

/** Registered branch facts carried beside one instruction-cache response lane. */
final case class FetchPredecode(config: OooCoreConfig) extends Bundle {
  val valid = Bool()
  val branchType = UInt(PredictedBranchType.Width bits)
  val target = UInt(config.xlen bits)
  val staticTaken = Bool()
  val indirect = Bool()
}

object FetchPredecoder {
  private def constantBool(value: Boolean): Bool = if (value) True else False

  private def opcode(instruction: Bits): UInt = instruction(31 downto 26).asUInt

  private def isDirect(instruction: Bits): Bool = {
    val op = opcode(instruction)
    op === U(0x14, 6 bits) || op === U(0x15, 6 bits)
  }

  private def isIndirect(instruction: Bits): Bool = opcode(instruction) === U(0x13, 6 bits)

  private def isConditional(instruction: Bits): Bool = {
    val op = opcode(instruction)
    op >= U(0x16, 6 bits) && op <= U(0x1b, 6 bits)
  }

  private def isReturn(instruction: Bits): Bool =
    isIndirect(instruction) && instruction(4 downto 0) === 0 &&
      instruction(9 downto 5) === 1 && instruction(25 downto 10) === 0

  private def isCall(instruction: Bits): Bool =
    opcode(instruction) === U(0x15, 6 bits) ||
      (isIndirect(instruction) && instruction(4 downto 0) === 1)

  private def directTarget(config: OooCoreConfig, pc: UInt, instruction: Bits): UInt = {
    val directOffset =
      (instruction(9 downto 0) ## instruction(25 downto 10) ## B(0, 2 bits)).asSInt
        .resize(config.xlen)
        .asUInt
    val conditionalOffset =
      (instruction(25 downto 10) ## B(0, 2 bits)).asSInt.resize(config.xlen).asUInt
    pc + Mux(isDirect(instruction), directOffset, conditionalOffset)
  }

  private def driveStandard(
      output: FetchPredecode,
      config: OooCoreConfig,
      pc: UInt,
      instruction: Bits
  ): Unit = {
    val direct = isDirect(instruction)
    val indirect = isIndirect(instruction)
    val conditional = isConditional(instruction)
    output.valid := direct || indirect || conditional
    output.branchType := PredictedBranchType.direct
    when(conditional) {
      output.branchType := PredictedBranchType.conditional
    }.elsewhen(isReturn(instruction)) {
      output.branchType := PredictedBranchType.ret
    }.elsewhen(isCall(instruction)) {
      output.branchType := PredictedBranchType.call
    }.elsewhen(indirect) {
      output.branchType := PredictedBranchType.indirect
    }
    output.target := pc + 4
    when(direct || conditional) {
      output.target := directTarget(config, pc, instruction)
    }
    output.staticTaken := direct || (conditional && instruction(25))
    output.indirect := indirect
  }

  def drive(
      output: FetchPredecode,
      config: OooCoreConfig,
      pc: UInt,
      instruction: Bits
  ): Unit = {
    driveStandard(output, config, pc, instruction)

    val specifications = config.customInstructionProfile.specifications
    if (specifications.nonEmpty) {
      val matches = specifications.map { specification =>
        (instruction.asUInt & U(specification.matchMask, 32 bits)) ===
          U(specification.matchValue, 32 bits)
      }
      val customValid = matches.reduce(_ || _)
      val customBranch = Bool()
      val customBranchKind = UInt(3 bits)
      val customImmediate = Bits(config.xlen bits)
      val customPredicate = Bool()
      val customTargetEvaluator = Bool()
      customBranch := False
      customBranchKind := CustomBranchKind.Always
      customImmediate := 0
      customPredicate := False
      customTargetEvaluator := False

      for ((specification, matched) <- specifications.zip(matches)) {
        when(matched) {
          customBranch := constantBool(specification.kind == CustomInstructionKind.Branch)
          customBranchKind := U(specification.branchKind, 3 bits)
          customImmediate := specification.immediate.decode(instruction, config.xlen)
          customPredicate := constantBool(specification.branchEvaluator.nonEmpty)
          customTargetEvaluator := constantBool(specification.branchTargetEvaluator.nonEmpty)
        }
      }

      val customIndirect =
        customBranchKind === CustomBranchKind.RegisterIndirect || customTargetEvaluator
      val customComparison = customBranchKind >= CustomBranchKind.Equal &&
        customBranchKind <= CustomBranchKind.UnsignedGreaterOrEqual
      val customConditional = customPredicate || customComparison
      val customDirect =
        customBranchKind === CustomBranchKind.Always && !customPredicate && !customIndirect
      when(customValid) {
        output.valid := customBranch
        output.branchType := PredictedBranchType.direct
        when(customConditional) {
          output.branchType := PredictedBranchType.conditional
        }.elsewhen(customIndirect) {
          output.branchType := PredictedBranchType.indirect
        }
        output.target := pc + 4
        when(customBranch && !customIndirect) {
          output.target := pc + customImmediate.asUInt
        }
        output.staticTaken := customBranch && !customIndirect &&
          (customDirect || (customConditional && customImmediate(config.xlen - 1)))
        output.indirect := customBranch && customIndirect
      }
    }
  }
}
